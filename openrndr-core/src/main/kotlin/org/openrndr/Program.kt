package org.openrndr

import org.openrndr.color.ColorRGBa
import org.openrndr.draw.Drawer
import org.openrndr.events.Event
import org.openrndr.internal.Driver
import org.openrndr.math.IntVector2
import org.openrndr.math.Vector2

import java.io.File

/**
 *
 */
enum class UnfocusBehaviour {
    NORMAL, /** Continue as usual **/
    THROTTLE /** Throttle drawing **/
}

class Mouse {
    class MouseEvent(val position: Vector2, val rotation: Vector2, val dragDisplacement: Vector2, val type: MouseEventType, val button: MouseButton, val modifiers: Set<KeyboardModifier>, var propagationCancelled:Boolean = false) {
        fun cancelPropagation() {
            propagationCancelled = true
        }
    }

    var position = Vector2(0.0, 0.0)

    val buttonDown = Event<MouseEvent>().postpone(true)
    val buttonUp = Event<MouseEvent>().postpone(true)
    val dragged = Event<MouseEvent>().postpone(true)
    val moved = Event<MouseEvent>().postpone(true)
    val scrolled = Event<MouseEvent>().postpone(true)
    val clicked =  Event<MouseEvent>().postpone(true)
}


class CharacterEvent(val character:Char, val modifiers: Set<KeyboardModifier>, var propagationCancelled: Boolean = false) {
    fun cancelPropagation() {
        propagationCancelled = true
    }
}

class Keyboard {
    val keyDown = Event<KeyEvent>().postpone(true)
    val keyUp = Event<KeyEvent>().postpone(true)
    val keyRepeat = Event<KeyEvent>().postpone(true)
    val character = Event<CharacterEvent>().postpone(true)
}

class Configuration {

    /**
     * The preferred window width
     */
    var width: Int = 640

    /**
     * The preferred window height
     */
    var height: Int = 480

    /**
     * The window title
     */
    var title: String = "OPENRNDR"

    /**
     * Should debug mode be used?
     */
    var debug: Boolean = false
    var trace: Boolean = false

    /**
     * Should window decorations be hidden?
     */
    var hideWindowDecorations = false

    /**
     * Should the window be made fullscreen?
     */
    var fullscreen = false

    /**
     * Should the window be made visible before calling setup?
     */
    var showBeforeSetup = true

    /**
     * Should the cursor be hidden?
     */
    var hideCursor = false

    /**
     * The window position. The window will be placed in the center of the primary screen when set to null
     */
    var position: IntVector2? = null

    /**
     * The window and drawing behaviour on window unfocus
     **/
    var unfocusBehaviour = UnfocusBehaviour.NORMAL


    var windowResizable:Boolean = false

    /**
     * Should the application be run in headless mode. 
     */
    var headless: Boolean = false

}

/**
 * Simple configuration builder
 */
fun configuration(builder:Configuration.()->Unit):Configuration {
    return Configuration().apply(builder)
}

enum class KeyboardModifier(val mask: Int) {
    SHIFT(1),
    CTRL(2),
    ALT(4),
    SUPER(8)
}

enum class MouseButton {
    LEFT,
    RIGHT,
    CENTER,
    NONE
}

enum class MouseEventType {
    MOVED,
    DRAGGED,
    CLICKED,
    BUTTON_UP,
    BUTTON_DOWN,
    SCROLLED,
}

enum class KeyEventType {
    KEY_DOWN,
    KEY_UP,
    KEY_REPEAT,
}

class KeyEvent(val type: KeyEventType, val key: Int, val scanCode: Int, val name: String, val modifiers: Set<KeyboardModifier>, var propagationCancelled: Boolean = false) {
    fun cancelPropagation() {
        propagationCancelled = true
    }
}

enum class WindowEventType {
    MOVED,
    RESIZED,
    FOCUSED,
    UNFOCUSED,
}

class WindowEvent(val type: WindowEventType, val position: Vector2, val size: Vector2, val focused: Boolean)

class DropEvent(val position:Vector2, val files:List<File>)

val KEY_SPACEBAR = 32

val KEY_ESCAPE = 256
val KEY_ENTER = 257

val KEY_TAB = 258
val KEY_BACKSPACE = 259
val KEY_INSERT = 260
val KEY_DELETE = 261
val KEY_ARROW_RIGHT = 262
val KEY_ARROW_LEFT = 263
val KEY_ARROW_DOWN = 264
val KEY_ARROW_UP = 265
val KEY_PAGE_UP = 266
val KEY_PAGE_DOWN = 267
val KEY_HOME = 268
val KEY_END = 269

val KEY_CAPSLOCK = 280
val KEY_PRINT_SCREEN = 283


val KEY_F1 = 290
val KEY_F2 = 291
val KEY_F3 = 292
val KEY_F4 = 293
val KEY_F5 = 294
val KEY_F6 = 295
val KEY_F7 = 296
val KEY_F8 = 297
val KEY_F9 = 298
val KEY_F10 = 299
val KEY_F11 = 300
val KEY_F12 = 301

val KEY_LEFT_SHIFT = 340
val KEY_RIGHT_SHIFT = 344

/**
    The Program class, this is where most user implementations start
**/
open class Program {

    var width = 0
    var height = 0

    lateinit var drawer: Drawer
    lateinit var driver: Driver

    lateinit var application: Application

    var backgroundColor:ColorRGBa? = ColorRGBa.BLACK

    val seconds: Double
        get() = application.seconds

    inner class Clipboard {
        var contents: String?
            get() {
                return application.clipboardContents
            }
            set(value) {
                application.clipboardContents = contents
            }
    }

    val clipboard = Clipboard()
    private val extensions = mutableListOf<Extension>()

    /**
     * Install an extension
     * @param extension the extension to install
     */
    fun extend(extension: Extension) : Extension{
        extensions.add(extension)
        extension.setup(this)
        return extension
    }

    /**
     * Simplified window interface
     */
    inner class Window {

        var title: String
            get() = application.windowTitle
            set(value) {
                application.windowTitle = value
            }

        var size = Vector2(0.0, 0.0)
        var scale = Vector2(1.0, 1.0)

        var presentationMode: PresentationMode
        get() = application.presentationMode
        set(value) {
            application.presentationMode = value
        }


        fun requestDraw() = application.requestDraw()

        /**
         * Window focused event, triggered when the window receives focus
         */
        val focused = Event<WindowEvent>().postpone(true)

        /**
         * Window focused event, triggered when the window loses focus
         */
        val unfocused = Event<WindowEvent>().postpone(true)

        /**
         * Window moved event
         */
        val moved = Event<WindowEvent>().postpone(true)
        val sized = Event<WindowEvent>().postpone(true)


        /**
         * Drop event, triggered when a file is dropped on the window
         */
        val drop = Event<DropEvent>().postpone(true)

        /**
         * Window position
         */
        var position:Vector2
            get() = application.windowPosition
        set(value) {
            application.windowPosition = value
        }
    }

    val window = Window()

    /**
     * This is ran exactly once before the first call to draw()
     */
    open fun setup() {}

    /**
    * This is the draw call that is called by Application. It takes care of handling extensions.
    */
    fun drawImpl() {
        backgroundColor?.let {
            drawer.background(it)
        }
        extensions.filter { it.enabled }.forEach { it.beforeDraw(drawer, this) }
        draw()
        extensions.reversed().filter { it.enabled }.forEach { it.afterDraw(drawer, this) }
    }
    /**
    * This is the user facing draw call. It should be overridden by the user.
    */
    open fun draw() {}
}

val programKeyboardEvents = mutableMapOf<Program, Keyboard>()
val programMouseEvents = mutableMapOf<Program, Mouse>()

val Program.keyboard:Keyboard get() = programKeyboardEvents.getOrPut(this) { Keyboard()}
val Program.mouse:Mouse get() = programMouseEvents.getOrPut(this) { Mouse() }
