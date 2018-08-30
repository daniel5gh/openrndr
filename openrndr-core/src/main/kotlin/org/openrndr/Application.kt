package org.openrndr

import org.openrndr.math.Matrix44
import org.openrndr.math.Vector2
import kotlin.concurrent.thread

enum class PresentationMode {
    AUTOMATIC,
    MANUAL,
}

enum class Eye {
    Left,
    Right,
}

class HMDCamera() {
    var currentEye = Eye.Left
    var projectionLeft = Matrix44.IDENTITY
    var projectionRight = Matrix44.IDENTITY

    // relative to head
    var eyeLeft = Matrix44.IDENTITY
    var eyeRight = Matrix44.IDENTITY

    var viewLeft = Matrix44.IDENTITY
    var viewRight = Matrix44.IDENTITY

    // generic camera API
    var projection: Matrix44 = Matrix44.IDENTITY
        get() {
            return when (currentEye) {
                Eye.Left -> projectionLeft
                Eye.Right -> projectionRight
            }
        }

    var view: Matrix44 = Matrix44.IDENTITY
        get() {
            return when (currentEye) {
                Eye.Left -> viewLeft
                Eye.Right -> viewRight
            }
        }
}

abstract class Application {
    companion object {

        fun run(program: Program, configuration: Configuration) {
            val c = applicationClass(configuration)
            val application = c.declaredConstructors[0].newInstance(program, configuration) as Application
            application.setup()
            application.loop()
        }

        fun runAsync(program: Program, configuration: Configuration) {
            val c = applicationClass(configuration)
            val application = c.declaredConstructors[0].newInstance(program, configuration) as Application
            thread {
                application.setup()
                application.loop()
            }
        }

        fun applicationClass(configuration: Configuration): Class<*> {
            val c = if (!configuration.headless)
                Application::class.java.classLoader.loadClass("org.openrndr.internal.gl3.ApplicationGLFWGL3")
            else
                Application::class.java.classLoader.loadClass("org.openrndr.internal.gl3.ApplicationEGLGL3")
            return c
        }
    }

    abstract fun requestDraw()

    abstract fun exit()
    abstract fun setup()

    abstract fun loop()
    abstract var clipboardContents: String?
    abstract var windowTitle: String

    abstract var windowPosition: Vector2

    abstract val seconds: Double

    abstract var presentationMode: PresentationMode

    val hmdCamera: HMDCamera = HMDCamera()
}

fun application(program: Program, configuration: Configuration = Configuration()) {
    Application.run(program, configuration)
}

fun resourceUrl(name: String, `class`: Class<*> = Application::class.java): String {
    val resource = `class`.getResource(name)

    if (resource == null) {
        throw RuntimeException("resource $name not found")
    } else {
        return `class`.getResource(name).toExternalForm()
    }
}