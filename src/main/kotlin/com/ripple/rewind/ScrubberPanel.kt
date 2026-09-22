package com.ripple.rewind

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.ripple.engine.TraceSession
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets
import java.awt.Point
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.KeyStroke
import javax.swing.SwingConstants

/**
 * The Rewind transport: slider + play/pause + step + "pass 3 / 7".
 *
 * Deliberately non-focusable, top to bottom. The popup does not request focus and
 * no widget inside it accepts it, so the caret never leaves the editor while you
 * scrub. The keyboard shortcuts are therefore registered on the EDITOR's component
 * (scoped to the popup's lifetime) rather than on this panel, which is the only
 * way arrows can work while focus stays where the audience is looking.
 *
 * EDT only.
 */
class ScrubberPanel private constructor(
    private val controller: RewindController
) : JBPanel<ScrubberPanel>(BorderLayout(0, JBUI.scale(6))) {

    private val slider = JSlider(0, (controller.passCount - 1).coerceAtLeast(1), 0)
    private val passLabel = JBLabel("", SwingConstants.RIGHT)
    private val hintLabel = JBLabel("")
    private val playButton: JButton
    private val stepBackButton: JButton
    private val stepForwardButton: JButton

    /** Guards the slider -> controller -> slider feedback loop. */
    private var syncing = false

    /**
     * True while a user-initiated slider change is being handled. The controller
     * fires listeners from inside `pause()` before the new pass is applied, and
     * writing the old pass back into the slider mid-drag would fight the thumb.
     */
    private var draggingSlider = false

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8, 10, 8, 10)

        stepBackButton = iconButton(AllIcons.Actions.Play_back, "Previous pass (Left)") { controller.stepBack() }
        playButton = iconButton(AllIcons.Actions.Resume, "Play / Pause") { controller.togglePlay() }
        stepForwardButton = iconButton(AllIcons.Actions.Play_forward, "Next pass (Right)") { controller.stepForward() }

        val transport = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0))
        transport.isOpaque = false
        transport.add(stepBackButton)
        transport.add(playButton)
        transport.add(stepForwardButton)

        slider.isOpaque = false
        slider.isFocusable = false
        slider.snapToTicks = true
        slider.paintTicks = controller.passCount in 2..24
        slider.majorTickSpacing = if (controller.passCount <= 12) 1 else (controller.passCount / 10).coerceAtLeast(1)
        slider.minorTickSpacing = 1
        slider.toolTipText = "Drag to scrub through the recording"
        slider.addChangeListener {
            if (syncing) return@addChangeListener
            // Read the target BEFORE pausing: pause() notifies listeners, which would
            // otherwise overwrite slider.value with the pass we are moving away from.
            val target = slider.value
            if (target == controller.currentPass) return@addChangeListener
            draggingSlider = true
            try {
                controller.pause()
                controller.goTo(target)
            } finally {
                draggingSlider = false
            }
        }

        passLabel.font = JBFont.label().asBold()
        passLabel.preferredSize = Dimension(JBUI.scale(96), passLabel.preferredSize.height)

        hintLabel.font = JBFont.small()
        hintLabel.foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)

        val row = JPanel(BorderLayout(JBUI.scale(8), 0))
        row.isOpaque = false
        row.add(transport, BorderLayout.WEST)
        row.add(slider, BorderLayout.CENTER)
        row.add(passLabel, BorderLayout.EAST)

        add(row, BorderLayout.CENTER)
        add(hintLabel, BorderLayout.SOUTH)

        preferredSize = Dimension(JBUI.scale(480), preferredSize.height.coerceAtLeast(JBUI.scale(68)))

        if (!controller.isScrubbable) {
            slider.isEnabled = false
            stepBackButton.isEnabled = false
            playButton.isEnabled = false
            stepForwardButton.isEnabled = false
        }

        bindLocalKeys()
        controller.addListener { pass, playing -> onStateChanged(pass, playing) }
        onStateChanged(controller.currentPass, controller.isPlaying)
    }

    private fun onStateChanged(pass: Int, playing: Boolean) {
        if (controller.isScrubbable && !draggingSlider && slider.value != pass) {
            syncing = true
            try {
                slider.value = pass
            } finally {
                syncing = false
            }
        }
        playButton.icon = if (playing) AllIcons.Actions.Pause else AllIcons.Actions.Resume
        playButton.toolTipText = if (playing) "Pause" else "Play"
        passLabel.text = "pass ${pass + 1} / ${controller.passCount}"
        if (controller.isScrubbable) {
            stepBackButton.isEnabled = pass > 0
            stepForwardButton.isEnabled = pass < controller.passCount - 1
        }
        hintLabel.text = hintText()
    }

    private fun hintText(): String {
        val session = controller.session
        val shortName = session.methodQualifiedName.substringAfterLast('.')
        val capped = if (session.truncated) ", capped" else ""
        return if (!controller.isScrubbable) {
            "$shortName — only one pass recorded, nothing to scrub"
        } else {
            "$shortName — ${session.events.size} snapshots$capped   ·   ← → to step, Esc to close"
        }
    }

    private fun iconButton(icon: Icon, tooltip: String, onClick: () -> Unit): JButton {
        val button = JButton(icon)
        button.toolTipText = tooltip
        button.isFocusable = false
        button.isFocusPainted = false
        button.isContentAreaFilled = false
        button.isBorderPainted = false
        button.isOpaque = false
        button.margin = Insets(0, 0, 0, 0)
        button.border = JBUI.Borders.empty(3, 4)
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.addActionListener { onClick() }
        return button
    }

    /**
     * Fallback bindings for the case where the popup window does end up focused
     * (for instance after the user drags the popup by its header).
     */
    private fun bindLocalKeys() {
        val inputMap = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionMap = actionMap
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), KEY_BACK)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), KEY_FORWARD)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), KEY_PLAY)
        actionMap.put(KEY_BACK, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = controller.stepBack()
        })
        actionMap.put(KEY_FORWARD, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = controller.stepForward()
        })
        actionMap.put(KEY_PLAY, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = controller.togglePlay()
        })
    }

    private class TransportAction(
        private val enabled: () -> Boolean,
        private val body: () -> Unit
    ) : AnAction() {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            // Disabled means "let the editor have this key back" — so when there is
            // only one pass, Left/Right still move the caret normally.
            e.presentation.isEnabled = enabled()
        }

        override fun actionPerformed(e: AnActionEvent) {
            body()
        }
    }

    companion object {
        private const val KEY_BACK = "ripple.rewind.back"
        private const val KEY_FORWARD = "ripple.rewind.forward"
        private const val KEY_PLAY = "ripple.rewind.play"

        /**
         * Builds the controller, the panel and the popup, wires disposal both ways,
         * and shows the scrubber near the bottom of the editor's visible area.
         */
        fun open(project: Project, editor: Editor, session: TraceSession): JBPopup {
            ApplicationManager.getApplication().assertIsDispatchThread()

            val controller = RewindController(editor, session)
            val panel = ScrubberPanel(controller)

            val popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, null)
                .setProject(project)
                .setTitle("Ripple Rewind")
                .setRequestFocus(false)
                .setFocusable(false)
                .setMovable(true)
                .setResizable(false)
                .setCancelOnClickOutside(false)
                .setCancelOnWindowDeactivation(false)
                .setCancelOnOtherWindowOpen(false)
                .setShowBorder(true)
                .createPopup()

            popup.addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    controller.dispose()
                }
            })
            controller.addCloseHandler {
                if (!popup.isDisposed) popup.cancel()
            }

            registerEditorShortcuts(editor, popup, controller)

            controller.start()
            popup.show(anchor(editor))
            return popup
        }

        /**
         * Left / Right / Escape live on the editor component so they work while the
         * editor keeps focus. They are scoped to the popup, so closing it releases
         * them and the arrow keys go back to moving the caret.
         */
        private fun registerEditorShortcuts(editor: Editor, popup: JBPopup, controller: RewindController) {
            val component = editor.contentComponent
            val scrubbable = { controller.isScrubbable }

            TransportAction(scrubbable) { controller.stepBack() }.registerCustomShortcutSet(
                CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0)),
                component,
                popup
            )
            TransportAction(scrubbable) { controller.stepForward() }.registerCustomShortcutSet(
                CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)),
                component,
                popup
            )
            TransportAction({ true }) {
                if (!popup.isDisposed) popup.cancel()
            }.registerCustomShortcutSet(
                CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)),
                component,
                popup
            )
        }

        /** Bottom-centre of the visible editor area, like a video player's transport bar. */
        private fun anchor(editor: Editor): RelativePoint {
            val component = editor.contentComponent
            val visible = editor.scrollingModel.visibleArea
            val width = JBUI.scale(480)
            val x = visible.x + ((visible.width - width) / 2).coerceAtLeast(JBUI.scale(16))
            val y = (visible.y + visible.height - JBUI.scale(110)).coerceAtLeast(visible.y + JBUI.scale(8))
            return RelativePoint(component, Point(x, y))
        }
    }
}
