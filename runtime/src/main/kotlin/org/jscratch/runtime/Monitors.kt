package org.jscratch.runtime

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A variable, list or reporter monitor on the stage. This is the state only: [MonitorView] measures it,
 * places it and draws it from the frame snapshot.
 *
 * [x] and [y] are the top-left corner in stage pixels, NaN until the monitor is first shown and placed.
 * [slot] picks the variable or list among the [target]'s generated fields; built-in reporters ignore it.
 */
class Monitor(
    val opcode: String,
    val mode: Mode,
    val spriteName: String,
    val slot: Int,
    val param: String,
    val label: String,
    x: Double,
    y: Double,
    val width: Double,
    val height: Double,
    visible: Boolean,
    val sliderMin: Double,
    val sliderMax: Double,
    val isDiscrete: Boolean,
) {
    enum class Mode { DEFAULT, LARGE, SLIDER, LIST }

    @Volatile var x: Double = x

    @Volatile var y: Double = y

    @Volatile var visible: Boolean = visible

    /** How far a list monitor's rows are scrolled, in pixels. The window changes it on the mouse wheel. */
    @Volatile var scroll: Double = 0.0

    lateinit var target: Target

    val isPlaced: Boolean get() = !x.isNaN() && !y.isNaN()

    /** The variable's value, the [ScratchList], or what the reporter reports. Called on the scheduler thread. */
    fun value(rt: Runtime): Any? {
        val t = target
        return when (opcode) {
            "data_variable", "data_listcontents" -> t.monitorSlot(slot)
            "motion_xposition" -> t.xPosition()
            "motion_yposition" -> t.yPosition()
            "motion_direction" -> t.getDirection()
            "looks_size" -> t.getSize()
            "looks_costumenumbername" -> if (param == "number") t.costumeNumber() else t.costumeName()
            "looks_backdropnumbername", "looks_backdropname" ->
                if (param == "number") t.backdropNumber() else t.backdropName()
            "sound_volume" -> t.getVolume()
            "sensing_answer" -> rt.answer
            "sensing_timer" -> rt.timer()
            "sensing_current" -> t.current(param)
            "sensing_loudness" -> -1.0
            else -> ""
        }
    }

    companion object {
        /** A value as scratch-gui shows it: numbers rounded to six decimals the way `toFixed(6)` does. */
        fun format(value: Any?): String {
            if (value !is Double || value.isNaN() || value.isInfinite()) return Cast.toString(value)
            return Cast.toString(BigDecimal(value).setScale(6, RoundingMode.HALF_UP).toDouble())
        }
    }
}

/**
 * Decodes the monitor table the compiler stores at [RESOURCE]: one monitor per line, tab-separated fields
 * with `\\`, `\t`, `\n` and `\r` escapes. See `Monitors.encode` in the compiler for the field order.
 */
internal object MonitorData {
    const val RESOURCE = "data/monitors.txt"

    fun load(): List<Monitor> {
        val stream = MonitorData::class.java.classLoader.getResourceAsStream(RESOURCE) ?: return emptyList()
        return decode(stream.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    fun decode(text: String): List<Monitor> {
        val out = ArrayList<Monitor>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val f = line.split('\t').map { unescape(it) }
            if (f.size < 14) continue
            val mode = when (f[1]) {
                "large" -> Monitor.Mode.LARGE
                "slider" -> Monitor.Mode.SLIDER
                "list" -> Monitor.Mode.LIST
                else -> Monitor.Mode.DEFAULT
            }
            out.add(
                Monitor(
                    opcode = f[0],
                    mode = mode,
                    spriteName = f[2],
                    slot = f[3].toIntOrNull() ?: -1,
                    param = f[4],
                    label = f[5],
                    x = number(f[6], Double.NaN),
                    y = number(f[7], Double.NaN),
                    width = number(f[8], 0.0),
                    height = number(f[9], 0.0),
                    visible = f[10] == "1",
                    sliderMin = number(f[11], 0.0),
                    sliderMax = number(f[12], 100.0),
                    isDiscrete = f[13] == "1",
                ),
            )
        }
        return out
    }

    private fun number(s: String, fallback: Double): Double = s.toDoubleOrNull() ?: fallback

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
