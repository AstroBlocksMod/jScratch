package org.jscratch.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The project runtime: owns the stage, the sprites, the script scheduler, the
 * input state and the window. The generated `Main` class builds one of these,
 * adds the targets and calls [run].
 *
 * Headless mode (`-Djscratch.headless=<frames>`) runs up to the given number of
 * frames with a virtual clock and no window, printing every `say` to stdout. It
 * stops early when no script is left running. With `-Djscratch.screenshot=<file.png>`
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

    val headlessFrames: Int = Integer.getInteger("jscratch.headless", -1)
    val headless: Boolean get() = headlessFrames >= 0
    val sounds = Sounds(enabled = !headless)

    @Volatile var mouseX: Double = 0.0

    @Volatile var mouseY: Double = 0.0

    @Volatile var mouseDown: Boolean = false
    private val keysDown: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val events = ConcurrentLinkedQueue<Event>()

    @Volatile var redrawRequested: Boolean = false
    private var frameIndex: Long = 0
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

        class Click(val x: Double, val y: Double) : Event()

        object GreenFlag : Event()

        object StopAll : Event()
    }

    // ---- setup ----------------------------------------------------------------------------------

    fun addTarget(t: Target) {
        if (t is Stage) {
            stage = t
        } else if (t is Sprite) {
            originals.add(t)
            sprites.add(t)
        }
    }

    fun run() {
        sprites.sortBy { it.layerOrder }
        for (t in allTargets()) t.costumes.forEach { assets.load(it) }
        sounds.loadAll(allTargets().flatMap { it.sounds })
        if (headless) {
            val start = System.nanoTime()
            resetTimer()
            startHats(HAT_FLAG, "", null)
            // Stop early once every script has finished: nothing can start a new one without input.
            while (frameIndex < headlessFrames && threads.isNotEmpty()) {
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
                    startHats(HAT_FLAG, "", null)
                }
                is Event.StopAll -> stopAllInternal()
                is Event.KeyDown -> startHats(HAT_KEY, e.key, null)
                is Event.Click -> {
                    val hit = sprites.asReversed().firstOrNull { it.ghost < 100.0 && it.touchingPoint(e.x, e.y) }
                    if (hit != null) startHats(HAT_CLICKED, "", hit) else startHats(HAT_CLICKED, "", stage)
                }
            }
        }

        val q = pendingQuestion
        if (q != null && !answered && window != null && !window!!.isAsking) {
            window!!.ask(questionTarget, q) { a ->
                answer = a
                answered = true
                pendingQuestion = null
            }
        }
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
        for (t in allTargets()) {
            t.clearSay()
            // scratch-vm clears graphic effects on stop, and so on every green flag.
            t.clearEffects()
        }
        sprites.removeAll { it.isClone }
        sounds.stopAll()
        pendingQuestion = null
        answered = true
        window?.cancelAsk()
    }

    fun stopOtherScripts(target: Target) {
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
            if (down) events.add(Event.Click(mouseX, mouseY))
        }
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

        const val HAT_FLAG = 0
        const val HAT_KEY = 1
        const val HAT_CLICKED = 2
        const val HAT_BROADCAST = 3
        const val HAT_BACKDROP = 4
        const val HAT_CLONE_START = 5
    }
}
