package org.jscratch.runtime

import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.time.LocalDateTime

enum class RotationStyle { ALL_AROUND, LEFT_RIGHT, DONT_ROTATE }

/** Axis-aligned bounds in Scratch stage coordinates (y grows upwards). */
class Bounds(val left: Double, val right: Double, val top: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = top - bottom

    fun intersects(o: Bounds): Boolean = left <= o.right && right >= o.left && bottom <= o.top && top >= o.bottom
}

class Hat(val kind: Int, val arg: String, val scriptId: Int) {
    /** The last thread an edge-activated hat queued, so checking whether its script still runs is one lookup. */
    internal var thread: ScriptThread? = null
}

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

    /** Whether the mouse is dragging this sprite right now. */
    @JvmField var dragging: Boolean = false

    @JvmField var rotationStyle: RotationStyle = RotationStyle.ALL_AROUND

    @JvmField var layerOrder: Int = 0

    @JvmField var currentCostume: Int = 0

    @JvmField var volume: Double = 100.0

    /** The pitch sound effect, -360 to 360. */
    @JvmField var pitch: Double = 0.0

    /** The pan sound effect, -100 (left) to 100 (right). */
    @JvmField var pan: Double = 0.0

    @JvmField var ghost: Double = 0.0

    /** Every graphic effect besides [ghost]. */
    internal var effects: Effects = Effects.NONE
        private set
    private var effected: EffectedCostume? = null

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

    /** Reads the variable or list a monitor shows, by the slot number the compiler gave it. */
    open fun monitorSlot(slot: Int): Any? = null

    /** Sets a variable from a slider monitor. */
    open fun setMonitorSlot(slot: Int, value: Any?) {}

    /** The current condition of the edge-activated hat of script [id]. */
    open fun hatPredicate(id: Int): Boolean = false

    // ---- edge-activated hats --------------------------------------------------------------------

    /** The condition of each edge-activated hat when it was last checked, by script, as scratch-vm keeps them. */
    private var edgeValues = HashMap<Int, Boolean>()

    /**
     * Checks the condition of the edge-activated hat of script [id] and remembers it. True when the
     * script should start: the condition is true and was false before, or was never checked.
     */
    internal fun edgeActivated(id: Int): Boolean {
        val value =
            try {
                hatPredicate(id)
            } catch (e: RuntimeException) {
                // The same text every frame, so the log prints it once.
                Log.warn("The condition of script $id in '$name' failed with ${e.javaClass.name} and counts as false")
                false
            }
        val old = edgeValues.put(id, value)
        return value && old != true
    }

    /**
     * Whether an edge-activated hat of this target could still fire in a headless run with no script
     * running: it was never checked, or it watches the timer, which keeps going, and was false. Without
     * scripts or a mouse nothing moves, so a touching hat stays as it is, and loudness is always -1.
     */
    internal fun edgeHatWaiting(): Boolean = hats.any {
        if (it.kind != Runtime.HAT_EDGE) return@any false
        val value = edgeValues[it.scriptId]
        value == null || (it.arg == "timer" && !value)
    }

    /** Forgets every hat condition, which the green flag does, so hats that are already true fire again. */
    fun clearEdgeValues() {
        edgeValues.clear()
    }

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
        pitch = src.pitch
        pan = src.pan
        ghost = src.ghost
        effects = src.effects
        effected = src.effected
        pen.copyFrom(src.pen)
        // A clone starts with its parent's hat conditions, so a hat that is true for both does not fire again.
        edgeValues = HashMap(src.edgeValues)
        copyLocalsFrom(src)
    }

    // ---- geometry -------------------------------------------------------------------------------

    val costume: Costume? get() = costumes.getOrNull(currentCostume)

    /** The current costume with [effects] applied, or null while none of them is on. */
    internal fun effectedCostume(): EffectedCostume? {
        if (!effects.active) return null
        val c = costume ?: return null
        val sil = c.silhouette ?: return null
        val scale = drawnScale()
        val e = effected
        if (e != null && e.source === sil && e.effects == effects && e.scale == scale) return e
        return sil.effected(c.width, c.height, effects, scale).also { effected = it }
    }

    /**
     * The resolution the target is drawn at, in output pixels per costume pixel: the pen layer and
     * screenshots draw at twice the stage size, and a large window on a HiDPI screen at more. Bucketed
     * to powers of two so a sprite growing smoothly re-renders its effects a few times, not on every
     * size, and capped at the costume's own resolution, which rotation cannot raise.
     */
    private fun drawnScale(): Double {
        val c = costume ?: return 1.0
        val output = Math.max(rt.pen.scale.toDouble(), rt.windowDeviceScale)
        val s = output * (if (isStage) 1.0 else size / 100.0) / c.imageResolution
        if (s >= 1.0) return 1.0
        var bucket = 1.0
        while (bucket * 0.5 >= s && bucket > 1.0 / 16) bucket *= 0.5
        return bucket
    }

    /** The pixels this target draws and stamps: its costume image with every effect but ghost. */
    fun drawnImage(): BufferedImage? = effectedCostume()?.image ?: costume?.image

    /**
     * The [renderTransform], adjusted when [drawnImage] is an effected image at reduced resolution,
     * so stamping and drawing place it exactly where the full-size costume would go.
     */
    fun drawnTransform(): AffineTransform? {
        val at = renderTransform() ?: return null
        val e = effectedCostume() ?: return at
        if (e.width != e.source.width || e.height != e.source.height) {
            at.scale(e.source.width.toDouble() / e.width, e.source.height.toDouble() / e.height)
        }
        return at
    }

    /** The costume's opaque pixels as drawn, which effects that move pixels change. */
    private fun drawnSilhouette(): Silhouette? =
        if (effects.changesShape) effectedCostume()?.silhouette else costume?.silhouette

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

    /**
     * Tight bounds: the box around the costume's opaque pixels, transformed by
     * position, rotation and size. Scratch uses these for the edge, bouncing and
     * fencing. Null when nothing is opaque, like scratch-render's empty bounds.
     */
    fun bounds(): Bounds? = placement().bounds

    /** Bounds snapped outwards to whole pixels and clipped to the stage, as scratch-render queries them. */
    internal fun touchingBounds(): Bounds? = placement().touchingBounds

    /** Tests stage points against this target's costume pixels. Null when there is nothing to hit. */
    internal fun probe(): Probe? = placement().probe

    private var placement: Placement? = null

    private fun placement(): Placement {
        val p = placement
        if (p != null && p.isCurrent()) return p
        return Placement().also { placement = it }
    }

    /**
     * Geometry for one position, direction, size, rotation style, costume image and
     * set of effects. Each part is computed on first use and kept until any of those
     * change, so repeated `touching` checks against a sprite that has not moved cost nothing.
     */
    private inner class Placement {
        private val costume = this@Target.costume
        private val image = costume?.image
        private val x = this@Target.x
        private val y = this@Target.y
        private val direction = this@Target.direction
        private val size = this@Target.size
        private val rotationStyle = this@Target.rotationStyle
        private val effects = this@Target.effects

        fun isCurrent(): Boolean {
            val c = this@Target.costume
            return c === costume && c?.image === image && this@Target.x == x && this@Target.y == y &&
                this@Target.direction == direction && this@Target.size == size &&
                this@Target.rotationStyle == rotationStyle && this@Target.effects == effects
        }

        val bounds: Bounds? by lazy(LazyThreadSafetyMode.NONE) { computeBounds() }
        val touchingBounds: Bounds? by lazy(LazyThreadSafetyMode.NONE) { computeTouchingBounds(bounds) }
        val probe: Probe? by lazy(LazyThreadSafetyMode.NONE) { computeProbe() }
    }

    private fun computeBounds(): Bounds? {
        val pts = drawnSilhouette()?.hull ?: return null
        if (pts.isEmpty()) return null
        // The hull of a reduced-resolution effected image is in its pixels, not the costume's.
        val at = (if (effects.changesShape) drawnTransform() else renderTransform()) ?: return null
        val n = pts.size / 2
        val out = DoubleArray(pts.size)
        at.transform(pts, 0, out, 0, n)
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until n) {
            minX = Math.min(minX, out[i * 2])
            maxX = Math.max(maxX, out[i * 2])
            minY = Math.min(minY, out[i * 2 + 1])
            maxY = Math.max(maxY, out[i * 2 + 1])
        }

        val hw = Runtime.STAGE_WIDTH / 2.0
        val hh = Runtime.STAGE_HEIGHT / 2.0
        return Bounds(minX - hw, maxX - hw, hh - minY, hh - maxY)
    }

    private fun computeTouchingBounds(b: Bounds?): Bounds? {
        if (b == null) return null
        val hw = Runtime.STAGE_WIDTH / 2.0
        val hh = Runtime.STAGE_HEIGHT / 2.0
        val left = Math.floor(b.left).coerceIn(-hw, hw)
        val right = Math.ceil(b.right).coerceIn(-hw, hw)
        val bottom = Math.floor(b.bottom).coerceIn(-hh, hh)
        val top = Math.ceil(b.top).coerceIn(-hh, hh)
        if (right <= left || top <= bottom) return null
        return Bounds(left, right, top, bottom)
    }

    private fun computeProbe(): Probe? {
        val c = costume ?: return null
        val sil = c.silhouette ?: return null
        val inverse =
            try {
                renderTransform()?.createInverse() ?: return null
            } catch (e: java.awt.geom.NoninvertibleTransformException) {
                return null
            }
        // scratch-render samples bitmaps by nearest neighbor, and vectors only when they are
        // drawn upright at their own size.
        val drawnDirection = if (rotationStyle == RotationStyle.ALL_AROUND) direction else 90.0
        val nearest = !c.isSvg || (drawnDirection % 90.0 == 0.0 && size > 99 && size < 101)
        return Probe(inverse, sil, nearest, effects, c.width, c.height)
    }

    /**
     * Samples the costume at stage points. With [effects], it moves each point the way the effects
     * move pixels and changes the color it finds, like scratch-render's `getLocalPosition` and
     * `sampleColor4b`. Ghost is left to the caller.
     */
    internal class Probe(
        inverse: AffineTransform,
        private val sil: Silhouette,
        private val nearest: Boolean,
        private val effects: Effects,
        private val skinWidth: Double,
        private val skinHeight: Double,
    ) : ColorLayer {
        private val m00 = inverse.scaleX
        private val m01 = inverse.shearX
        private val m02 = inverse.translateX
        private val m10 = inverse.shearY
        private val m11 = inverse.scaleY
        private val m12 = inverse.translateY

        /** Whether the stage point ([x], [y]), in Scratch coordinates, lands on an opaque pixel. */
        fun hits(x: Double, y: Double): Boolean =
            toImage(x, y) { px, py -> if (nearest) sil.isTouchingNearest(px, py) else sil.isTouchingLinear(px, py) }

        /**
         * The costume's color at ([x], [y]) with every effect but ghost. scratch-render samples colors
         * by nearest neighbor.
         */
        override fun colorAt(x: Double, y: Double): Int =
            effects.transformColor(toImage(x, y) { px, py -> sil.colorAtNearest(px, py) })

        private inline fun <R> toImage(x: Double, y: Double, sample: (Double, Double) -> R): R {
            val sx = Runtime.STAGE_WIDTH / 2.0 + x
            val sy = Runtime.STAGE_HEIGHT / 2.0 - y
            var px = m00 * sx + m01 * sy + m02
            var py = m10 * sx + m11 * sy + m12
            if (Math.abs(px) < sil.width * 1e-6) px = 0.0
            if (Math.abs(py) < sil.height * 1e-6) py = 0.0
            // Effects only move points that land on the costume.
            if (effects.changesShape && px >= 0 && px < sil.width && py >= 0 && py < sil.height) {
                val point = doubleArrayOf(px / sil.width, py / sil.height)
                effects.transformPoint(point, skinWidth, skinHeight)
                px = point[0] * sil.width
                py = point[1] * sil.height
            }
            return sample(px, py)
        }
    }

    /** A sprite with a ghost effect, as color sensing sees it. */
    private class Faded(private val probe: Probe, private val keep: Double) : ColorLayer {
        override fun colorAt(x: Double, y: Double): Int = ColorSensing.fade(probe.colorAt(x, y), keep)
    }

    /**
     * Whether the mouse at ([mx], [my]) is over one of this sprite's opaque pixels. Like
     * scratch-render, it tests the whole stage pixel under the mouse, not the exact point.
     */
    fun touchingPoint(mx: Double, my: Double): Boolean {
        if (!visible) return false
        return probe()?.hits(Math.floor(mx), Math.ceil(my)) == true
    }

    /**
     * Pixel-accurate collision with every visible sprite or clone called [spriteName], except this
     * one. Like scratch-render, it walks the whole pixels where the bounds overlap and stops at the
     * first one that is opaque in both costumes. Parts outside the stage never collide.
     */
    private fun touchingSprite(spriteName: String): Boolean {
        val mine = touchingBounds() ?: return false
        val myProbe = probe() ?: return false
        val probes = ArrayList<Probe>()
        var left = Double.POSITIVE_INFINITY
        var right = Double.NEGATIVE_INFINITY
        var bottom = Double.POSITIVE_INFINITY
        var top = Double.NEGATIVE_INFINITY
        for (other in rt.sprites) {
            // A sprite being dragged can touch others, but nothing touches it, as in Scratch 2 and 3.
            if (other === this || other.name != spriteName || !other.visible || other.dragging) continue
            val ob = other.touchingBounds() ?: continue
            if (!ob.intersects(mine)) continue
            probes.add(other.probe() ?: continue)
            left = Math.min(left, Math.max(mine.left, ob.left))
            right = Math.max(right, Math.min(mine.right, ob.right))
            bottom = Math.min(bottom, Math.max(mine.bottom, ob.bottom))
            top = Math.max(top, Math.min(mine.top, ob.top))
        }
        if (probes.isEmpty()) return false
        var px = left
        while (px <= right) {
            var py = bottom
            while (py <= top) {
                if (myProbe.hits(px, py)) {
                    for (p in probes) if (p.hits(px, py)) return true
                }
                py++
            }
            px++
        }
        return false
    }

    /**
     * `touching color` and, with a [mask], `color is touching color`. Like scratch-render's CPU path,
     * it walks the whole pixels of this sprite's bounds. Wherever this sprite is opaque (or, with a
     * mask, has the mask color, ghost ignored), it blends what everything else draws there and stops
     * at the first match. The pen layer covers the whole stage, so scratch-render's union of candidate
     * bounds is always these bounds. Unlike `touching` a sprite, this works while hidden.
     */
    private fun sensesColor(rgb: Int, mask: Int): Boolean {
        val bounds = touchingBounds() ?: return false
        val mine = probe() ?: return false
        val layers = ArrayList<ColorLayer>()
        for (i in rt.sprites.indices.reversed()) {
            val other = rt.sprites[i]
            // A fully ghosted sprite adds nothing to the blend, so skip sampling it.
            if (other === this || !other.visible || other.ghost >= 100) continue
            val ob = other.touchingBounds() ?: continue
            if (!ob.intersects(bounds)) continue
            val p = other.probe() ?: continue
            layers.add(if (other.ghost > 0) Faded(p, 1 - other.ghost / 100.0) else p)
        }
        layers.add(rt.pen)
        if (!isStage) rt.stage.probe()?.let { layers.add(it) }
        var y = bounds.bottom
        while (y <= bounds.top) {
            var x = bounds.left
            while (x <= bounds.right) {
                val inside =
                    if (mask == ColorSensing.NO_MASK) {
                        mine.hits(x, y)
                    } else {
                        ColorSensing.maskMatches(mine.colorAt(x, y), mask)
                    }
                if (inside && ColorSensing.colorMatches(ColorSensing.blend(layers, x, y), rgb)) return true
                x++
            }
            y++
        }
        return false
    }

    // ---- control / threads ----------------------------------------------------------------------

    fun yieldLoop() = rt.yieldLoop()

    fun wait(seconds: Double) = rt.wait(seconds)

    fun stopAll() = rt.stopAll()

    fun stopOtherScripts() = rt.stopOtherScripts(this)

    fun enterWarp() {
        rt.currentScript?.enterWarp()
    }

    fun exitWarp() {
        rt.currentScript?.exitWarp()
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

    // The hidden legacy counter blocks. Every target shares the one counter.

    fun counter(): Double = rt.counter

    fun incrCounter() {
        rt.counter++
    }

    fun clearCounter() {
        rt.counter = 0.0
    }

    fun broadcast(name: Any?) {
        rt.broadcast(Cast.toString(name))
    }

    fun broadcastAndWait(name: Any?) {
        rt.broadcastAndWait(Cast.toString(name))
    }

    // ---- motion ---------------------------------------------------------------------------------

    /** Moves the sprite. As in Scratch, scripts cannot move a sprite while the mouse drags it. */
    fun setXY(nx: Double, ny: Double) {
        if (!dragging) moveTo(nx, ny, true)
    }

    /** Moves the sprite where the mouse drags it. Like in Scratch, that never draws with the pen. */
    internal fun dragTo(nx: Double, ny: Double) = moveTo(nx, ny, false)

    private fun moveTo(nx: Double, ny: Double, drawPen: Boolean) {
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
        if (drawPen && pen.down) rt.pen.line(ox, oy, tx, ty, pen)
        if (visible) rt.requestRedraw()
    }

    /** Keeps at least a sliver of the sprite on stage, like scratch-render's fencing. */
    private fun fence(nx: Double, ny: Double): DoubleArray {
        // Far from the edges no part of the costume can leave the fence, so skip the exact bounds.
        // The costume's box bounds the fence bounds whatever the rotation or effects.
        val r = (costume?.boxRadius() ?: 0.0) * size / 100.0
        val clear =
            Math.abs(nx) + r <= Runtime.STAGE_WIDTH / 2.0 - FENCE_WIDTH &&
                Math.abs(ny) + r <= Runtime.STAGE_HEIGHT / 2.0 - FENCE_WIDTH
        if (clear) return doubleArrayOf(nx, ny)
        val b = fenceBounds()
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

    /**
     * The whole costume box under the current transform, in stage coordinates: the `getAABB` bounds
     * scratch-render fences with. Unlike [bounds] it counts transparent pixels and is never empty,
     * so a sprite wearing a blank costume is fenced too. Projects depend on that to measure the
     * stage: they move such a sprite to a huge position and read the fenced coordinates back.
     */
    private fun fenceBounds(): Bounds {
        val img = costume?.image
        val at = renderTransform()
        if (img == null || at == null) return Bounds(x, x, y, y)
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
        var minX = pts[0]
        var maxX = pts[0]
        var minY = pts[1]
        var maxY = pts[1]
        for (i in 1 until 4) {
            minX = Math.min(minX, pts[i * 2])
            maxX = Math.max(maxX, pts[i * 2])
            minY = Math.min(minY, pts[i * 2 + 1])
            maxY = Math.max(maxY, pts[i * 2 + 1])
        }
        val hw = Runtime.STAGE_WIDTH / 2.0
        val hh = Runtime.STAGE_HEIGHT / 2.0
        return Bounds(minX - hw, maxX - hw, hh - minY, hh - maxY)
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
        // scratch-vm says numbers as parseFloat(n.toFixed(2)). Every double from 2^52 up is a whole number
        // already, and Math.round would saturate at Long.MAX_VALUE there, so only smaller ones are rounded.
        var text =
            if (message is Double && Math.abs(message) < TWO_POW_52) {
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

    /** `set effect to`. Like scratch-vm, ghost is clamped to 0..100 and unknown effects change nothing. */
    fun setEffect(effect: String, value: Double) {
        val name = effect.lowercase()
        if (name == "ghost" || effects.get(name) != null) setEffectNamed(name, value) else unknownEffect(effect)
    }

    fun changeEffect(effect: String, delta: Double) {
        val name = effect.lowercase()
        val current = if (name == "ghost") ghost else effects.get(name)
        if (current != null) setEffectNamed(name, current + delta) else unknownEffect(effect)
    }

    /** Sets the known effect [name], which is already lower case. */
    private fun setEffectNamed(name: String, value: Double) {
        if (name == "ghost") ghost = value.coerceIn(0.0, 100.0) else effects = effects.with(name, value)
        if (visible || isStage) rt.requestRedraw()
    }

    private fun unknownEffect(effect: String) = Log.warn("Unknown graphic effect '$effect'; it does nothing.")

    fun clearEffects() {
        ghost = 0.0
        effects = Effects.NONE
        rt.requestRedraw()
    }

    // ---- sensing --------------------------------------------------------------------------------

    fun touching(option: Any?): Boolean {
        if (!visible) return false
        return when (val s = Cast.toString(option)) {
            "_mouse_" -> touchingPoint(rt.mouseX, rt.mouseY)
            "_edge_" -> {
                val b = bounds() ?: return false
                b.left < -Runtime.STAGE_WIDTH / 2.0 || b.right > Runtime.STAGE_WIDTH / 2.0 ||
                    b.top > Runtime.STAGE_HEIGHT / 2.0 || b.bottom < -Runtime.STAGE_HEIGHT / 2.0
            }
            else -> touchingSprite(s)
        }
    }

    fun touchingColor(color: Any?): Boolean = sensesColor(Cast.toArgb(color) and 0xFFFFFF, ColorSensing.NO_MASK)

    /** `color [mask] is touching [color]?`: only this sprite's pixels of the mask color count. */
    fun colorTouchingColor(mask: Any?, color: Any?): Boolean =
        sensesColor(Cast.toArgb(color) and 0xFFFFFF, Cast.toArgb(mask) and 0xFFFFFF)

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

    fun loudness(): Double = rt.loudness()

    /** `set drag mode`: the mouse can drag a draggable sprite around the stage. */
    fun setDragMode(mode: String) {
        // The stage never becomes draggable, or its clicks would move to mouse up.
        if (!isStage) draggable = mode == "draggable"
    }

    fun username(): String = rt.username

    fun online(): Boolean = rt.online()

    /** `loud?`, a hidden legacy block. */
    fun loud(): Boolean = rt.loudness() > 10.0

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

    /** The index of the sound a menu value or expression picks, or -1 for none. */
    private fun soundIndex(option: Any?): Int {
        if (sounds.isEmpty()) return -1
        if (option is Double) {
            val n = sounds.size
            return (((Math.round(option - 1) % n) + n) % n).toInt()
        }

        val s = Cast.toString(option)
        val named = sounds.indexOfFirst { it.name == s }
        if (named >= 0) return named
        val n = Cast.toNumberOrNaN(s)
        if (!n.isNaN() && !Cast.isWhiteSpace(s)) return soundIndex(n)
        return -1
    }

    fun playSound(option: Any?) {
        val i = soundIndex(option)
        if (i >= 0) rt.sounds.play(this, i, false)
    }

    /**
     * Waits until the sound has played to its end, which the pitch effect makes sooner or later,
     * or until it stops or starts over: stop all sounds, or this sprite or a clone of it playing
     * the same sound again, ends the wait, as it does in Scratch. It waits a whole frame at a time
     * even without screen refresh, because scratch-vm's block returns a promise.
     */
    fun playSoundUntilDone(option: Any?) {
        val i = soundIndex(option)
        if (i < 0) return
        val player = rt.sounds.play(this, i, true) ?: return
        val stops = player.stops
        if (player.isFinished(stops, rt.nowMillis())) return
        rt.requestRedraw()
        do {
            rt.yieldFrame()
        } while (!player.isFinished(stops, rt.nowMillis()))
    }

    fun stopAllSounds() = rt.sounds.stopAll()

    /**
     * Volume and sound effect blocks wait for the next frame, even without screen refresh, as in
     * scratch-vm. NaN counts as 0, like Scratch's `Cast.toNumber`.
     */
    fun setVolume(v: Double) {
        volume = (if (v.isNaN()) 0.0 else v).coerceIn(0.0, 100.0)
        rt.sounds.update(this)
        rt.yieldFrame()
    }

    fun changeVolume(d: Double) = setVolume(volume + (if (d.isNaN()) 0.0 else d))

    fun getVolume(): Double = volume

    fun setSoundEffect(effect: String, value: Double) {
        val name = effect.lowercase()
        if (soundEffect(name) != null) {
            setSoundEffectNamed(name, if (value.isNaN()) 0.0 else value)
        } else {
            unknownSoundEffect(effect)
        }
    }

    fun changeSoundEffect(effect: String, delta: Double) {
        val name = effect.lowercase()
        val current = soundEffect(name)
        if (current != null) {
            setSoundEffectNamed(name, current + (if (delta.isNaN()) 0.0 else delta))
        } else {
            unknownSoundEffect(effect)
        }
    }

    /** The value of the sound effect [name], which is already lower case, or null for an unknown effect. */
    private fun soundEffect(name: String): Double? = when (name) {
        "pitch" -> pitch
        "pan" -> pan
        else -> null
    }

    /** Sets the known sound effect [name], which is already lower case. */
    private fun setSoundEffectNamed(name: String, value: Double) {
        if (name == "pitch") pitch = value.coerceIn(-360.0, 360.0) else pan = value.coerceIn(-100.0, 100.0)
        rt.sounds.update(this)
        rt.yieldFrame()
    }

    private fun unknownSoundEffect(effect: String) = Log.warn("Unknown sound effect '$effect'; it does nothing.")

    fun clearSoundEffects() {
        pitch = 0.0
        pan = 0.0
        rt.sounds.update(this)
    }

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
        val img = drawnImage() ?: return
        val at = drawnTransform() ?: return
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

        /** 2 to the 52nd: from here up, a double has no fractional part. */
        private const val TWO_POW_52 = 4503599627370496.0
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
