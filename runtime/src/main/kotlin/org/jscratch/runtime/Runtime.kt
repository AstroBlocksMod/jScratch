package org.jscratch.runtime

import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The project runtime: owns the stage, the sprites, the script scheduler, the
 * input state and the window. The generated `Main` class builds one of these,
 * adds the targets and calls [run].
 *
 * Headless mode (`-Djscratch.headless=<frames>`) runs up to the given number of
 * frames with a virtual clock and no window, printing every `say` to stdout. It
 * stops early when no script is left running and no edge-activated hat can still
 * fire. With `-Djscratch.screenshot=<file.png>`
 * it also saves the final frame, and `-Djscratch.timing=true` prints the frame
 * count and wall time to stderr. The tests and the benchmarks rely on these.
 */
class Runtime {
    lateinit var stage: Stage

    /** Sprites in draw order, back to front. Includes clones. */
    val sprites: ArrayList<Sprite> = ArrayList()
    private val originals: ArrayList<Sprite> = ArrayList()

    val threads: ArrayList<ScriptThread> = ArrayList()
    val assets = Assets()
    val pen = PenLayer()

    /** Every monitor of the project, in the compiler's order, which is also the order they are placed in. */
    val monitors: ArrayList<Monitor> = ArrayList()

    val headlessFrames: Int = Integer.getInteger("jscratch.headless", -1)
    val headless: Boolean get() = headlessFrames >= 0
    val sounds = Sounds(this)
    private val microphone = Microphone()
    private var loudness: Double = -1.0
    private var loudnessFrame: Long = -1

    @Volatile var mouseX: Double = 0.0

    @Volatile var mouseY: Double = 0.0

    @Volatile var mouseDown: Boolean = false

    /** Where the mouse went down, while the button is held over the stage. NaN when it is up. */
    private var pressX: Double = Double.NaN
    private var pressY: Double = Double.NaN
    private var pressNanos: Long = 0

    /** Whether the press has been held long enough to try to start a drag without moving. */
    private var pressTimedOut: Boolean = false

    /** The mouse position the last drag check saw, to tell whether the mouse moved since. */
    private var seenX: Double = 0.0
    private var seenY: Double = 0.0

    /** Window pixels per stage unit, which the window sets as it resizes. The drag threshold is in window pixels. */
    @Volatile var windowScale: Double = 1.0

    /** The sprite the mouse is dragging, and the sprite's position relative to the mouse. */
    private var dragged: Sprite? = null
    private var dragOffsetX: Double = 0.0
    private var dragOffsetY: Double = 0.0

    /** What `username` reports. Projects run outside the Scratch website, so nobody is signed in unless this is set. */
    val username: String = System.getProperty("jscratch.username", "")

    /** The counter of the hidden legacy counter blocks. Like scratch-vm's, only loading the project resets it. */
    var counter: Double = 0.0

    private var online: Boolean = true
    private var onlineCheckedNanos: Long = 0
    private var onlineChecked: Boolean = false

    private val keysDown: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val events = ConcurrentLinkedQueue<Event>()

    /** Whether any target has an edge-activated hat. Clones have the hats of their sprite, so this never changes. */
    private var hasEdgeHats = false

    @Volatile var redrawRequested: Boolean = false
    private var frameIndex: Long = 0

    /** How many frames have run. */
    val frameCount: Long get() = frameIndex
    private var timerStartMs: Double = 0.0

    @Volatile var answer: String = ""

    @Volatile private var pendingQuestion: String? = null

    @Volatile private var questionTarget: Target? = null

    @Volatile private var answered: Boolean = true

    @Volatile var running: Boolean = true
    var window: StageWindow? = null
        private set

    @Volatile var frameListener: ((Runtime) -> Unit)? = null

    sealed class Event {
        class KeyDown(val key: String) : Event()

        class MouseDown(val x: Double, val y: Double) : Event()

        class MouseUp(val x: Double, val y: Double) : Event()

        object GreenFlag : Event()

        object StopAll : Event()

        class MonitorValue(val index: Int, val value: Double) : Event()

        class MonitorScroll(val index: Int, val delta: Double) : Event()
    }

    // ---- setup ----------------------------------------------------------------------------------

    fun addTarget(t: Target) {
        if (t.hats.any { it.kind == HAT_EDGE }) hasEdgeHats = true
        if (t is Stage) {
            stage = t
        } else if (t is Sprite) {
            originals.add(t)
            sprites.add(t)
        }
    }

    fun run() {
        sprites.sortBy { it.layerOrder }
        for (m in MonitorData.load()) {
            m.target = if (m.spriteName.isEmpty()) stage else spriteNamed(m.spriteName) ?: stage
            monitors.add(m)
        }
        for (t in allTargets()) t.costumes.forEach { assets.load(it) }
        sounds.loadAll(allTargets().flatMap { it.sounds })
        if (headless) {
            val start = System.nanoTime()
            resetTimer()
            startHats(HAT_FLAG, "", null)
            // Stop early once every script has finished: nothing but an edge-activated hat can start a new one
            // without input.
            while (frameIndex < headlessFrames && (threads.isNotEmpty() || edgeHatWaiting())) {
                frame()
                frameIndex++
            }
            if (java.lang.Boolean.getBoolean("jscratch.timing")) {
                val ms = (System.nanoTime() - start) / 1_000_000.0
                System.err.println("jscratch: $frameIndex frames in $ms ms")
            }
            System.getProperty("jscratch.screenshot")?.let { path ->
                val img = StageRenderer.toImage(StageRenderer.snapshot(this), pen, 2)
                javax.imageio.ImageIO.write(img, "png", java.io.File(path))
            }
            stopAllInternal()
            return
        }

        val w = StageWindow(this)
        window = w
        w.open()
        events.add(Event.GreenFlag)
        val frameNanos = 1_000_000_000L / FPS
        var next = System.nanoTime()
        while (running) {
            frame()
            frameIndex++
            w.present()
            next += frameNanos
            val sleep = next - System.nanoTime()
            if (sleep > 0) {
                Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
            } else {
                next = System.nanoTime()
            }
        }
        sounds.stopAll()
    }

    fun allTargets(): List<Target> {
        val all = ArrayList<Target>(sprites.size + 1)
        all.add(stage)
        all.addAll(sprites)
        return all
    }

    /** Milliseconds on the runtime clock. Virtual in headless mode so waits are deterministic. */
    fun nowMillis(): Double = if (headless) frameIndex * 1000.0 / FPS else System.nanoTime() / 1_000_000.0

    // ---- scheduler ------------------------------------------------------------------------------

    /** Runs one frame: processes input events, then steps every script until it yields. */
    fun frame() {
        processEvents()
        startEdgeHats()
        redrawRequested = false
        val start = System.nanoTime()
        for (t in threads) t.tickYield = false
        do {
            var active = 0
            var i = 0
            while (i < threads.size) {
                val t = threads[i]
                i++
                if (t.done) continue
                if (t.tickYield && !t.killed) continue
                t.step()
                if (!t.done && !t.tickYield) active++
            }
            threads.removeAll { it.done }
        } while (active > 0 && !redrawRequested && System.nanoTime() - start < WORK_TIME_NANOS)
        frameListener?.invoke(this)
    }

    private fun processEvents() {
        while (true) {
            val e = events.poll() ?: break
            when (e) {
                is Event.GreenFlag -> {
                    stopAllInternal()
                    // The green flag restarts the timer, as in scratch-vm, so loading time never counts.
                    resetTimer()
                    for (t in allTargets()) t.clearEdgeValues()
                    startHats(HAT_FLAG, "", null)
                }
                is Event.StopAll -> stopAllInternal()
                is Event.KeyDown -> startHats(HAT_KEY, e.key, null)
                is Event.MonitorValue -> monitors.getOrNull(e.index)?.let { it.target.setMonitorSlot(it.slot, e.value) }
                // Layout clamps the far end, since only it knows how many rows fit.
                is Event.MonitorScroll -> monitors.getOrNull(e.index)?.let {
                    it.scroll = Math.max(0.0, it.scroll + e.delta)
                }
                is Event.MouseDown -> {
                    pressX = e.x
                    pressY = e.y
                    pressNanos = System.nanoTime()
                    pressTimedOut = false
                    seenX = e.x
                    seenY = e.y
                    // A draggable target starts its click hats when the mouse goes up instead, if it was not dragged.
                    val hit = pick(e.x, e.y)
                    if (!hit.draggable) startHats(HAT_CLICKED, "", hit)
                }
                is Event.MouseUp -> mouseUp(e.x, e.y)
            }
        }
        updateDrag()

        val q = pendingQuestion
        if (q != null && !answered && window != null && !window!!.isAsking) {
            window!!.ask(questionTarget, q) { a ->
                answer = a
                answered = true
                pendingQuestion = null
            }
        }
    }

    // ---- dragging -------------------------------------------------------------------------------
    //
    // In Scratch, scratch-gui drags sprites: a press on a draggable sprite becomes a drag once the mouse
    // moves more than 3 pixels or stays down for 400 ms. The sprite jumps to the front and follows the
    // mouse, and a press that became a drag is not a click.

    /** The topmost sprite with an opaque pixel under the stage point, or the stage. Fully ghosted sprites are skipped. */
    private fun pick(x: Double, y: Double): Target =
        sprites.asReversed().firstOrNull { it.ghost < 100.0 && it.touchingPoint(x, y) } ?: stage

    /** Starts or continues a drag, once a frame while the mouse is held. */
    private fun updateDrag() {
        if (pressX.isNaN()) return
        // scratch-gui tries to start a drag on every mouse move past the threshold, not while the mouse rests.
        val moved = mouseX != seenX || mouseY != seenY
        seenX = mouseX
        seenY = mouseY
        if (dragged == null) {
            if (moved && movedFromPress(mouseX, mouseY)) {
                startDrag()
            } else if (!pressTimedOut && System.nanoTime() - pressNanos >= DRAG_DELAY_NANOS) {
                // scratch-gui tries this once, so a sprite passing under a mouse held still is not picked up.
                pressTimedOut = true
                startDrag()
            }
        }
        dragged?.dragTo(mouseX + dragOffsetX, mouseY + dragOffsetY)
    }

    private fun movedFromPress(x: Double, y: Double): Boolean =
        Math.hypot(x - pressX, y - pressY) * windowScale > DRAG_THRESHOLD

    /** Picks up the sprite where the mouse went down, if it is draggable. */
    private fun startDrag() {
        val hit = pick(pressX, pressY) as? Sprite ?: return
        if (!hit.draggable) return
        moveToLayer(hit, true)
        hit.dragging = true
        dragged = hit
        dragOffsetX = hit.x - pressX
        dragOffsetY = hit.y - pressY
    }

    private fun mouseUp(x: Double, y: Double) {
        if (pressX.isNaN()) return
        // A press released this far away became a drag on the way, even if no frame saw the mouse move.
        if (dragged == null && movedFromPress(x, y)) startDrag()
        pressX = Double.NaN
        pressY = Double.NaN
        val drag = dragged
        if (drag != null) {
            drag.dragTo(x + dragOffsetX, y + dragOffsetY)
            drag.dragging = false
            dragged = null
            return
        }
        val hit = pick(x, y)
        if (hit.draggable) startHats(HAT_CLICKED, "", hit)
    }

    /** `show variable`, `hide variable`, `show list` and `hide list`. */
    fun setMonitorVisible(index: Int, visible: Boolean) {
        monitors.getOrNull(index)?.visible = visible
    }

    fun requestRedraw() {
        redrawRequested = true
    }

    /**
     * Starts every script whose hat matches. A script that is already running for
     * the same target is restarted, which is what Scratch does.
     */
    fun startHats(kind: Int, arg: String, only: Target?): List<ScriptThread> {
        val started = ArrayList<ScriptThread>()
        val targets: List<Target> = if (only != null) listOf(only) else allTargets()
        for (t in targets) {
            for (h in t.hats) {
                if (h.kind != kind) continue
                val matches =
                    when (kind) {
                        HAT_KEY -> h.arg == "any" || h.arg == arg
                        HAT_BROADCAST, HAT_BACKDROP -> h.arg.equals(arg, ignoreCase = true)
                        else -> true
                    }
                if (!matches) continue
                started.add(startScript(t, h.scriptId))
            }
        }
        return started
    }

    /**
     * Queues a thread for every edge-activated hat whose script is not running, as scratch-vm does at the
     * start of every frame. Each thread checks its hat's condition when it first steps and only runs the
     * script when the condition has just turned true.
     */
    private fun startEdgeHats() {
        if (!hasEdgeHats) return
        startEdgeHats(stage)
        for (s in sprites) startEdgeHats(s)
    }

    private fun startEdgeHats(t: Target) {
        for (h in t.hats) {
            if (h.kind != HAT_EDGE) continue
            // Nothing else starts these scripts, so the hat's own last thread tells whether its script runs.
            val running = h.thread
            if (running != null && !running.done && !running.killed) continue
            val thread = ScriptThread(this, t, h.scriptId, edgeHat = true)
            h.thread = thread
            threads.add(thread)
        }
    }

    /** Whether some edge-activated hat could still fire in a headless run with no script running. */
    private fun edgeHatWaiting(): Boolean = hasEdgeHats && allTargets().any { it.edgeHatWaiting() }

    private fun startScript(target: Target, scriptId: Int): ScriptThread {
        for (t in threads) {
            if (t.target === target && t.scriptId == scriptId && !t.done) t.killed = true
        }

        val thread = ScriptThread(this, target, scriptId)
        threads.add(thread)
        return thread
    }

    /** Called from inside a script: lets the other scripts run, then continues within the same frame if time allows. */
    fun yieldLoop() {
        ScriptThread.current()?.yieldNow(false)
    }

    /** Called from inside a script: waits for the next frame. */
    fun yieldTick() {
        ScriptThread.current()?.yieldNow(true)
    }

    /**
     * Called from inside a script: waits for the next frame even in "run without screen refresh",
     * like a block in scratch-vm that returns a promise.
     */
    fun yieldFrame() {
        ScriptThread.current()?.yieldFrame()
    }

    fun wait(seconds: Double) {
        val durationMs = Math.max(0.0, seconds * 1000.0)
        val end = nowMillis() + durationMs
        requestRedraw()
        do {
            yieldTick()
        } while (nowMillis() < end)
    }

    fun waitForThreads(started: List<ScriptThread>) {
        while (started.any { !it.done }) yieldLoop()
    }

    fun broadcast(name: String) {
        startHats(HAT_BROADCAST, name, null)
    }

    fun broadcastAndWait(name: String) {
        waitForThreads(startHats(HAT_BROADCAST, name, null))
    }

    /** `stop all` from a script: kills every script including the caller. */
    fun stopAll() {
        stopAllInternal()
        if (ScriptThread.current() != null) throw StopScript()
    }

    private fun stopAllInternal() {
        for (t in threads) t.killed = true
        sounds.stopAll()
        for (t in allTargets()) {
            t.clearSay()
            // scratch-vm clears graphic and sound effects on stop, and so on every green flag. The
            // sounds are stopped already, so there is nothing to apply the cleared effects to.
            t.clearEffects()
            t.pitch = 0.0
            t.pan = 0.0
        }
        sprites.removeAll { it.isClone }
        pendingQuestion = null
        answered = true
        window?.cancelAsk()
    }

    fun stopOtherScripts(target: Target) {
        sounds.stopWaiting(target)
        val me = ScriptThread.current()
        for (t in threads) {
            if (t.target === target && t !== me) t.killed = true
        }
    }

    // ---- clones and layers ----------------------------------------------------------------------

    fun spriteNamed(name: String): Sprite? = originals.firstOrNull { it.name == name }

    fun createClone(original: Target): Target? {
        if (sprites.count { it.isClone } >= MAX_CLONES) return null
        val clone = original.newInstance() as Sprite
        clone.isClone = true
        clone.copyFrom(original)
        clone.costumes.forEach { assets.load(it) }
        val idx = sprites.indexOf(original)
        sprites.add(if (idx < 0) sprites.size else idx, clone)
        startHats(HAT_CLONE_START, "", clone)
        return clone
    }

    fun removeClone(clone: Target) {
        sprites.remove(clone)
        sounds.remove(clone)
        val me = ScriptThread.current()
        for (t in threads) {
            if (t.target === clone && t !== me) t.killed = true
        }
        requestRedraw()
    }

    fun moveToLayer(t: Target, front: Boolean) {
        if (!sprites.remove(t)) return
        if (front) sprites.add(t as Sprite) else sprites.add(0, t as Sprite)
        requestRedraw()
    }

    fun moveLayers(t: Target, delta: Int) {
        val idx = sprites.indexOf(t)
        if (idx < 0) return
        sprites.removeAt(idx)
        val target = (idx + delta).coerceIn(0, sprites.size)
        sprites.add(target, t as Sprite)
        requestRedraw()
    }

    // ---- sensing --------------------------------------------------------------------------------

    fun timer(): Double = (nowMillis() - timerStartMs) / 1000.0

    fun resetTimer() {
        timerStartMs = nowMillis()
    }

    /**
     * `online?`: whether the computer has a network connection, like a browser's `navigator.onLine`,
     * which the Scratch block reports. That means some network interface other than loopback is up
     * with an address that is not link-local, since macOS keeps link-local ones like AirDrop's up even
     * offline. Listing the interfaces takes a moment, so the answer is kept for a second.
     */
    fun online(): Boolean {
        val now = System.nanoTime()
        if (!onlineChecked || now - onlineCheckedNanos >= 1_000_000_000L) {
            onlineChecked = true
            onlineCheckedNanos = now
            online =
                try {
                    NetworkInterface.networkInterfaces().anyMatch { nif ->
                        nif.isUp && !nif.isLoopback &&
                            nif.inetAddresses().anyMatch { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    }
                } catch (_: Exception) {
                    // Like TurboWarp outside a browser: when there is no way to tell, assume a connection.
                    true
                }
        }
        return online
    }

    /** Microphone loudness from 0 to 100, measured once a frame like scratch-vm does, or -1 without a microphone. */
    fun loudness(): Double {
        if (headless) return -1.0
        if (loudnessFrame != frameIndex) {
            loudnessFrame = frameIndex
            loudness = microphone.loudness()
        }
        return loudness
    }

    fun keyPressed(key: Any?): Boolean {
        val k = Keys.normalize(key)
        if (k == "any") return keysDown.isNotEmpty()
        return keysDown.contains(k)
    }

    fun ask(target: Target, question: String) {
        if (headless) {
            println("${target.name} asks: $question")
            answer = ""
            return
        }
        pendingQuestion = question
        questionTarget = target
        answered = false
        while (!answered) yieldTick()
    }

    /** Hook for `say`: headless runs print the bubble text so tests can observe behavior. */
    fun onSay(target: Target, text: String, isThink: Boolean) {
        if (headless && text.isNotEmpty()) {
            println("${target.name} ${if (isThink) "thinks" else "says"}: $text")
        }
    }

    fun unsupported(opcode: String) {
        Log.warn("Block '$opcode' is not supported yet; it does nothing.")
    }

    // ---- input, called from the UI thread -------------------------------------------------------

    fun postKeyDown(key: String) {
        if (keysDown.add(key)) events.add(Event.KeyDown(key))
    }

    fun postKeyUp(key: String) {
        keysDown.remove(key)
    }

    fun postMouse(x: Double, y: Double, down: Boolean?) {
        mouseX = x.coerceIn(-STAGE_WIDTH / 2.0, STAGE_WIDTH / 2.0)
        mouseY = y.coerceIn(-STAGE_HEIGHT / 2.0, STAGE_HEIGHT / 2.0)
        if (down != null) {
            mouseDown = down
            events.add(if (down) Event.MouseDown(mouseX, mouseY) else Event.MouseUp(mouseX, mouseY))
        }
    }

    /** A slider monitor was dragged to [value]. */
    fun postMonitorValue(index: Int, value: Double) {
        events.add(Event.MonitorValue(index, value))
    }

    /** The mouse wheel turned over a list monitor by [delta] pixels. */
    fun postMonitorScroll(index: Int, delta: Double) {
        events.add(Event.MonitorScroll(index, delta))
    }

    fun postGreenFlag() {
        events.add(Event.GreenFlag)
    }

    fun postStop() {
        events.add(Event.StopAll)
    }

    companion object {
        const val STAGE_WIDTH = 480
        const val STAGE_HEIGHT = 360
        const val FPS = 30
        const val MAX_CLONES = 300
        private const val WORK_TIME_NANOS = 750L * 1_000_000L / FPS

        /** How far the mouse moves, in window pixels, before a press on a draggable sprite becomes a drag. */
        private const val DRAG_THRESHOLD = 3.0

        /** How long a press stays still before it becomes a drag anyway. */
        private const val DRAG_DELAY_NANOS = 400_000_000L

        const val HAT_FLAG = 0
        const val HAT_KEY = 1
        const val HAT_CLICKED = 2
        const val HAT_BROADCAST = 3
        const val HAT_BACKDROP = 4
        const val HAT_CLONE_START = 5
        const val HAT_EDGE = 6
    }
}
