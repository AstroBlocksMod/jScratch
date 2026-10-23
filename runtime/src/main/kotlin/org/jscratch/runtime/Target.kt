package org.jscratch.runtime

import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.time.LocalDateTime

enum class RotationStyle { ALL_AROUND, LEFT_RIGHT, DONT_ROTATE }

/** Axis-aligned bounds in Scratch stage coordinates (y grows upwards). */
class Bounds(val left: Double, val right: Double, val top: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = top - bottom

    fun contains(x: Double, y: Double): Boolean = x >= left && x <= right && y <= top && y >= bottom

    fun intersects(o: Bounds): Boolean = left <= o.right && right >= o.left && bottom <= o.top && top >= o.bottom
}

class Hat(val kind: Int, val arg: String, val scriptId: Int)

/**
 * Base class of every compiled sprite and of the stage. The compiler emits one
 * subclass per target whose constructor fills in costumes, variables and hats,
 * and whose `runScript` dispatches to one method per script.
 *
 * Every block that generated code calls lives here (or on [Runtime]) so the
 * code generator only has to know a handful of owner classes.
 */
abstract class Target(@JvmField val rt: Runtime, @JvmField val name: String, @JvmField val isStage: Boolean) {
    @JvmField var x: Double = 0.0

    @JvmField var y: Double = 0.0

    @JvmField var direction: Double = 90.0

    @JvmField var size: Double = 100.0

    @JvmField var visible: Boolean = true

    @JvmField var draggable: Boolean = false

    @JvmField var rotationStyle: RotationStyle = RotationStyle.ALL_AROUND

    @JvmField var layerOrder: Int = 0

    @JvmField var currentCostume: Int = 0

    @JvmField var volume: Double = 100.0

    @JvmField var ghost: Double = 0.0

    @JvmField var isClone: Boolean = false

    @JvmField val costumes: ArrayList<Costume> = ArrayList()

    @JvmField val sounds: ArrayList<SoundAsset> = ArrayList()

    @JvmField val hats: ArrayList<Hat> = ArrayList()

    @JvmField val pen: PenState = PenState()

    @Volatile var sayText: String = ""

    @Volatile var sayIsThink: Boolean = false
    private var sayToken: Int = 0

    // ---- hooks implemented by generated classes -------------------------------------------------

    /** Runs script number [id] on the calling (script) thread. */
    abstract fun runScript(id: Int)

    /** Creates a fresh, uninitialized instance of the same generated class (used for clones). */
    open fun newInstance(): Target = throw UnsupportedOperationException("not a sprite")

    /** Copies generated variable and list fields from [src] (clones get their own copies). */
    open fun copyLocalsFrom(src: Target) {}

    /** Looks up a variable of this target by its Scratch name (for `sensing_of`). Null when unknown. */
    open fun lookupVariable(name: String): Any? = null

    // ---- setup called from generated constructors -----------------------------------------------

    fun addCostume(
        name: String,
        resource: String,
        rotationCenterX: Double,
        rotationCenterY: Double,
        bitmapResolution: Double,
    ) {
        costumes.add(Costume(name, resource, rotationCenterX, rotationCenterY, bitmapResolution))
    }

    fun addSound(name: String, resource: String) {
        sounds.add(SoundAsset(name, resource))
    }

    fun registerHat(kind: Int, arg: String, scriptId: Int) {
        hats.add(Hat(kind, arg, scriptId))
    }

    fun setRotationStyle(style: String) {
        rotationStyle =
            when (style) {
                "left-right" -> RotationStyle.LEFT_RIGHT
                "don't rotate" -> RotationStyle.DONT_ROTATE
                else -> RotationStyle.ALL_AROUND
            }
    }

    fun copyFrom(src: Target) {
        x = src.x
        y = src.y
        direction = src.direction
        size = src.size
        visible = src.visible
        draggable = src.draggable
        rotationStyle = src.rotationStyle
        currentCostume = src.currentCostume
        volume = src.volume
        ghost = src.ghost
        pen.copyFrom(src.pen)
        copyLocalsFrom(src)
    }

    // ---- geometry -------------------------------------------------------------------------------

    val costume: Costume? get() = costumes.getOrNull(currentCostume)

    /** Costume-pixels to stage-pixels transform (stage pixel origin top-left, y down). */
    fun renderTransform(): AffineTransform? {
        val c = costume ?: return null
        val at = AffineTransform()
        at.translate(Runtime.STAGE_WIDTH / 2.0 + x, Runtime.STAGE_HEIGHT / 2.0 - y)
        if (!isStage) {
            when (rotationStyle) {
                RotationStyle.ALL_AROUND -> at.rotate(Math.toRadians(direction - 90.0))
                RotationStyle.LEFT_RIGHT -> if (direction < 0) at.scale(-1.0, 1.0)
                RotationStyle.DONT_ROTATE -> {}
            }
        }

        val s = (if (isStage) 1.0 else size / 100.0) / c.imageResolution
        at.scale(s, s)
        at.translate(-c.imageRotationCenterX, -c.imageRotationCenterY)
        return at
    }

    fun bounds(): Bounds? {
        val c = costume ?: return null
        val img = c.image ?: return null
        val at = renderTransform() ?: return null
        val pts =
            doubleArrayOf(
                0.0,
                0.0,
                img.width.toDouble(),
                0.0,
                0.0,
                img.height.toDouble(),
                img.width.toDouble(),
                img.height.toDouble(),
            )
        at.transform(pts, 0, pts, 0, 4)
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until 4) {
            minX = Math.min(minX, pts[i * 2])
            maxX = Math.max(maxX, pts[i * 2])
            minY = Math.min(minY, pts[i * 2 + 1])
            maxY = Math.max(maxY, pts[i * 2 + 1])
        }

        val hw = Runtime.STAGE_WIDTH / 2.0
        val hh = Runtime.STAGE_HEIGHT / 2.0
        return Bounds(minX - hw, maxX - hw, hh - minY, hh - maxY)
    }

    // ---- control / threads ----------------------------------------------------------------------

    fun yieldLoop() = rt.yieldLoop()

    fun wait(seconds: Double) = rt.wait(seconds)

    fun stopAll() = rt.stopAll()

    fun stopOtherScripts() = rt.stopOtherScripts(this)

    fun enterWarp() {
        ScriptThread.current()?.enterWarp()
    }

    fun exitWarp() {
        ScriptThread.current()?.exitWarp()
    }

    fun createCloneOf(option: Any?) {
        val name = Cast.toString(option)
        val original: Target? = if (name == "_myself_") this else rt.spriteNamed(name)
        if (original == null || original.isStage) return
        rt.createClone(original)
    }

    fun deleteThisClone() {
        if (!isClone) return
        rt.removeClone(this)
        throw StopScript()
    }

    fun broadcast(name: Any?) {
        rt.broadcast(Cast.toString(name))
    }

    fun broadcastAndWait(name: Any?) {
        rt.broadcastAndWait(Cast.toString(name))
    }

    // ---- motion ---------------------------------------------------------------------------------

    fun setXY(nx: Double, ny: Double) {
        if (isStage) return
        var tx = if (nx.isNaN()) 0.0 else nx
        var ty = if (ny.isNaN()) 0.0 else ny
        val fenced = fence(tx, ty)
        tx = fenced[0]
        ty = fenced[1]
        val ox = x
        val oy = y
        x = tx
        y = ty
        if (pen.down) rt.pen.line(ox, oy, tx, ty, pen)
        if (visible) rt.requestRedraw()
    }

    /** Keeps at least a sliver of the sprite on stage, like scratch-render's fencing. */
    private fun fence(nx: Double, ny: Double): DoubleArray {
        val b = bounds() ?: return doubleArrayOf(nx, ny)
        var rx = nx
        var ry = ny
        val dx = nx - x
        val dy = ny - y
        val inset = Math.floor(Math.min(b.width, b.height) / 2)
        val sx = Runtime.STAGE_WIDTH / 2.0 - Math.min(FENCE_WIDTH, inset)
        if (b.right + dx < -sx) {
            rx = Math.ceil(x - (sx + b.right))
        } else if (b.left + dx > sx) {
            rx = Math.floor(x + (sx - b.left))
        }

        val sy = Runtime.STAGE_HEIGHT / 2.0 - Math.min(FENCE_WIDTH, inset)
        if (b.top + dy < -sy) {
            ry = Math.ceil(y - (sy + b.top))
        } else if (b.bottom + dy > sy) {
            ry = Math.floor(y + (sy - b.bottom))
        }
        return doubleArrayOf(rx, ry)
    }

    fun setDirection(d: Double) {
        if (isStage) return
        if (d.isNaN() || d.isInfinite()) return
        // wrap into (-180, 180]
        direction = d - Math.floor((d + 179.0) / 360.0) * 360.0
        if (visible) rt.requestRedraw()
    }

    fun moveSteps(steps: Double) {
        val rad = Math.toRadians(90.0 - direction)
        setXY(x + steps * Math.cos(rad), y + steps * Math.sin(rad))
    }

    fun turnRight(deg: Double) = setDirection(direction + deg)

    fun turnLeft(deg: Double) = setDirection(direction - deg)

    fun changeX(dx: Double) = setXY(x + dx, y)

    fun changeY(dy: Double) = setXY(x, y + dy)

    fun setX(nx: Double) = setXY(nx, y)

    fun setY(ny: Double) = setXY(x, ny)

    fun xPosition(): Double = limitPrecision(x)

    fun yPosition(): Double = limitPrecision(y)

    fun getDirection(): Double = direction

    private fun limitPrecision(v: Double): Double {
        val rounded = Math.round(v).toDouble()
        return if (Math.abs(v - rounded) < 1e-9) rounded else v
    }

    /** Resolves a `go to` / `point towards` / `distance to` menu value to a stage position. */
    private fun targetPosition(option: Any?): Point2D.Double? {
        val name = Cast.toString(option)
        return when (name) {
            "_mouse_" -> Point2D.Double(rt.mouseX, rt.mouseY)
            "_random_" -> {
                Point2D.Double(
                    Math.round(Runtime.STAGE_WIDTH * (Math.random() - 0.5)).toDouble(),
                    Math.round(Runtime.STAGE_HEIGHT * (Math.random() - 0.5)).toDouble(),
                )
            }
            else -> {
                rt.spriteNamed(name)?.let { Point2D.Double(it.x, it.y) }
            }
        }
    }

    fun goTo(option: Any?) {
        val p = targetPosition(option) ?: return
        setXY(p.x, p.y)
    }

    fun goToXY(nx: Double, ny: Double) = setXY(nx, ny)

    fun glideToXY(seconds: Double, tx: Double, ty: Double) {
        val durationMs = Math.max(0.0, seconds * 1000.0)
        val start = rt.nowMillis()
        val sx = x
        val sy = y
        if (durationMs <= 0.0) {
            setXY(tx, ty)
            rt.yieldTick()
            return
        }
        while (true) {
            rt.yieldTick()
            val t = (rt.nowMillis() - start) / durationMs
            if (t >= 1.0) {
                setXY(tx, ty)
                return
            }
            setXY(sx + (tx - sx) * t, sy + (ty - sy) * t)
        }
    }

    fun glideTo(seconds: Double, option: Any?) {
        val p = targetPosition(option) ?: return
        glideToXY(seconds, p.x, p.y)
    }

    fun pointInDirection(d: Double) = setDirection(d)

    fun pointTowards(option: Any?) {
        if (Cast.toString(option) == "_random_") {
            setDirection(Math.round(Math.random() * 360.0 - 180.0).toDouble())
            return
        }

        val p = targetPosition(option) ?: return
        val dx = p.x - x
        val dy = p.y - y
        setDirection(90.0 - Math.toDegrees(Math.atan2(dy, dx)))
    }

    fun ifOnEdgeBounce() {
        val b = bounds() ?: return
        val hw = Runtime.STAGE_WIDTH / 2.0
        val hh = Runtime.STAGE_HEIGHT / 2.0
        val distLeft = Math.max(0.0, hw + b.left)
        val distTop = Math.max(0.0, hh - b.top)
        val distRight = Math.max(0.0, hw - b.right)
        val distBottom = Math.max(0.0, hh + b.bottom)
        var nearest = ""
        var minDist = Double.POSITIVE_INFINITY
        if (distLeft < minDist) {
            minDist = distLeft
            nearest = "left"
        }
        if (distTop < minDist) {
            minDist = distTop
            nearest = "top"
        }
        if (distRight < minDist) {
            minDist = distRight
            nearest = "right"
        }
        if (distBottom < minDist) {
            minDist = distBottom
            nearest = "bottom"
        }
        if (minDist > 0) return
        val rad = Math.toRadians(90.0 - direction)
        var dx = Math.cos(rad)
        var dy = -Math.sin(rad)
        when (nearest) {
            "left" -> dx = Math.max(0.2, Math.abs(dx))
            "top" -> dy = Math.max(0.2, Math.abs(dy))
            "right" -> dx = 0 - Math.max(0.2, Math.abs(dx))
            "bottom" -> dy = 0 - Math.max(0.2, Math.abs(dy))
        }
        setDirection(Math.toDegrees(Math.atan2(dy, dx)) + 90.0)
        // Push the sprite back inside the stage.
        val nb = bounds() ?: return
        var nx = x
        var ny = y
        if (nb.left < -hw) {
            nx += -hw - nb.left
        } else if (nb.right > hw) {
            nx -= nb.right - hw
        }
        if (nb.top > hh) {
            ny -= nb.top - hh
        } else if (nb.bottom < -hh) {
            ny += -hh - nb.bottom
        }
        setXY(nx, ny)
    }

    // ---- looks ----------------------------------------------------------------------------------

    fun say(message: Any?) = sayInternal(message, false)

    fun think(message: Any?) = sayInternal(message, true)

    private fun sayInternal(message: Any?, isThink: Boolean) {
        var text =
            if (message is Double) {
                Cast.toString(Math.round(message * 100.0) / 100.0)
            } else {
                Cast.toString(message)
            }
        if (text.length > 330) text = text.substring(0, 330)
        sayToken++
        sayText = text
        sayIsThink = isThink
        rt.onSay(this, text, isThink)
        rt.requestRedraw()
    }

    fun sayForSecs(message: Any?, seconds: Double) {
        say(message)
        val token = sayToken
        rt.wait(seconds)
        if (token == sayToken) sayInternal("", false)
    }

    fun thinkForSecs(message: Any?, seconds: Double) {
        think(message)
        val token = sayToken
        rt.wait(seconds)
        if (token == sayToken) sayInternal("", true)
    }

    fun clearSay() {
        sayToken++
        sayText = ""
    }

    fun show() {
        visible = true
        rt.requestRedraw()
    }

    fun hide() {
        visible = false
        rt.requestRedraw()
    }

    fun setCostumeIndex(index: Double) {
        if (costumes.isEmpty()) return
        val r = Math.round(if (index.isNaN()) 0.0 else index).toDouble()
        val n = costumes.size
        currentCostume = (((r % n) + n) % n).toInt()
        if (visible || isStage) rt.requestRedraw()
    }

    /** `switch costume to`: a number is an index, a name is looked up, "next costume" steps. */
    fun switchCostume(requested: Any?) {
        if (requested is Double) {
            setCostumeIndex(requested - 1)
            return
        }

        val s = Cast.toString(requested)
        val byName = costumes.indexOfFirst { it.name == s }
        when {
            byName >= 0 -> setCostumeIndex(byName.toDouble())
            s == "next costume" -> setCostumeIndex((currentCostume + 1).toDouble())
            s == "previous costume" -> setCostumeIndex((currentCostume - 1).toDouble())
            !Cast.toNumberOrNaN(s).isNaN() && !Cast.isWhiteSpace(s) -> setCostumeIndex(Cast.toNumber(s) - 1)
        }
    }

    fun nextCostume() = setCostumeIndex((currentCostume + 1).toDouble())

    fun costumeNumber(): Double = (currentCostume + 1).toDouble()

    fun costumeName(): String = costume?.name ?: ""

    fun switchBackdrop(requested: Any?) = rt.stage.setBackdrop(requested, false)

    fun switchBackdropAndWait(requested: Any?) = rt.stage.setBackdrop(requested, true)

    fun nextBackdrop() = rt.stage.setBackdrop("next backdrop", false)

    fun backdropNumber(): Double = rt.stage.costumeNumber()

    fun backdropName(): String = rt.stage.costumeName()

    fun setSize(s: Double) {
        if (isStage) return
        val c = costume
        var minScale = 5.0
        var maxScale = 1500.0
        if (c != null && c.width > 0 && c.height > 0) {
            // Scratch limits size so the costume stays between 5 px and 1.5 stage sizes.
            minScale = Math.min(1.0, Math.max(5.0 / c.width, 5.0 / c.height)) * 100.0
            maxScale =
                Math.min(
                    1.5 * Runtime.STAGE_WIDTH / c.width,
                    1.5 * Runtime.STAGE_HEIGHT / c.height,
                ) * 100.0
            if (maxScale < minScale) maxScale = minScale
        }
        size = (if (s.isNaN()) 100.0 else s).coerceIn(minScale, maxScale)
        if (visible) rt.requestRedraw()
    }

    fun changeSize(delta: Double) = setSize(size + delta)

    fun getSize(): Double = size

    fun goToLayer(where: String) {
        if (isStage) return
        rt.moveToLayer(this, where == "front")
    }

    fun moveLayers(direction: String, count: Double) {
        if (isStage) return
        val n = Cast.toNumber(count).toInt()
        rt.moveLayers(this, if (direction == "forward") n else -n)
    }

    fun setEffect(effect: String, value: Double) {
        if (effect.equals("ghost", ignoreCase = true)) {
            ghost = value.coerceIn(0.0, 100.0)
            rt.requestRedraw()
        } else {
            Log.warn("Graphic effect '$effect' is not supported yet; only 'ghost' is.")
        }
    }

    fun changeEffect(effect: String, delta: Double) {
        if (effect.equals("ghost", ignoreCase = true)) setEffect(effect, ghost + delta) else setEffect(effect, delta)
    }

    fun clearEffects() {
        ghost = 0.0
        rt.requestRedraw()
    }

    // ---- sensing --------------------------------------------------------------------------------

    fun touching(option: Any?): Boolean {
        if (!visible) return false
        val b = bounds() ?: return false
        return when (val s = Cast.toString(option)) {
            "_mouse_" -> b.contains(rt.mouseX, rt.mouseY)
            "_edge_" -> {
                b.left < -Runtime.STAGE_WIDTH / 2.0 || b.right > Runtime.STAGE_WIDTH / 2.0 ||
                    b.top > Runtime.STAGE_HEIGHT / 2.0 || b.bottom < -Runtime.STAGE_HEIGHT / 2.0
            }
            else -> {
                rt.sprites.any { other ->
                    other !== this && other.name == s && other.visible && other.bounds()?.intersects(b) == true
                }
            }
        }
    }

    fun touchingColor(color: Any?): Boolean {
        rt.unsupported("sensing_touchingcolor")
        return false
    }

    fun distanceTo(option: Any?): Double {
        val p = targetPosition(option) ?: return 10000.0
        val dx = x - p.x
        val dy = y - p.y
        return Math.sqrt(dx * dx + dy * dy)
    }

    fun keyPressed(key: Any?): Boolean = rt.keyPressed(key)

    fun mouseDown(): Boolean = rt.mouseDown

    fun mouseX(): Double = rt.mouseX

    fun mouseY(): Double = rt.mouseY

    fun timer(): Double = rt.timer()

    fun resetTimer() = rt.resetTimer()

    fun askAndWait(question: Any?) = rt.ask(this, Cast.toString(question))

    fun answer(): String = rt.answer

    fun daysSince2000(): Double = (System.currentTimeMillis() - 946684800000L) / 86400000.0

    fun current(what: String): Double {
        val now = LocalDateTime.now()
        return when (what.lowercase()) {
            "year" -> now.year.toDouble()
            "month" -> now.monthValue.toDouble()
            "date" -> now.dayOfMonth.toDouble()
            "dayofweek" -> (now.dayOfWeek.value % 7 + 1).toDouble()
            "hour" -> now.hour.toDouble()
            "minute" -> now.minute.toDouble()
            "second" -> now.second.toDouble()
            else -> 0.0
        }
    }

    fun sensingOf(property: String, option: Any?): Any {
        val objName = Cast.toString(option)
        val target: Target = if (objName == "_stage_") rt.stage else rt.spriteNamed(objName) ?: return 0.0
        return when (property) {
            "x position" -> target.x
            "y position" -> target.y
            "direction" -> target.direction
            "costume #", "backdrop #" -> target.costumeNumber()
            "costume name", "backdrop name" -> target.costumeName()
            "size" -> target.size
            "volume" -> target.volume
            else -> target.lookupVariable(property) ?: 0.0
        }
    }

    // ---- sound ----------------------------------------------------------------------------------

    private fun soundFor(option: Any?): SoundAsset? {
        if (sounds.isEmpty()) return null
        if (option is Double) {
            val n = sounds.size
            return sounds[(((Math.round(option - 1) % n) + n) % n).toInt()]
        }

        val s = Cast.toString(option)
        sounds.firstOrNull { it.name == s }?.let { return it }
        val n = Cast.toNumberOrNaN(s)
        if (!n.isNaN() && !Cast.isWhiteSpace(s)) return soundFor(n)
        return null
    }

    fun playSound(option: Any?) {
        val s = soundFor(option) ?: return
        rt.sounds.play(s, volume)
    }

    fun playSoundUntilDone(option: Any?) {
        val s = soundFor(option) ?: return
        val secs = rt.sounds.play(s, volume)
        if (secs > 0) rt.wait(secs)
    }

    fun stopAllSounds() = rt.sounds.stopAll()

    fun setVolume(v: Double) {
        volume = v.coerceIn(0.0, 100.0)
    }

    fun changeVolume(d: Double) = setVolume(volume + d)

    fun getVolume(): Double = volume

    // ---- pen ------------------------------------------------------------------------------------

    fun penClear() = rt.pen.clear()

    fun penDown() {
        pen.down = true
        rt.pen.line(x, y, x, y, pen)
    }

    fun penUp() {
        pen.down = false
    }

    fun stamp() {
        val img = costume?.image ?: return
        val at = renderTransform() ?: return
        rt.pen.stamp(img, at, (1.0 - ghost / 100.0).toFloat())
    }

    fun setPenColor(color: Any?) = pen.setArgb(Cast.toArgb(color))

    fun setPenParam(param: Any?, value: Double) = pen.setParam(Cast.toString(param), value)

    fun changePenParam(param: Any?, delta: Double) = pen.changeParam(Cast.toString(param), delta)

    fun setPenSize(s: Double) {
        pen.size = (if (s.isNaN()) 1.0 else s).coerceIn(1.0, 1200.0)
    }

    fun changePenSize(d: Double) = setPenSize(pen.size + d)

    // ---- misc -----------------------------------------------------------------------------------

    fun unsupported(opcode: String) = rt.unsupported(opcode)

    companion object {
        const val FENCE_WIDTH = 15.0
    }
}

/** Base class of generated sprite classes. */
abstract class Sprite(rt: Runtime, name: String) : Target(rt, name, false)

/** Base class of the generated stage class. */
abstract class Stage(rt: Runtime, name: String) : Target(rt, name, true) {
    /** `switch backdrop to`, which also fires `when backdrop switches to` hats. */
    fun setBackdrop(requested: Any?, wait: Boolean) {
        if (requested is Double) {
            setCostumeIndex(requested - 1)
        } else {
            val s = Cast.toString(requested)
            val byName = costumes.indexOfFirst { it.name == s }
            when {
                byName >= 0 -> setCostumeIndex(byName.toDouble())
                s == "next backdrop" -> setCostumeIndex((currentCostume + 1).toDouble())
                s == "previous backdrop" -> setCostumeIndex((currentCostume - 1).toDouble())
                s == "random backdrop" -> {
                    val n = costumes.size
                    if (n > 1) {
                        var idx = (Math.random() * (n - 1)).toInt()
                        if (idx >= currentCostume) idx++
                        setCostumeIndex(idx.toDouble())
                    }
                }
                !Cast.toNumberOrNaN(s).isNaN() && !Cast.isWhiteSpace(s) -> setCostumeIndex(Cast.toNumber(s) - 1)
            }
        }
        // Scratch fires "when backdrop switches to" on every switch, even to the same backdrop.
        val started = rt.startHats(Runtime.HAT_BACKDROP, costumeName(), null)
        if (wait) rt.waitForThreads(started)
    }
}
