package org.jscratch.compiler.codegen

import org.jscratch.compiler.sb3.Monitor
import org.jscratch.compiler.sb3.Project
import org.jscratch.compiler.sb3.Target

/**
 * One monitor as the runtime sees it. [owner] is the target whose value it shows, and [slot] the index of
 * the variable or list among that target's slots (variables first, then lists, in declaration order), or
 * -1 for a built-in reporter. [label] is the text scratch-gui shows, sprite name prefix included.
 */
class MonitorDef(
    val owner: Target,
    val varId: String?,
    val opcode: String,
    val mode: String,
    val slot: Int,
    val param: String,
    val label: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val visible: Boolean,
    val sliderMin: Double,
    val sliderMax: Double,
    val isDiscrete: Boolean,
)

/**
 * Builds the monitor table: every record from `project.json` the runtime can show, then a hidden monitor
 * for each variable and list that has none, so `show variable` works on any of them. The table goes into
 * the JAR as a text resource that the runtime's `MonitorData` decodes.
 */
object Monitors {
    const val RESOURCE = "data/monitors.txt"

    private class Slot(val target: Target, val index: Int, val id: String, val name: String)

    fun build(project: Project, warn: (String) -> Unit): List<MonitorDef> {
        val out = ArrayList<MonitorDef>()
        val monitored = HashSet<Pair<Target, String>>()
        for (m in project.monitors) {
            val def = fromRecord(project, m)
            if (def == null) {
                if (m.visible) warn("Monitor for '${m.opcode}' is not supported yet; it is not shown")
                continue
            }
            if (def.varId != null && !monitored.add(def.owner to def.varId)) continue
            out.add(def)
        }
        for (t in project.targets) {
            for (isList in listOf(false, true)) {
                for (s in slots(t, isList)) {
                    if (!monitored.add(t to s.id)) continue
                    val opcode = if (isList) "data_listcontents" else "data_variable"
                    out.add(
                        MonitorDef(
                            t, s.id, opcode, if (isList) "list" else "default", s.index, "", prefixed(t, s.name),
                            Double.NaN, Double.NaN, 0.0, 0.0, false, 0.0, 100.0, true,
                        ),
                    )
                }
            }
        }
        return out
    }

    private fun fromRecord(project: Project, m: Monitor): MonitorDef? {
        val sprite = m.spriteName?.let { name -> project.sprites.firstOrNull { it.name == name } ?: return null }
        val owner = sprite ?: project.stage
        if (m.opcode == "data_variable" || m.opcode == "data_listcontents") {
            val isList = m.opcode == "data_listcontents"
            val s = findSlot(project, owner, m.id, m.params[if (isList) "LIST" else "VARIABLE"], isList) ?: return null
            val mode = when {
                isList -> "list"
                m.mode == "large" || m.mode == "slider" -> m.mode
                else -> "default"
            }
            return record(m, s.target, s.id, mode, s.index, "", prefixed(s.target, s.name))
        }
        val (text, param) = builtinLabel(m) ?: return null
        return record(m, owner, null, if (m.mode == "large") "large" else "default", -1, param, prefixed(owner, text))
    }

    private fun record(
        m: Monitor,
        owner: Target,
        varId: String?,
        mode: String,
        slot: Int,
        param: String,
        label: String,
    ) = MonitorDef(
        owner, varId, m.opcode, mode, slot, param, label, m.x, m.y, m.width, m.height, m.visible,
        m.sliderMin, m.sliderMax, m.isDiscrete,
    )

    /** Finds a monitored variable by id in its owner, then in any target, then by name in the owner and the stage. */
    private fun findSlot(project: Project, owner: Target, id: String, name: String?, isList: Boolean): Slot? {
        for (t in listOf(owner) + project.targets.filter { it !== owner }) {
            slots(t, isList).firstOrNull { it.id == id }?.let { return it }
        }
        if (name == null) return null
        for (t in listOf(owner, project.stage).distinct()) {
            slots(t, isList).firstOrNull { it.name == name }?.let { return it }
        }
        return null
    }

    /** The target's variables or lists, numbered the way the code generator numbers its fields. */
    private fun slots(t: Target, isList: Boolean): List<Slot> = if (isList) {
        t.lists.values.mapIndexed { i, l -> Slot(t, t.variables.size + i, l.id, l.name) }
    } else {
        t.variables.values.mapIndexed { i, v -> Slot(t, i, v.id, v.name) }
    }

    private fun prefixed(t: Target, text: String): String = if (t.isStage) text else "${t.name}: $text"

    /** Labels from scratch-gui's `opcode-labels.js`, and the menu value the runtime needs to compute the value. */
    private fun builtinLabel(m: Monitor): Pair<String, String>? = when (m.opcode) {
        "motion_xposition" -> "x position" to ""
        "motion_yposition" -> "y position" to ""
        "motion_direction" -> "direction" to ""
        "looks_size" -> "size" to ""
        "looks_costumenumbername" -> {
            val which = m.params["NUMBER_NAME"] ?: "number"
            "costume $which" to which
        }
        "looks_backdropnumbername" -> {
            val which = m.params["NUMBER_NAME"] ?: "number"
            "backdrop $which" to which
        }
        "looks_backdropname" -> "backdrop name" to "name"
        "sound_volume" -> "volume" to ""
        "sensing_answer" -> "answer" to ""
        "sensing_loudness" -> "loudness" to ""
        "sensing_username" -> "username" to ""
        "sensing_timer" -> "timer" to ""
        "sensing_current" -> {
            val menu = m.params["CURRENTMENU"] ?: "YEAR"
            val text = if (menu.equals("dayofweek", ignoreCase = true)) "day of week" else menu.lowercase()
            text to menu
        }
        else -> null
    }

    /**
     * One monitor per line, fields separated by tabs: opcode, mode, sprite name (empty for the stage), slot,
     * param, label, x, y, width, height, visible, slider min, slider max, discrete. Text fields escape
     * backslash, tab, newline and carriage return. An unplaced monitor has empty x and y.
     */
    fun encode(defs: List<MonitorDef>): String {
        val sb = StringBuilder()
        for (d in defs) {
            val fields = listOf(
                d.opcode,
                d.mode,
                if (d.owner.isStage) "" else d.owner.name,
                d.slot.toString(),
                d.param,
                d.label,
                number(d.x),
                number(d.y),
                number(d.width),
                number(d.height),
                if (d.visible) "1" else "0",
                number(d.sliderMin),
                number(d.sliderMax),
                if (d.isDiscrete) "1" else "0",
            )
            for ((i, f) in fields.withIndex()) {
                if (i > 0) sb.append('\t')
                escape(sb, f)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun number(v: Double): String = if (v.isNaN()) "" else v.toString()

    private fun escape(sb: StringBuilder, s: String) {
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
    }
}
