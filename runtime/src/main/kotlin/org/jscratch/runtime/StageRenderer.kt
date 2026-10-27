package org.jscratch.runtime

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/** One sprite as it looked at the end of a frame. */
class DrawItem(
    val image: BufferedImage,
    val transform: AffineTransform,
    val alpha: Float,
    val sayText: String,
    val sayIsThink: Boolean,
    val bounds: Bounds?,
)

class FrameSnapshot(val backdrop: DrawItem?, val items: List<DrawItem>, val monitors: List<MonitorItem>)

/** Draws a [FrameSnapshot] with Java2D. Shared by the window and by headless screenshots. */
object StageRenderer {
    fun snapshot(rt: Runtime): FrameSnapshot {
        val items = ArrayList<DrawItem>()
        for (s in rt.sprites) {
            if (!s.visible) continue
            val img = s.drawnImage() ?: continue
            val at = s.renderTransform() ?: continue
            // Bounds only place the say bubble. A costume with no opaque pixels anchors it at the sprite.
            val bounds = if (s.sayText.isEmpty()) null else s.bounds() ?: Bounds(s.x, s.x, s.y, s.y)
            items.add(DrawItem(img, at, (1.0 - s.ghost / 100.0).toFloat(), s.sayText, s.sayIsThink, bounds))
        }

        val stage = rt.stage
        val backdrop =
            stage.drawnImage()?.let { img ->
                stage.renderTransform()?.let { at -> DrawItem(img, at, 1f, "", false, null) }
            }
        return FrameSnapshot(backdrop, items, MonitorView.layout(rt))
    }

    /**
     * Renders the stage into [g], whose transform must already map stage pixels (480 x 360) to the device.
     * [pen] is the pen layer's image or a copy of it, at any multiple of the stage size.
     */
    fun render(g: Graphics2D, snap: FrameSnapshot?, pen: BufferedImage) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.clipRect(0, 0, Runtime.STAGE_WIDTH, Runtime.STAGE_HEIGHT)
        g.color = Color.WHITE
        g.fillRect(0, 0, Runtime.STAGE_WIDTH, Runtime.STAGE_HEIGHT)
        snap?.backdrop?.let { g.drawImage(it.image, it.transform, null) }
        val penScale = Runtime.STAGE_WIDTH.toDouble() / pen.width
        g.drawImage(pen, AffineTransform.getScaleInstance(penScale, penScale), null)
        if (snap == null) return
        for (item in snap.items) {
            if (item.alpha <= 0f) continue
            val oldComposite = g.composite
            if (item.alpha < 1f) g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, item.alpha)
            g.drawImage(item.image, item.transform, null)
            g.composite = oldComposite
        }
        for (item in snap.items) {
            if (item.sayText.isNotEmpty()) drawBubble(g, item)
        }
        // Monitors sit in a layer above the stage canvas in scratch-gui, so above sprites and bubbles too.
        for (m in snap.monitors) MonitorView.draw(g, m)
    }

    /** Renders a frame to an image at [scale] times the stage size. */
    fun toImage(snap: FrameSnapshot?, pen: BufferedImage, scale: Int): BufferedImage {
        val img = BufferedImage(Runtime.STAGE_WIDTH * scale, Runtime.STAGE_HEIGHT * scale, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.scale(scale.toDouble(), scale.toDouble())
        render(g, snap, pen)
        g.dispose()
        return img
    }

    private fun drawBubble(g: Graphics2D, item: DrawItem) {
        val b = item.bounds ?: return
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 14)
        val fm = g.fontMetrics
        val lines = wrap(item.sayText, fm, 170)
        val textW = lines.maxOf { fm.stringWidth(it) }
        val w = textW + 20.0
        val h = lines.size * fm.height + 12.0
        var bx = Runtime.STAGE_WIDTH / 2.0 + b.right - 10
        var by = Runtime.STAGE_HEIGHT / 2.0 - b.top - h - 8
        if (bx + w > Runtime.STAGE_WIDTH) bx = Runtime.STAGE_WIDTH - w
        if (bx < 0) bx = 0.0
        if (by < 0) by = 0.0
        val rect = RoundRectangle2D.Double(bx, by, w, h, 16.0, 16.0)
        g.color = Color.WHITE
        g.fill(rect)
        g.color = Color(0x80, 0x80, 0x80)
        g.stroke = BasicStroke(2f)
        g.draw(rect)
        if (item.sayIsThink) {
            val dot = Ellipse2D.Double(bx + 10, by + h + 1, 10.0, 8.0)
            g.color = Color.WHITE
            g.fill(dot)
            g.color = Color(0x80, 0x80, 0x80)
            g.draw(dot)
        } else {
            val tail = Path2D.Double()
            tail.moveTo(bx + 12, by + h - 1)
            tail.lineTo(bx + 6, by + h + 10)
            tail.lineTo(bx + 26, by + h - 1)
            g.color = Color.WHITE
            g.fill(tail)
            g.color = Color(0x80, 0x80, 0x80)
            g.draw(tail)
        }
        g.color = Color(0x57, 0x5E, 0x75)
        var ty = by + 6 + fm.ascent
        for (line in lines) {
            g.drawString(line, (bx + 10).toFloat(), ty.toFloat())
            ty += fm.height
        }
    }

    private fun wrap(text: String, fm: FontMetrics, maxWidth: Int): List<String> {
        val out = ArrayList<String>()
        for (paragraph in text.split('\n')) {
            var line = StringBuilder()
            for (word in paragraph.split(' ')) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (fm.stringWidth(candidate) <= maxWidth || line.isEmpty()) {
                    line = StringBuilder(candidate)
                } else {
                    out.add(line.toString())
                    line = StringBuilder(word)
                }
            }
            out.add(line.toString())
        }
        return out
    }
}
