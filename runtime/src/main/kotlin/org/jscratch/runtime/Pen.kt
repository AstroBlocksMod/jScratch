package org.jscratch.runtime

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Line2D
import java.awt.image.BufferedImage

/** Pen state of one sprite. Colors are kept as Scratch does: hue, saturation, brightness in 0..100. */
class PenState {
    var down = false
    var hue = 66.66
    var saturation = 100.0
    var brightness = 100.0
    var transparency = 0.0
    var size = 1.0

    fun color(): Color {
        val base =
            Color.getHSBColor(
                (hue / 100.0).toFloat(),
                (saturation / 100.0).toFloat(),
                (brightness / 100.0).toFloat(),
            )
        val alpha = Math.round(255 * (1 - transparency / 100.0)).toInt().coerceIn(0, 255)
        return Color(base.red, base.green, base.blue, alpha)
    }

    fun setArgb(argb: Int) {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val a = (argb shr 24) and 0xFF
        val hsb = Color.RGBtoHSB(r, g, b, null)
        hue = hsb[0] * 100.0
        saturation = hsb[1] * 100.0
        brightness = hsb[2] * 100.0
        transparency = if (a == 0) 0.0 else 100.0 * (1 - a / 255.0)
    }

    fun setParam(param: String, value: Double) {
        when (param) {
            "color" -> hue = wrapColor(value)
            "saturation" -> saturation = value.coerceIn(0.0, 100.0)
            "brightness" -> brightness = value.coerceIn(0.0, 100.0)
            "transparency" -> transparency = value.coerceIn(0.0, 100.0)
            else -> Log.warn("Unknown pen color parameter '$param'")
        }
    }

    fun changeParam(param: String, delta: Double) {
        when (param) {
            "color" -> setParam(param, hue + delta)
            "saturation" -> setParam(param, saturation + delta)
            "brightness" -> setParam(param, brightness + delta)
            "transparency" -> setParam(param, transparency + delta)
            else -> Log.warn("Unknown pen color parameter '$param'")
        }
    }

    private fun wrapColor(v: Double): Double {
        val m = v % 100.0
        return if (m < 0) m + 100.0 else m
    }

    fun copyFrom(o: PenState) {
        down = o.down
        hue = o.hue
        saturation = o.saturation
        brightness = o.brightness
        transparency = o.transparency
        size = o.size
    }
}

/** The pen layer: a bitmap between the backdrop and the sprites. */
class PenLayer(val scale: Int = 2) {
    val image: BufferedImage =
        BufferedImage(Runtime.STAGE_WIDTH * scale, Runtime.STAGE_HEIGHT * scale, BufferedImage.TYPE_INT_ARGB)

    @Volatile var dirty = false

    fun clear() {
        val g = image.createGraphics()
        g.composite = AlphaComposite.Clear
        g.fillRect(0, 0, image.width, image.height)
        g.dispose()
        dirty = true
    }

    fun line(x0: Double, y0: Double, x1: Double, y1: Double, pen: PenState) {
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = pen.color()
        g.stroke = BasicStroke((pen.size * scale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(
            Line2D.Double(
                (Runtime.STAGE_WIDTH / 2.0 + x0) * scale,
                (Runtime.STAGE_HEIGHT / 2.0 - y0) * scale,
                (Runtime.STAGE_WIDTH / 2.0 + x1) * scale,
                (Runtime.STAGE_HEIGHT / 2.0 - y1) * scale,
            ),
        )
        g.dispose()
        dirty = true
    }

    fun stamp(image: BufferedImage, transform: AffineTransform, alpha: Float) {
        val g = this.image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.scale(scale.toDouble(), scale.toDouble())
        if (alpha < 1f) g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
        g.drawImage(image, transform, null)
        g.dispose()
        dirty = true
    }
}
