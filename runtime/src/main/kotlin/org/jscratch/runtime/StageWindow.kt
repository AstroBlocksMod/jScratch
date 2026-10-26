package org.jscratch.runtime

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Path2D
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JToolBar
import javax.swing.SwingUtilities

/** The Swing window with a green flag, a stop button and the stage. */
class StageWindow(private val rt: Runtime) {
    private val frame = JFrame("jScratch")
    private val panel = StagePanel()

    @Volatile private var snapshot: FrameSnapshot? = null

    @Volatile var isAsking: Boolean = false
        private set

    fun open() {
        SwingUtilities.invokeAndWait {
            val toolbar = JToolBar()
            toolbar.isFloatable = false
            val flag = JButton(FlagIcon())
            flag.toolTipText = "Green flag"
            flag.isFocusable = false
            flag.addActionListener { rt.postGreenFlag() }
            val stop = JButton(StopIcon())
            stop.toolTipText = "Stop"
            stop.isFocusable = false
            stop.addActionListener { rt.postStop() }
            toolbar.add(flag)
            toolbar.add(stop)
            frame.layout = BorderLayout()
            frame.add(toolbar, BorderLayout.NORTH)
            frame.add(panel, BorderLayout.CENTER)
            frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
            frame.addWindowListener(
                object : WindowAdapter() {
                    override fun windowClosing(e: WindowEvent) {
                        rt.running = false
                        frame.dispose()
                    }
                },
            )
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(
                KeyEventDispatcher { e ->
                    if (isAsking) return@KeyEventDispatcher false
                    val key = Keys.fromEvent(e) ?: return@KeyEventDispatcher false
                    when (e.id) {
                        KeyEvent.KEY_PRESSED -> rt.postKeyDown(key)
                        KeyEvent.KEY_RELEASED -> rt.postKeyUp(key)
                    }
                    false
                },
            )
            frame.pack()
            frame.setLocationRelativeTo(null)
            frame.isVisible = true
            panel.requestFocusInWindow()
        }
    }

    /** Called by the scheduler after each frame: snapshots the scene and asks Swing to repaint. */
    fun present() {
        snapshot = StageRenderer.snapshot(rt)
        panel.repaint()
    }

    fun ask(target: Target?, question: String, onAnswer: (String) -> Unit) {
        isAsking = true
        SwingUtilities.invokeLater {
            val title = if (target == null || target.isStage) "Question" else target.name
            val a = JOptionPane.showInputDialog(frame, question, title, JOptionPane.QUESTION_MESSAGE)
            isAsking = false
            onAnswer(a ?: "")
        }
    }

    fun cancelAsk() {
        isAsking = false
    }

    private inner class StagePanel : JPanel() {
        private var scale = 1.0
        private var offsetX = 0.0
        private var offsetY = 0.0

        /** The monitor the mouse went down on. Presses on monitors never reach the project, as in scratch-gui. */
        private var pressed: MonitorItem? = null

        /** Whether that press landed on a slider's range input, so dragging moves the slider. */
        private var sliding = false

        init {
            preferredSize = Dimension(Runtime.STAGE_WIDTH, Runtime.STAGE_HEIGHT)
            background = Color(0x30, 0x30, 0x30)
            isFocusable = true
            val mouse =
                object : MouseAdapter() {
                    override fun mouseMoved(e: MouseEvent) = post(e, null)

                    override fun mouseDragged(e: MouseEvent) {
                        if (sliding) pressed?.let { slide(it, e) }
                        post(e, null)
                    }

                    override fun mousePressed(e: MouseEvent) {
                        requestFocusInWindow()
                        val hit = monitorAt(e)
                        if (hit != null) {
                            pressed = hit
                            sliding = hit.onSlider(stageX(e), stageY(e))
                            if (sliding) slide(hit, e)
                            post(e, null)
                        } else {
                            post(e, true)
                        }
                    }

                    override fun mouseReleased(e: MouseEvent) {
                        if (pressed != null) {
                            pressed = null
                            sliding = false
                            post(e, null)
                        } else {
                            post(e, false)
                        }
                    }

                    override fun mouseWheelMoved(e: MouseWheelEvent) {
                        val hit = monitorAt(e) ?: return
                        if (hit.mode == Monitor.Mode.LIST) {
                            rt.postMonitorScroll(hit.index, e.preciseWheelRotation * MonitorView.ROW)
                        }
                    }

                    private fun slide(item: MonitorItem, e: MouseEvent) {
                        rt.postMonitorValue(item.index, item.sliderValueAt(stageX(e)))
                    }

                    private fun monitorAt(e: MouseEvent): MonitorItem? =
                        snapshot?.monitors?.lastOrNull { it.contains(stageX(e), stageY(e)) }

                    /** The mouse position in stage pixels from the top left, the monitors' coordinates. */
                    private fun stageX(e: MouseEvent): Double = (e.x - offsetX) / scale

                    private fun stageY(e: MouseEvent): Double = (e.y - offsetY) / scale

                    private fun post(e: MouseEvent, down: Boolean?) {
                        val sx = (e.x - offsetX) / scale - Runtime.STAGE_WIDTH / 2.0
                        val sy = Runtime.STAGE_HEIGHT / 2.0 - (e.y - offsetY) / scale
                        rt.postMouse(sx, sy, down)
                    }
                }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
            addMouseWheelListener(mouse)
        }

        override fun paintComponent(g0: Graphics) {
            super.paintComponent(g0)
            val g = g0.create() as Graphics2D
            scale = Math.min(width / Runtime.STAGE_WIDTH.toDouble(), height / Runtime.STAGE_HEIGHT.toDouble())
            offsetX = (width - Runtime.STAGE_WIDTH * scale) / 2
            offsetY = (height - Runtime.STAGE_HEIGHT * scale) / 2
            g.translate(offsetX, offsetY)
            g.scale(scale, scale)
            StageRenderer.render(g, snapshot, rt.pen)
            g.dispose()
        }
    }

    private class FlagIcon : Icon {
        override fun getIconWidth() = 24

        override fun getIconHeight() = 24

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.translate(x, y)
            g2.color = Color(0x45, 0x99, 0x3D)
            val flag = Path2D.Double()
            flag.moveTo(6.0, 3.0)
            flag.lineTo(6.0, 21.0)
            flag.lineTo(8.0, 21.0)
            flag.lineTo(8.0, 14.0)
            flag.lineTo(20.0, 14.0)
            flag.lineTo(17.0, 8.5)
            flag.lineTo(20.0, 3.0)
            flag.closePath()
            g2.fill(flag)
            g2.dispose()
        }
    }

    private class StopIcon : Icon {
        override fun getIconWidth() = 24

        override fun getIconHeight() = 24

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.translate(x, y)
            g2.color = Color(0xEC, 0x5D, 0x5D)
            val oct = Path2D.Double()
            val pts =
                doubleArrayOf(9.0, 3.0, 15.0, 3.0, 21.0, 9.0, 21.0, 15.0, 15.0, 21.0, 9.0, 21.0, 3.0, 15.0, 3.0, 9.0)
            oct.moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) {
                oct.lineTo(pts[i], pts[i + 1])
                i += 2
            }
            oct.closePath()
            g2.fill(oct)
            g2.dispose()
        }
    }
}
