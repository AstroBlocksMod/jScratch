package org.jscratch.runtime

import java.awt.geom.Rectangle2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonitorsTest {
    @Test
    fun valuesShowLikeScratchGui() {
        assertEquals("0.3", Monitor.format(0.1 + 0.2))
        assertEquals("0.333333", Monitor.format(1.0 / 3))
        assertEquals("-0.666667", Monitor.format(-2.0 / 3))
        assertEquals("0", Monitor.format(1e-7))
        assertEquals("42", Monitor.format(42.0))
        assertEquals("1e+21", Monitor.format(1e21))
        assertEquals("Infinity", Monitor.format(Double.POSITIVE_INFINITY))
        // Strings that look like numbers are shown as typed, and booleans as words.
        assertEquals("0.1234567", Monitor.format("0.1234567"))
        assertEquals("true", Monitor.format(true))
    }

    @Test
    fun decodesTheCompilersTable() {
        val text =
            "data_variable\tslider\tCat\t2\t\tCat: a\\tb\t10.0\t\t0.0\t0.0\t1\t-5.0\t5.0\t0\n" +
                "sensing_current\tlarge\t\t-1\tYEAR\tyear\t1.0\t2.0\t0.0\t0.0\t0\t0.0\t100.0\t1\n"
        val (slider, year) = MonitorData.decode(text)
        assertEquals(Monitor.Mode.SLIDER, slider.mode)
        assertEquals("Cat", slider.spriteName)
        assertEquals(2, slider.slot)
        assertEquals("Cat: a\tb", slider.label)
        assertEquals(10.0, slider.x)
        assertFalse(slider.isPlaced, "an empty y means the monitor was never placed")
        assertTrue(slider.visible)
        assertEquals(-5.0, slider.sliderMin)
        assertFalse(slider.isDiscrete)
        assertEquals(Monitor.Mode.LARGE, year.mode)
        assertEquals("", year.spriteName)
        assertEquals("YEAR", year.param)
        assertFalse(year.visible)
    }

    @Test
    fun placesNewMonitorsLikeScratchGui() {
        val first = MonitorView.initialPosition(emptyList(), 80.0, 22.0)
        assertEquals(5.0, first.x)
        assertEquals(5.0, first.y)
        // The next one goes below the first, at the first one's bottom edge plus padding.
        val second = MonitorView.initialPosition(listOf(first), 60.0, 22.0)
        assertEquals(5.0, second.x)
        assertEquals(32.0, second.y)
        // A column that is full moves on to the next one, at the first one's right edge rounded up to 50 px.
        val column = (0 until 9).map { Rectangle2D.Double(5.0, 5.0 + it * 32, 80.0, 22.0) }
        val next = MonitorView.initialPosition(column, 60.0, 22.0)
        assertEquals(105.0, next.x)
        assertEquals(5.0, next.y)
    }

    @Test
    fun slidersSnapToTheirStep() {
        val discrete = slider(isDiscrete = true)
        assertEquals(0.0, discrete.sliderValueAt(discrete.sliderLeft - 50))
        assertEquals(10.0, discrete.sliderValueAt(discrete.sliderRight + 50))
        assertEquals(5.0, discrete.sliderValueAt((discrete.sliderLeft + discrete.sliderRight) / 2))
        val fine = slider(isDiscrete = false)
        val at = fine.sliderLeft + (fine.sliderRight - fine.sliderLeft) * 0.123456
        assertEquals(1.23, fine.sliderValueAt(at))
    }

    @Test
    fun onlyTheSliderRowMovesTheSlider() {
        val m = slider(isDiscrete = true)
        // The label and value row is 14 px below the 1 px border and 3 px padding, so the input starts at 18.
        assertFalse(m.onSlider(10.0, 10.0), "label")
        assertFalse(m.onSlider(m.sliderLeft, m.sliderTop - 1), "just above the input")
        assertTrue(m.onSlider(m.sliderLeft, m.sliderTop + 10), "the bar")
        assertTrue(m.onSlider(m.sliderRight, m.sliderTop + 10), "the far end of the bar")
        assertFalse(m.copy(Monitor.Mode.DEFAULT).onSlider(m.sliderLeft, m.sliderTop + 10), "not a slider")
    }

    @Test
    fun listBodyFillsTheSpaceBetweenHeaderAndFooter() {
        // A list saved as 100 by 200 is 202 px tall with its border; the header takes 21 and the footer 20.
        assertEquals(159.0, MonitorView.bodyHeight(202.0))
        assertEquals(0.0, MonitorView.maxScroll(6, 202.0), "six rows of 24 px fit in 159")
        assertEquals(7 * 24.0 - 159, MonitorView.maxScroll(7, 202.0))
    }

    @Test
    fun linesUnescapeLikeTheCompilerEscapes() {
        assertEquals("a\tb\nc\\d\re", LineText.unescape("a\\tb\\nc\\\\d\\re"))
        assertEquals("plain", LineText.unescape("plain"))
        assertEquals("ends\\", LineText.unescape("ends\\"), "a lone trailing backslash stays")
    }

    private fun slider(isDiscrete: Boolean) = MonitorItem(
        0, Monitor.Mode.SLIDER, 0, 0.0, 0.0, 150.0, 42.0, "a", 10.0, listOf("0"), doubleArrayOf(7.0), 40.0, 0, 0,
        emptyList(), DoubleArray(0), 0.0, 0.0, 0.0, 0.0, 10.0, isDiscrete,
    )

    private fun MonitorItem.copy(mode: Monitor.Mode) = MonitorItem(
        index, mode, color, x, y, width, height, label, labelWidth, lines, lineWidths, valueWidth, itemCount,
        firstRow, rows, rowIndexWidths, lengthWidth, scroll, number, sliderMin, sliderMax, isDiscrete,
    )
}
