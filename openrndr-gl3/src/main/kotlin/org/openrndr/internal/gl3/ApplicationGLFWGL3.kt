package org.openrndr.internal.gl3

import mu.KotlinLogging

import java.lang.IllegalStateException
import java.lang.RuntimeException
import java.io.File

import org.lwjgl.PointerBuffer
import org.lwjgl.glfw.Callbacks.glfwFreeCallbacks
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.glfw.GLFW.glfwSetWindowPos
import org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor
import org.lwjgl.glfw.GLFW.glfwGetVideoMode
import org.lwjgl.opengl.GL.createCapabilities
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.glfw.GLFW.glfwPollEvents
import org.lwjgl.glfw.GLFW.glfwSwapBuffers
import org.lwjgl.glfw.GLFW.glfwWindowShouldClose
import org.lwjgl.glfw.GLFW.GLFW_RELEASE
import org.lwjgl.glfw.GLFW.glfwSetKeyCallback
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GLUtil

import org.openrndr.*
import org.openrndr.draw.Drawer
import org.openrndr.internal.Driver
import org.openrndr.math.Vector2
import java.util.*
import org.lwjgl.opengl.GL30.*
import org.lwjgl.openvr.*
import org.lwjgl.openvr.VR.*
import org.openrndr.draw.RenderTarget
import org.openrndr.draw.renderTarget
import org.openrndr.math.Matrix44
import java.nio.IntBuffer

private val logger = KotlinLogging.logger {}
internal var primaryWindow: Long = NULL

fun convertHmdMatrix44(m : HmdMatrix44) : Matrix44 {
    return Matrix44(
            m.m(0).toDouble(), m.m(1).toDouble(), m.m(2).toDouble(), m.m(3).toDouble(),
            m.m(4).toDouble(), m.m(5).toDouble(), m.m(6).toDouble(), m.m(7).toDouble(),
            m.m(8).toDouble(), m.m(8).toDouble(), m.m(10).toDouble(), m.m(11).toDouble(),
            m.m(12).toDouble(), m.m(13).toDouble(), m.m(14).toDouble(), m.m(15).toDouble()
    )
}

fun convertHmdMatrix34(m : HmdMatrix34) : Matrix44 {
    return Matrix44(
            m.m(0).toDouble(), m.m(4).toDouble(), m.m(8).toDouble(), 0.0,
            m.m(1).toDouble(), m.m(5).toDouble(), m.m(9).toDouble(), 0.0,
            m.m(2).toDouble(), m.m(6).toDouble(), m.m(10).toDouble(), 0.0,
            m.m(3).toDouble(), m.m(7).toDouble(), m.m(11).toDouble(), 1.0
    )
}

class ApplicationGLFWGL3(private val program: Program, private val configuration: Configuration) : Application() {
    private var windowFocused = true
    private var window: Long = NULL
    private var driver: DriverGL3
    private var realWindowTitle = configuration.title
    private var exitRequested = false
    private val fixWindowSize = System.getProperty("os.name").contains("windows", true)
    private var setupCalled = false
    override var presentationMode: PresentationMode = PresentationMode.AUTOMATIC

    private var vrMode = false
//    val hmdCamera = HMDCamera()
    lateinit var rtLeft: RenderTarget
    lateinit var rtRight: RenderTarget

    override var windowPosition: Vector2
        get() {
            val x = IntArray(1)
            val y = IntArray(1)
            glfwGetWindowPos(window, x, y)
            return Vector2(
                    if (fixWindowSize) (x[0].toDouble() / program.window.scale.x) else x[0].toDouble(),
                    if (fixWindowSize) (y[0].toDouble() / program.window.scale.y) else y[0].toDouble())
        }
        set(value) {
            glfwSetWindowPos(window,
                    if (fixWindowSize) (value.x * program.window.scale.x).toInt() else value.x.toInt(),
                    if (fixWindowSize) (value.y * program.window.scale.y).toInt() else value.y.toInt())
        }

    override var clipboardContents: String?
        get() {
            return try {
                val result = glfwGetClipboardString(window)
                result
            } catch (e: Exception) {
                ""
            }
        }
        set(value) {
            if (value != null) {
                glfwSetClipboardString(window, value)
            } else {
                throw RuntimeException("clipboard contents can't be null")
            }
        }

    private var startTimeMillis = System.currentTimeMillis()
    override val seconds: Double
        get() = (System.currentTimeMillis() - startTimeMillis) / 1000.0

    override var windowTitle: String
        get() = realWindowTitle
        set(value) {
            glfwSetWindowTitle(window, value)
            realWindowTitle = value
        }

    init {
        logger.debug { "debug output enabled" }
        logger.trace { "trace level enabled" }

        driver = DriverGL3()
        Driver.driver = driver
        program.application = this
        createPrimaryWindow()
    }

    /**
     * Initialize OpenVR
     *
     * Call after OpenGL context has been created and made current
     */
    private fun vrInit() {
        try {
            val peError : IntBuffer = IntBuffer.allocate(1)
            val eType = EVRApplicationType_VRApplication_Scene
            val token = VR_InitInternal(peError, eType)
            if( peError.get(0) != 0 ) {
                println("OpenVR Initialize Error: ${peError.get(0)} https://github.com/ValveSoftware/openvr/wiki/HmdError")
                println(VR_GetVRInitErrorAsEnglishDescription(peError.get(0)))
                return
            }
            OpenVR.create(token)
            vrMode = true
            println("OpenVR Initialized: ${VR_RuntimePath()} ")

            val pnWidth : IntBuffer = IntBuffer.allocate(1)
            val pnHeight : IntBuffer = IntBuffer.allocate(1)
//            EXCEPTION_ACCESS_VIOLATION?
//            VRSystem.VRSystem_GetRecommendedRenderTargetSize(pnWidth, pnHeight)
            // hack hack, hardcode some size instead
            pnWidth.put(1024*2)
            pnWidth.rewind()
            pnHeight.put(1024*2)
            pnHeight.rewind()
            println("OpenVR Suggested render target size: ${pnWidth.get(0)}x${pnHeight.get(0)}")
            rtLeft = renderTarget(pnWidth.get(0), pnHeight.get(0)) {
                colorBuffer()
                depthBuffer()
            }
            rtRight = renderTarget(pnWidth.get(0), pnHeight.get(0)) {
                colorBuffer()
                depthBuffer()
            }

            // get projection matrices
            val matrix44 = HmdMatrix44.create()
            VRSystem.VRSystem_GetProjectionMatrix(EVREye_Eye_Left, 0.1f, 500.0f, matrix44)
            hmdCamera.projectionLeft = convertHmdMatrix44(matrix44)
            VRSystem.VRSystem_GetProjectionMatrix(EVREye_Eye_Right, 0.1f, 500.0f, matrix44)
            hmdCamera.projectionRight = convertHmdMatrix44(matrix44)
            println("OpenVR projection Left:\n${hmdCamera.projectionLeft}")
            println("OpenVR projection Right:\n${hmdCamera.projectionRight}")

            // get eye offset matrices
            val matrix34 = HmdMatrix34.create()
            VRSystem.VRSystem_GetEyeToHeadTransform(EVREye_Eye_Left, matrix34)
            hmdCamera.eyeLeft = convertHmdMatrix34(matrix34)
            VRSystem.VRSystem_GetEyeToHeadTransform(EVREye_Eye_Right, matrix34)
            hmdCamera.eyeRight = convertHmdMatrix34(matrix34)
            println("OpenVR eye Left:\n${hmdCamera.eyeLeft}")
            println("OpenVR eye Right:\n${hmdCamera.eyeRight}")
            debugGLErrors { null }
        } catch (e: Exception) {
            println("No OpenVR support! $e")
        }
    }

    override fun setup() {
        glfwDefaultWindowHints()
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE)
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)

        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
        glfwWindowHint(GLFW_RESIZABLE, if (configuration.windowResizable) GLFW_TRUE else GLFW_FALSE)
        glfwWindowHint(GLFW_DECORATED, if (configuration.hideWindowDecorations) GLFW_FALSE else GLFW_TRUE)

        glfwWindowHint(GLFW_RED_BITS, 8)
        glfwWindowHint(GLFW_GREEN_BITS, 8)
        glfwWindowHint(GLFW_BLUE_BITS, 8)
        glfwWindowHint(GLFW_STENCIL_BITS, 8)
        glfwWindowHint(GLFW_DEPTH_BITS, 24)

        println(glfwGetVersionString())

        if (useDebugContext) {
            glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE)
        }

        val xscale = FloatArray(1)
        val yscale = FloatArray(1)
        glfwGetMonitorContentScale(glfwGetPrimaryMonitor(), xscale, yscale)

        if (configuration.fullscreen) {
            xscale[0] = 1.0f
            yscale[0] = 1.0f
        }

        logger.debug { "content scale ${xscale[0]} ${yscale[0]}" }
        program.window.scale = Vector2(xscale[0].toDouble(), yscale[0].toDouble())

        logger.debug { "creating window" }
        window = if (!configuration.fullscreen) {
            val adjustedWidth = if (fixWindowSize) (xscale[0] * configuration.width).toInt() else configuration.width
            val adjustedHeight = if (fixWindowSize) (yscale[0] * configuration.height).toInt() else configuration.height

            glfwCreateWindow(adjustedWidth,
                    adjustedHeight,
                    configuration.title, NULL, primaryWindow)
        } else {
            logger.info { "creating fullscreen window" }

            var requestWidth = configuration.width
            var requestHeight = configuration.height

            if (requestWidth == -1 || requestHeight == -1) {
                val mode = glfwGetVideoMode(glfwGetPrimaryMonitor())
                if (mode != null) {
                    requestWidth = mode.width()
                    requestHeight = mode.height()
                } else {
                    throw RuntimeException("failed to determine current video mode")
                }
            }

            glfwCreateWindow(requestWidth,
                    requestHeight,
                    configuration.title, glfwGetPrimaryMonitor(), primaryWindow)
        }

        logger.debug { "window created: $window" }

        if (window == NULL) {
            throw RuntimeException("Failed to create the GLFW window")
        }

        // Get the thread stack and push a new frame
        stackPush().let { stack ->
            val pWidth = stack.mallocInt(1) // int*
            val pHeight = stack.mallocInt(1) // int*

            // Get the window size passed to glfwCreateWindow
            glfwGetWindowSize(window, pWidth, pHeight)

            // Get the resolution of the primary monitor
            val vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor())

            if (configuration.position == null) {
                if (vidmode != null) {
                    // Center the window
                    glfwSetWindowPos(
                            window,
                            (vidmode.width() - pWidth.get(0)) / 2,
                            (vidmode.height() - pHeight.get(0)) / 2
                    )
                }
            } else {
                configuration.position?.let {
                    glfwSetWindowPos(window,
                            it.x,
                            it.y)
                }
            }
            Unit
        }

        logger.debug { "making context current" }
        glfwMakeContextCurrent(window)

        if (glfwExtensionSupported("GLX_EXT_swap_control_tear") || glfwExtensionSupported("WGL_EXT_swap_control_tear")) {
            glfwSwapInterval(-1)
        } else {
            glfwSwapInterval(1)
        }

        var readyFrames = 0

        glfwSetWindowRefreshCallback(window) {
            if (readyFrames > 0) {
                if (setupCalled)
                drawFrame()
                glfwSwapBuffers(window)
            }
            readyFrames++
        }

        glfwSetFramebufferSizeCallback(window) { window, width, height ->
            logger.debug { "resizing window to ${width}x${height} " }

            if (readyFrames > 0) {
                setupSizes()
                program.window.sized.trigger(WindowEvent(WindowEventType.RESIZED, program.window.position, program.window.size, true))
            }

            readyFrames++
            logger.debug { "all ok" }
        }

        glfwSetWindowPosCallback(window) { _, x, y ->
            logger.debug { "window has moved to $x $y" }
            program.window.moved.trigger(WindowEvent(WindowEventType.MOVED, Vector2(x.toDouble(), y.toDouble()), Vector2(0.0, 0.0), true))
        }

        glfwSetWindowFocusCallback(window) { _, focused ->
            logger.debug { "window focus has changed; focused=$focused" }
            windowFocused = focused
            if (focused) {

                program.window.focused.trigger(
                        WindowEvent(WindowEventType.FOCUSED, Vector2(0.0, 0.0), Vector2(0.0, 0.0), true))
            } else {
                program.window.unfocused.trigger(
                        WindowEvent(WindowEventType.FOCUSED, Vector2(0.0, 0.0), Vector2(0.0, 0.0), false))
            }
        }
        logger.debug { "glfw version: ${glfwGetVersionString()}" }
        logger.debug { "showing window" }
        glfwShowWindow(window)
    }

    private fun createPrimaryWindow() {
        if (primaryWindow == NULL) {
            glfwSetErrorCallback(GLFWErrorCallback.create { error, description ->
                logger.error(
                        "LWJGL Error - Code: {}, Description: {}",
                        Integer.toHexString(error),
                        GLFWErrorCallback.getDescription(description)
                )
            })
            if (!glfwInit()) {
                throw IllegalStateException("Unable to initialize GLFW")
            }

            glfwDefaultWindowHints()
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3)
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3)
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GL_TRUE)
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE)
            glfwWindowHint(GLFW_RED_BITS, 8)
            glfwWindowHint(GLFW_GREEN_BITS, 8)
            glfwWindowHint(GLFW_BLUE_BITS, 8)
            glfwWindowHint(GLFW_STENCIL_BITS, 8)
            glfwWindowHint(GLFW_DEPTH_BITS, 24)
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE)
            primaryWindow = glfwCreateWindow(640, 480, "OPENRNDR primary window", NULL, NULL)
        }
    }
    private val vaos = IntArray(1)

    fun preloop() {
        createCapabilities()

        if (useDebugContext) {
            GLUtil.setupDebugMessageCallback()
        }

        glGenVertexArrays(vaos)
        glBindVertexArray(vaos[0])
        driver = DriverGL3()
        driver.defaultVAO = vaos[0]
        program.driver = driver
        program.drawer = Drawer(driver)

        val defaultRenderTarget = ProgramRenderTargetGL3(program)
        defaultRenderTarget.bind()

        // sets this.vrMode if VR can be initialized
        vrInit()

        setupSizes()
        program.drawer.ortho()
    }

    private var drawRequested = true

    override fun loop() {
        logger.debug { "starting loop" }
        preloop()

        var lastDragPosition = Vector2.ZERO
        var globalModifiers = setOf<KeyboardModifier>()

        glfwSetKeyCallback(window) { _, key, scancode, action, mods ->
            val modifiers = modifierSet(mods)
            val name = glfwGetKeyName(key, scancode) ?: "<null>"

            globalModifiers = modifiers
            when (action) {
                GLFW_PRESS -> program.keyboard.keyDown.trigger(KeyEvent(KeyEventType.KEY_DOWN, key, scancode, name, modifiers))
                GLFW_RELEASE -> program.keyboard.keyUp.trigger(KeyEvent(KeyEventType.KEY_UP, key, scancode, name, modifiers))
                GLFW_REPEAT -> program.keyboard.keyRepeat.trigger(KeyEvent(KeyEventType.KEY_REPEAT, key, scancode, name, modifiers))
            }
        }

        glfwSetCharCallback(window) { window, codepoint ->
            program.keyboard.character.trigger(CharacterEvent(codepoint.toChar(), emptySet()))
        }

        glfwSetDropCallback(window) { _, count, names ->
            logger.debug { "$count file(s) have been dropped" }
            val pointers = PointerBuffer.create(names, count)
            val files = (0 until count).map {
                File(pointers.getStringUTF8(0))
            }
            program.window.drop.trigger(DropEvent(Vector2(0.0, 0.0), files))
        }

        var down = false
        glfwSetScrollCallback(window) { _, xoffset, yoffset ->
            program.mouse.scrolled.trigger(Mouse.MouseEvent(program.mouse.position, Vector2(xoffset, yoffset), Vector2.ZERO, MouseEventType.SCROLLED, MouseButton.NONE, globalModifiers))
        }

        glfwSetMouseButtonCallback(window) { _, button, action, mods ->
            val mouseButton = when (button) {
                GLFW_MOUSE_BUTTON_LEFT -> MouseButton.LEFT
                GLFW_MOUSE_BUTTON_RIGHT -> MouseButton.RIGHT
                GLFW_MOUSE_BUTTON_MIDDLE -> MouseButton.CENTER
                else -> MouseButton.NONE
            }

            val modifiers = mutableSetOf<KeyboardModifier>()
            val buttonsDown = BitSet()

            if (mods and GLFW_MOD_SHIFT != 0) {
                modifiers.add(KeyboardModifier.SHIFT)
            }
            if (mods and GLFW_MOD_ALT != 0) {
                modifiers.add(KeyboardModifier.ALT)
            }
            if (mods and GLFW_MOD_CONTROL != 0) {
                modifiers.add(KeyboardModifier.CTRL)
            }
            if (mods and GLFW_MOD_SUPER != 0) {
                modifiers.add(KeyboardModifier.SUPER)
            }

            if (action == GLFW_PRESS) {
                down = true
                lastDragPosition = program.mouse.position
                program.mouse.buttonDown.trigger(
                        Mouse.MouseEvent(program.mouse.position, Vector2.ZERO, Vector2.ZERO, MouseEventType.BUTTON_DOWN, mouseButton, modifiers)
                )
                buttonsDown.set(button, true)
            }

            if (action == GLFW_RELEASE) {
                down = false
                program.mouse.buttonUp.trigger(
                        Mouse.MouseEvent(program.mouse.position, Vector2.ZERO, Vector2.ZERO, MouseEventType.BUTTON_UP, mouseButton, modifiers)
                )
                buttonsDown.set(button, false)

                program.mouse.clicked.trigger(
                        Mouse.MouseEvent(program.mouse.position, Vector2.ZERO, Vector2.ZERO, MouseEventType.CLICKED, mouseButton, modifiers)
                )
            }
        }

        glfwSetCursorPosCallback(window) { _, xpos, ypos ->
            val position = if (fixWindowSize) Vector2(xpos, ypos) / program.window.scale else Vector2(xpos, ypos)
            logger.trace { "mouse moved $xpos $ypos -- $position" }
            program.mouse.position = position
            program.mouse.moved.trigger(Mouse.MouseEvent(position, Vector2.ZERO, Vector2.ZERO, MouseEventType.MOVED, MouseButton.NONE, globalModifiers))
            if (down) {
                program.mouse.dragged.trigger(Mouse.MouseEvent(position, Vector2.ZERO, position - lastDragPosition, MouseEventType.DRAGGED, MouseButton.NONE, globalModifiers))
                lastDragPosition = position
            }
        }

        glfwSetCursorEnterCallback(window) { window, entered ->
            logger.debug { "cursor state changed; inside window = $entered" }
        }


        if (configuration.showBeforeSetup) {
            logger.debug { "clearing and displaying pre-setup" }

            // clear the front buffer
            glDepthMask(true)
            glClearColor(0.5f, 0.5f, 0.5f, 0.0f)
            glClear(GL_COLOR_BUFFER_BIT or GL_STENCIL_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

            // swap the color buffers
            glfwSwapBuffers(window)

            // clear the back buffer
            glClearColor(0.5f, 0.5f, 0.5f, 0.0f)
            glClear(GL_COLOR_BUFFER_BIT or GL_STENCIL_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            glDepthMask(false)

            glfwPollEvents()
        }
        logger.debug { "opengl vendor: ${glGetString(GL_VENDOR)}" }
        logger.debug { "opengl version: ${glGetString(GL_VERSION)}" }

        println("opengl vendor: ${glGetString(GL_VENDOR)}")
        println("opengl version: ${glGetString(GL_VERSION)}")

        if (configuration.hideCursor) {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_HIDDEN)
        }

        logger.debug { "calling program.setup" }
        program.setup()
        setupCalled = true

        startTimeMillis = System.currentTimeMillis()

        if (glfwExtensionSupported("GLX_EXT_swap_control_tear") || glfwExtensionSupported("WGL_EXT_swap_control_tear")) {
            glfwSwapInterval(-1)
        } else {
            glfwSwapInterval(1)
        }

        var exception: Throwable? = null
        while (!exitRequested && !glfwWindowShouldClose(window)) {
            if (presentationMode == PresentationMode.AUTOMATIC || drawRequested) {
                drawRequested = false
                exception = drawFrame()
                if (exception != null) {
                    break
                }
                glfwSwapBuffers(window)
            }

            if (!windowFocused && configuration.unfocusBehaviour == UnfocusBehaviour.THROTTLE) {
                Thread.sleep(100)
            }

            if (presentationMode == PresentationMode.AUTOMATIC) {
                glfwPollEvents()
            } else {
                Thread.sleep(1)
                glfwPollEvents()
                deliverEvents()
            }
        }
        logger.info { "exiting loop" }

        if (vrMode)
            VR_ShutdownInternal()
        glfwFreeCallbacks(window)
        glfwDestroyWindow(window)

        // TODO: take care of these when all windows are closed
        //glfwTerminate()
        //glfwSetErrorCallback(null)?.free()
        logger.info { "done" }

        exception?.let {
            throw it
        }
    }

    private fun deliverEvents() {
        program.window.drop.deliver()
        program.window.sized.deliver()
        program.keyboard.keyDown.deliver()
        program.keyboard.keyUp.deliver()
        program.keyboard.keyRepeat.deliver()
        program.keyboard.character.deliver()
        program.mouse.moved.deliver()
        program.mouse.scrolled.deliver()
        program.mouse.clicked.deliver()
        program.mouse.buttonDown.deliver()
        program.mouse.buttonUp.deliver()
        program.mouse.dragged.deliver()
    }

    /**
     * Does all the needful VR things at the start of a frame
     *
     * It's best to call this as late as possible before any rendering will be done.
     * This because we get the HMD orientation here and we want to reduce the lag as
     * much as possible.
     */
    private fun vrPreDraw() {
        val pRenderPoseArray = TrackedDevicePose.create(k_unMaxTrackedDeviceCount)
        val pGamePoseArray = TrackedDevicePose.create(k_unMaxTrackedDeviceCount)

        // TODO handle openvr events VRSystem_PollNextEvent and processVREvent

        VRCompositor.VRCompositor_WaitGetPoses(pRenderPoseArray, pGamePoseArray)
        // get first render pose use its mDeviceToAbsoluteTracking as head space matrix
        val view= convertHmdMatrix34(pRenderPoseArray.get(0).mDeviceToAbsoluteTracking())
        hmdCamera.viewLeft = hmdCamera.eyeLeft * view
        hmdCamera.viewRight = hmdCamera.eyeRight * view
    }


    private fun vrDraw() {
        // TODO(VR): if rendering separately, can we use just one target? saving some gpu mem
        hmdCamera.currentEye = Eye.Left
        rtLeft.bind()
        program.drawImpl()
        rtLeft.unbind()
        hmdCamera.currentEye = Eye.Right
        rtRight.bind()
        program.drawImpl()
        rtRight.unbind()

        val texture = Texture.create()
        texture.handle((rtLeft.colorBuffers[0] as ColorBufferGL3).texture.toLong())
        texture.eType(ETextureType_TextureType_OpenGL)
        texture.eColorSpace(EColorSpace_ColorSpace_Gamma)
        VRCompositor.VRCompositor_Submit(
                EVREye_Eye_Left,
                texture,
                null,
                EVRSubmitFlags_Submit_Default
        )
        texture.handle((rtRight.colorBuffers[0] as ColorBufferGL3).texture.toLong())
        VRCompositor.VRCompositor_Submit(
                EVREye_Eye_Right,
                texture,
                null,
                EVRSubmitFlags_Submit_Default
        )
        // TODO(VR): OpenVR compositor expects a texture object of target GL_TEXTURE_2D_MULTISAMPLE, ours is GL_TEXTURE_2D, leading to a "GL_INVALID_OPERATION error generated. Target doesn't match the texture's target."
        GL11.glGetError() // clear error state :)
        checkGLErrors { null }
    }

    private fun drawFrame(): Throwable? {
        setupSizes()
        glBindVertexArray(vaos[0])
        program.drawer.reset()
        program.drawer.ortho()
        deliverEvents()
        try {
            logger.trace { "window: ${program.window.size.x.toInt()}x${program.window.size.y.toInt()} program: ${program.width}x${program.height}" }
            if (vrMode) {
                vrPreDraw()
                vrDraw()
            }
            // if vr mode, mirror mode ON, drawing right eye because this is the state of hmdCamera left by vrDraw()
            // note that this will use the right eye's perspective matrix, with a non square window it will look stretched
            program.drawImpl()
        } catch (e: Throwable) {
            logger.error { "caught exception, breaking animation loop" }
            e.printStackTrace()
            return e
        }
        return null
    }

    private fun setupSizes() {
        val wcsx = FloatArray(1)
        val wcsy = FloatArray(1)
        glfwGetWindowContentScale(window, wcsx, wcsy)
        program.window.scale = Vector2(wcsx[0].toDouble(), wcsy[0].toDouble())

        val fbw = IntArray(1)
        val fbh = IntArray(1)
        glfwGetFramebufferSize(window, fbw, fbh)

        glViewport(0, 0, fbw[0], fbh[0])
        program.width = Math.ceil(fbw[0] / program.window.scale.x).toInt()
        program.height = Math.ceil(fbh[0] / program.window.scale.y).toInt()
        program.window.size = Vector2(program.width.toDouble(), program.height.toDouble())
        program.drawer.width = program.width
        program.drawer.height = program.height
    }

    private fun modifierSet(mods: Int): Set<KeyboardModifier> {
        val modifiers = mutableSetOf<KeyboardModifier>()
        if (mods and GLFW_MOD_SHIFT != 0) {
            modifiers.add(KeyboardModifier.SHIFT)
        }
        if (mods and GLFW_MOD_ALT != 0) {
            modifiers.add(KeyboardModifier.ALT)
        }
        if (mods and GLFW_MOD_CONTROL != 0) {
            modifiers.add(KeyboardModifier.CTRL)
        }
        if (mods and GLFW_MOD_SUPER != 0) {
            modifiers.add(KeyboardModifier.SUPER)
        }
        return modifiers
    }

    override fun exit() {
        exitRequested = true
    }

    override fun requestDraw() {
        drawRequested = true
    }
}
