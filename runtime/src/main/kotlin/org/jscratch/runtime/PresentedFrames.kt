package org.jscratch.runtime

import java.awt.AlphaComposite
import java.awt.image.BufferedImage

/**
 * The frames the window paints: the last snapshot and a copy of the pen layer as it was when the frame
 * ended. Swing paints whenever it gets to it, by which time the scripts are drawing the next frame into
 * the live layer, often right after `erase all`. 3D engines draw their whole scene with the pen, so
 * painting the live layer showed a blank or half drawn scene.
 *
 * There are three copies: the one on show, the one being painted, which may be an older one when a
 * paint takes longer than a frame, and a free one to copy into. The lock only guards which is which, so
 * the scheduler never waits for a paint to finish and the event thread never waits for a copy.
 */
internal class PresentedFrames(private val pen: PenLayer) {
    private class Shown(val snapshot: FrameSnapshot?, val pen: BufferedImage)

    private val copies = Array(3) { BufferedImage(pen.image.width, pen.image.height, BufferedImage.TYPE_INT_ARGB) }
    private val lock = Any()

    @Volatile private var shown = Shown(null, copies[0])
    private var painting: BufferedImage? = null

    /** The latest snapshot, for hit-testing monitors from the event thread. */
    val snapshot: FrameSnapshot? get() = shown.snapshot

    /** Called by the scheduler between frames. Copies the pen layer only when a script changed it. */
    fun present(snapshot: FrameSnapshot?) {
        var copy = shown.pen
        if (pen.dirty) {
            pen.dirty = false
            copy = synchronized(lock) { copies.first { it !== shown.pen && it !== painting } }
            val g = copy.createGraphics()
            g.composite = AlphaComposite.Src
            g.drawImage(pen.image, 0, 0, null)
            g.dispose()
        }
        synchronized(lock) { shown = Shown(snapshot, copy) }
    }

    /** Called on the event thread to paint the frame on show. Its pen copy is left alone until [paint] returns. */
    fun <R> paint(block: (FrameSnapshot?, BufferedImage) -> R): R {
        val frame = synchronized(lock) { shown.also { painting = it.pen } }
        try {
            return block(frame.snapshot, frame.pen)
        } finally {
            synchronized(lock) { painting = null }
        }
    }
}
