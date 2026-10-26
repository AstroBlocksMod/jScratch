package org.jscratch.runtime

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.font.FontRenderContext
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D

/**
 * One visible monitor as it looked at the end of a frame, laid out in stage pixels with the origin at the
 * top left. Every text width is measured during layout, so drawing measures nothing. The window hit-tests
 * these for sliders and list scrolling, so the geometry helpers live here.
 */
class MonitorItem(
    val index: Int,
    val mode: Monitor.Mode,
    val color: Int,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val label: String,
    val labelWidth: Double,
    /** The value's lines, with their widths in the monitor's value font, and the width of the value box. */
    val lines: List<String>,
    val lineWidths: DoubleArray,
    val valueWidth: Double,
    /** List monitors: the length, then the rows in view from [firstRow], already cut to fit their boxes. */
    val itemCount: Int,
    val firstRow: Int,
    val rows: List<String>,
    val rowIndexWidths: DoubleArray,
    val lengthWidth: Double,
    val scroll: Double,
    val number: Double,
    val sliderMin: Double,
    val sliderMax: Double,
    val isDiscrete: Boolean,
) {
    fun contains(px: Double, py: Double): Boolean = px >= x && px < x + width && py >= y && py < y + height

    /** Height of a list monitor's scrolling rows, between the header and the footer. */
    val bodyHeight: Double get() = MonitorView.bodyHeight(height)

    val maxScroll: Double get() = MonitorView.maxScroll(itemCount, height)

    /** Top of a slider monitor's second row, which holds the range input. */
    val sliderTop: Double get() = y + 1 + MonitorView.PAD + MonitorView.LINE * lines.size

    /** Left and right ends of the slider thumb's travel. */
    val sliderLeft: Double get() = x + 1 + MonitorView.PAD + MonitorView.SLIDER_MARGIN + MonitorView.THUMB / 2
    val sliderRight: Double get() = x + width - 1 - MonitorView.PAD - MonitorView.SLIDER_MARGIN - MonitorView.THUMB / 2

    /** Whether ([px], [py]) is on the range input. As in a browser, the label and value above it ignore the mouse. */
    fun onSlider(px: Double, py: Double): Boolean {
        if (mode != Monitor.Mode.SLIDER) return false
        val top = sliderTop + MonitorView.SLIDER_MARGIN
        val bottom = sliderTop + MonitorView.SLIDER_ROW - MonitorView.SLIDER_MARGIN
        return px >= sliderLeft - MonitorView.THUMB / 2 && px <= sliderRight + MonitorView.THUMB / 2 &&
            py >= top && py < bottom
    }

    /** The value a slider takes when dragged to [px], snapped to its step like an HTML range input. */
    fun sliderValueAt(px: Double): Double {
        val span = sliderRight - sliderLeft
        val frac = if (span <= 0) 0.0 else ((px - sliderLeft) / span).coerceIn(0.0, 1.0)
        if (sliderMax <= sliderMin) return sliderMin
        val step = if (isDiscrete) 1.0 else 0.01
        val steps = Math.floor((sliderMax - sliderMin) / step + 1e-9)
        val n = Math.round(frac * (sliderMax - sliderMin) / step).toDouble().coerceIn(0.0, steps)
        val v = sliderMin + n * step
        return if (isDiscrete) v else Math.round(v * 100) / 100.0
    }
}

/**
 * Lays out and draws monitors the way scratch-gui does: its CSS sizes and colors, and its rule for placing
 * a monitor that was never placed (`getInitialPosition` in `monitor-layout.js`).
 */
object MonitorView {
    /** Container padding of default and slider monitors. */
    const val PAD = 3.0

    /** Line height of the 12 px monitor font. */
    const val LINE = 14.0
    private const val LARGE_LINE = 18.0
    const val ROW = 24.0
    private const val HEADER = 21.0
    private const val FOOTER = 20.0
    const val SLIDER_ROW = 20.0
    const val SLIDER_MARGIN = 2.0
    const val THUMB = 16.0

    /** Narrowest slider, which is the browser's default width for a range input plus its margins. */
    private const val SLIDER_MIN_WIDTH = 129.0 + 2 * SLIDER_MARGIN

    /** Longest line measured and drawn. Anything past it would be far off the stage anyway. */
    private const val MAX_LINE = 500

    /** Most lines of a value laid out: more than fit on the 360 px stage at the 14 px line height. */
    private const val MAX_LINES = 30

    private const val PLACE_PADDING = 5.0
    private const val PLACE_WIDTH = 400.0
    private const val PLACE_HEIGHT = 300.0
    private const val PLACE_EDGE = 40.0

    private val BACKGROUND = Color(0xE5, 0xF0, 0xFF)
    private val BORDER = Color(0, 0, 0, 38)
    private val TEXT = Color(0x57, 0x5E, 0x75)
    private val SLIDER_FILL = Color(0x00, 0x75, 0xFF)
    private val SLIDER_TRACK = Color(0xEF, 0xEF, 0xEF)
    private val SLIDER_EDGE = Color(0xB2, 0xB2, 0xB2)
    private const val EMPTY = "(empty)"

    private val frc = FontRenderContext(null, true, true)
    private val family: String by lazy {
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toHashSet()
        listOf("Helvetica Neue", "Helvetica", "Arial").firstOrNull { it in available } ?: Font.SANS_SERIF
    }
    private val plain: Font by lazy { Font(family, Font.PLAIN, 12) }
    private val bold: Font by lazy { Font(family, Font.BOLD, 12) }
    private val large: Font by lazy { Font(family, Font.PLAIN, 16) }
    private val emptyWidth: Double by lazy { width(EMPTY, plain) }
    private val equalsWidth: Double by lazy { width("=", bold) }

    // ---- layout, on the scheduler thread --------------------------------------------------------

    private class Content(
        val lines: List<String>,
        val lineWidths: DoubleArray,
        val valueWidth: Double,
        val list: ScratchList?,
        val number: Double,
        val w: Double,
        val h: Double,
    )

    fun bodyHeight(outerHeight: Double): Double = Math.max(0.0, outerHeight - 2 - HEADER - FOOTER)

    fun maxScroll(itemCount: Int, outerHeight: Double): Double =
        Math.max(0.0, itemCount * ROW - bodyHeight(outerHeight))

    /**
     * Measures every visible monitor and places the ones that have no position yet. Placement avoids only
     * the monitors on the stage now, as scratch-gui does: it removes a monitor's rectangle from its layout
     * when the monitor is hidden, so a new monitor can take a hidden one's spot.
     */
    fun layout(rt: Runtime): List<MonitorItem> {
        val shown = ArrayList<Pair<Int, Content>>()
        for ((i, m) in rt.monitors.withIndex()) {
            if (m.visible) shown.add(i to measure(m, m.value(rt)))
        }
        val taken = ArrayList<Rectangle2D.Double>()
        for ((i, c) in shown) {
            val m = rt.monitors[i]
            if (m.isPlaced) taken.add(Rectangle2D.Double(m.x, m.y, c.w, c.h))
        }
        for ((i, c) in shown) {
            val m = rt.monitors[i]
            if (m.isPlaced) continue
            val r = initialPosition(taken, c.w, c.h)
            m.x = r.x
            m.y = r.y
            taken.add(r)
        }
        return shown.map { (i, c) -> item(i, rt.monitors[i], c) }
    }

    /** Builds the snapshot item. A list monitor copies only the rows in view, since the list keeps changing. */
    private fun item(i: Int, m: Monitor, c: Content): MonitorItem {
        val count = c.list?.items?.size ?: 0
        m.scroll = m.scroll.coerceIn(0.0, maxScroll(count, c.h))
        var first = 0
        var rows = emptyList<String>()
        var indexWidths = DoubleArray(0)
        if (c.list != null && count > 0) {
            first = (m.scroll / ROW).toInt()
            val last = Math.min(count - 1, ((m.scroll + bodyHeight(c.h)) / ROW).toInt())
            indexWidths = DoubleArray(last - first + 1) { width((first + it + 1).toString(), bold) }
            rows = (first..last).mapIndexed { r, row ->
                val text = Cast.toString(c.list.items[row]).substringBefore('\n').take(MAX_LINE)
                ellipsize(text, plain, rowText(c.w, indexWidths[r]))
            }
        }
        return MonitorItem(
            i, m.mode, color(m.opcode), m.x, m.y, c.w, c.h, m.label, width(m.label, bold), c.lines, c.lineWidths,
            c.valueWidth, count, first, rows, indexWidths, width("length $count", bold), m.scroll, c.number,
            m.sliderMin, m.sliderMax, m.isDiscrete,
        )
    }

    private fun measure(m: Monitor, value: Any?): Content {
        if (m.mode == Monitor.Mode.LIST) {
            val w = if (m.width > 0) m.width else 100.0
            val h = if (m.height > 0) m.height else 200.0
            return Content(emptyList(), DoubleArray(0), 0.0, value as? ScratchList, 0.0, w + 2, h + 2)
        }
        val text = if (value is ScratchList) value.contents() else Monitor.format(value)
        val lines = text.splitToSequence('\n').take(MAX_LINES).map { it.take(MAX_LINE) }.toList()
        val number = Cast.toNumber(value)
        if (m.mode == Monitor.Mode.LARGE) {
            val widths = DoubleArray(lines.size) { width(lines[it], large) }
            val valueW = Math.max(48.0, widths.max() + 8)
            val h = 2 + Math.max(22.0, LARGE_LINE * lines.size + 3)
            return Content(lines, widths, valueW, null, number, Math.ceil(2 + valueW), Math.ceil(h))
        }
        val widths = DoubleArray(lines.size) { width(lines[it], plain) }
        val valueW = Math.max(40.0, widths.max() + 4)
        var w = 2 + 2 * PAD + 10 + width(m.label, bold) + 10 + valueW
        var h = 2 + 2 * PAD + LINE * lines.size
        if (m.mode == Monitor.Mode.SLIDER) {
            w = Math.max(w, 2 + 2 * PAD + SLIDER_MIN_WIDTH)
            h += SLIDER_ROW
        }
        return Content(lines, widths, valueW, null, number, Math.ceil(w), Math.ceil(h))
    }

    /** Where a list row's value box starts, from the left edge inside the border. */
    private fun rowBoxLeft(indexWidth: Double): Double = 2 + 3 + indexWidth + 3 + 3

    /** Room for the text of a list row in a monitor [outerWidth] wide: the box minus its border and padding. */
    private fun rowText(outerWidth: Double, indexWidth: Double): Double =
        outerWidth - 2 - 2 - 3 - rowBoxLeft(indexWidth) - 2 - 10

    /**
     * scratch-gui's placement for a monitor shown for the first time: try the corners formed by the right
     * and bottom edges of the monitors already on the stage, rounded to 50 px columns, and take the first
     * spot that overlaps nothing. It deliberately plans for a 400 by 300 stage, as scratch-gui does.
     */
    fun initialPosition(taken: List<Rectangle2D.Double>, w: Double, h: Double): Rectangle2D.Double {
        val endXs = (listOf(0.0) + taken.map { Math.ceil((it.x + it.width) / 50) * 50 }).sorted()
        val endYs = (listOf(0.0) + taken.map { Math.ceil(it.y + it.height) }).sorted()
        var planB: Rectangle2D.Double? = null
        var lastX: Double? = null
        var lastY: Double? = null
        for (x in endXs) {
            if (x == lastX) continue
            lastX = x
            for (y in endYs) {
                // scratch-gui never resets this between columns, so a repeated y is skipped even in a new column.
                if (y == lastY) continue
                lastY = y
                val padded = Rectangle2D.Double(x, y, w + 2 * PLACE_PADDING, h + 2 * PLACE_PADDING)
                if (taken.any { intersects(it, padded) }) continue
                val spot = Rectangle2D.Double(x + PLACE_PADDING, y + PLACE_PADDING, w, h)
                if (padded.x + padded.width > PLACE_WIDTH || padded.y + padded.height > PLACE_HEIGHT) {
                    val nearEdge = padded.x + PLACE_EDGE > PLACE_WIDTH || padded.y + PLACE_EDGE > PLACE_HEIGHT
                    if (planB == null && !nearEdge) planB = spot
                    continue
                }
                return spot
            }
        }
        planB?.let { return it }
        val rx = Math.ceil(Math.random() * (PLACE_WIDTH / 2))
        val ry = Math.ceil(Math.random() * (PLACE_HEIGHT - PLACE_EDGE))
        return Rectangle2D.Double(rx, ry, w, h)
    }

    private fun intersects(a: Rectangle2D.Double, b: Rectangle2D.Double): Boolean =
        !(a.x >= b.x + b.width || b.x >= a.x + a.width || a.y >= b.y + b.height || b.y >= a.y + a.height)

    /** Block category colors from scratch-blocks, as scratch-gui picks them by opcode. */
    private fun color(opcode: String): Int = when {
        opcode == "data_listcontents" -> 0xFF661A
        opcode.startsWith("data_") -> 0xFF8C1A
        opcode.startsWith("motion_") -> 0x4C97FF
        opcode.startsWith("looks_") -> 0x9966FF
        opcode.startsWith("sound_") -> 0xCF63CF
        opcode.startsWith("sensing_") -> 0x5CB1D6
        else -> 0x0FBD8C
    }

    private fun width(s: String, font: Font): Double = if (s.isEmpty()) 0.0 else font.getStringBounds(s, frc).width

    /** Cuts [s] to fit [maxWidth] with a trailing ellipsis, finding the longest prefix that fits by bisection. */
    private fun ellipsize(s: String, font: Font, maxWidth: Double): String {
        if (width(s, font) <= maxWidth) return s
        val dots = "…"
        var lo = 0
        var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (width(s.substring(0, mid) + dots, font) <= maxWidth) lo = mid else hi = mid - 1
        }
        return s.substring(0, lo) + dots
    }

    // ---- drawing, from the snapshot alone -------------------------------------------------------

    fun draw(g: Graphics2D, m: MonitorItem) {
        val old = g.clip
        val outer = RoundRectangle2D.Double(m.x, m.y, m.width, m.height, 8.0, 8.0)
        g.color = BORDER
        g.fill(outer)
        val inner = RoundRectangle2D.Double(m.x + 1, m.y + 1, m.width - 2, m.height - 2, 6.0, 6.0)
        g.color = BACKGROUND
        g.fill(inner)
        g.clip(inner)
        when (m.mode) {
            Monitor.Mode.LIST -> drawList(g, m)
            Monitor.Mode.LARGE -> {
                g.color = Color(m.color)
                g.fill(inner)
                g.color = Color.WHITE
                val top = m.y + (m.height - LARGE_LINE * m.lines.size) / 2
                for ((i, line) in m.lines.withIndex()) {
                    text(g, line, large, m.x + (m.width - m.lineWidths[i]) / 2, top + i * LARGE_LINE, LARGE_LINE)
                }
            }
            else -> drawDefault(g, m)
        }
        g.clip = old
    }

    private fun drawDefault(g: Graphics2D, m: MonitorItem) {
        val left = m.x + 1 + PAD
        val top = m.y + 1 + PAD
        g.color = TEXT
        text(g, m.label, bold, left + 5, top, LINE)
        val vx = left + 5 + m.labelWidth + 10
        g.color = Color(m.color)
        g.fill(RoundRectangle2D.Double(vx, top, m.valueWidth, LINE * m.lines.size, 8.0, 8.0))
        g.color = Color.WHITE
        for ((i, line) in m.lines.withIndex()) {
            text(g, line, plain, vx + (m.valueWidth - m.lineWidths[i]) / 2, top + i * LINE, LINE)
        }
        if (m.mode == Monitor.Mode.SLIDER) drawSlider(g, m)
    }

    private fun drawSlider(g: Graphics2D, m: MonitorItem) {
        val cy = m.sliderTop + SLIDER_ROW / 2
        val left = m.sliderLeft - THUMB / 2
        val right = m.sliderRight + THUMB / 2
        val span = m.sliderMax - m.sliderMin
        val frac = if (span <= 0 || m.number.isNaN()) 0.0 else ((m.number - m.sliderMin) / span).coerceIn(0.0, 1.0)
        val thumbX = m.sliderLeft + frac * (m.sliderRight - m.sliderLeft)
        val track = RoundRectangle2D.Double(left, cy - 2, right - left, 4.0, 4.0, 4.0)
        g.color = SLIDER_TRACK
        g.fill(track)
        g.color = SLIDER_EDGE
        g.draw(track)
        g.color = SLIDER_FILL
        g.fill(RoundRectangle2D.Double(left, cy - 2, thumbX - left, 4.0, 4.0, 4.0))
        g.fill(Ellipse2D.Double(thumbX - THUMB / 2, cy - THUMB / 2, THUMB, THUMB))
    }

    private fun drawList(g: Graphics2D, m: MonitorItem) {
        val left = m.x + 1
        val innerW = m.width - 2
        val headerTop = m.y + 1
        g.color = Color.WHITE
        g.fill(Rectangle2D.Double(left, headerTop, innerW, HEADER))
        g.color = BORDER
        g.fill(Rectangle2D.Double(left, headerTop + HEADER - 1, innerW, 1.0))
        g.color = TEXT
        text(g, m.label, bold, left + (innerW - m.labelWidth) / 2, headerTop + 3, LINE)

        val bodyTop = headerTop + HEADER
        val old = g.clip
        g.clip(Rectangle2D.Double(left, bodyTop, innerW, m.bodyHeight))
        if (m.itemCount == 0) {
            g.color = TEXT
            text(g, EMPTY, plain, left + (innerW - emptyWidth) / 2, bodyTop + 5, LINE)
        } else {
            val color = Color(m.color)
            for ((r, value) in m.rows.withIndex()) {
                val i = m.firstRow + r
                val rowTop = bodyTop + i * ROW - m.scroll
                g.color = TEXT
                text(g, (i + 1).toString(), bold, left + 2 + 3, rowTop, ROW)
                val bx = left + rowBoxLeft(m.rowIndexWidths[r])
                val bw = left + innerW - 2 - 3 - bx
                g.color = BORDER
                g.fill(RoundRectangle2D.Double(bx, rowTop + 1, bw, 22.0, 8.0, 8.0))
                g.color = color
                g.fill(RoundRectangle2D.Double(bx + 1, rowTop + 2, bw - 2, 20.0, 6.0, 6.0))
                g.color = Color.WHITE
                text(g, value, plain, bx + 6, rowTop + 1, 22.0)
            }
        }
        g.clip = old

        val footerTop = bodyTop + m.bodyHeight
        g.color = Color.WHITE
        g.fill(Rectangle2D.Double(left, footerTop, innerW, FOOTER))
        g.color = TEXT
        text(g, "+", bold, left + 3, footerTop + 3, LINE)
        text(g, "length ${m.itemCount}", bold, left + (innerW - m.lengthWidth) / 2, footerTop + 3, LINE)
        text(g, "=", bold, left + innerW - 3 - equalsWidth, footerTop + 3, LINE)
    }

    /** Draws [s] starting at [x], centered vertically in a line box of [lineHeight] at [top]. */
    private fun text(g: Graphics2D, s: String, font: Font, x: Double, top: Double, lineHeight: Double) {
        if (s.isEmpty()) return
        val lm = font.getLineMetrics(s, frc)
        val baseline = top + (lineHeight - lm.ascent - lm.descent) / 2 + lm.ascent
        g.font = font
        g.drawString(s, x.toFloat(), baseline.toFloat())
    }
}
