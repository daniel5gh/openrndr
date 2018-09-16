package org.openrndr.extensions

import org.openrndr.Extension
import org.openrndr.Mouse
import org.openrndr.Program
import org.openrndr.draw.Drawer
import org.openrndr.math.Matrix44
import org.openrndr.math.transforms.transform
import org.openrndr.mouse

private class Camera2D {
    var view = Matrix44.IDENTITY
    fun mouseDragged(event: Mouse.MouseEvent) {
        view *= transform { translate(event.dragDisplacement / view[0].x) }
    }

    fun mouseScrolled(event: Mouse.MouseEvent) {
        view *= transform {
            translate(event.position)
            scale(1.0 + event.rotation.y * 0.01)
            translate(event.position * -1.0)
        }
    }
}

class Debug2D : Extension {

    override var enabled: Boolean = true

    private val camera = Camera2D()
    override fun setup(program: Program) {
        program.mouse.dragged.listen {
            if (!it.propagationCancelled) {
                camera.mouseDragged(it)
            }
        }

        program.mouse.scrolled.listen {
            if (!it.propagationCancelled) {
                camera.mouseScrolled(it)
            }
        }
    }
    override fun beforeDraw(drawer: Drawer, program: Program) {
        drawer.view = camera.view
    }
}