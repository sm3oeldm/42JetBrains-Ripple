package com.ripple.rewind

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.util.Disposer
import com.ripple.engine.TraceSession
import com.ripple.inlay.TraceTrailRenderer
import javax.swing.Timer

/**
 * Playback brain for the Rewind scrubber.
 *
 * Owns: the current pass, the auto-advance [Timer] (javax.swing.Timer, so every
 * tick lands on the EDT — never a background thread touching inlays), and the
 * lifecycle of the chips it paints.
 *
 * Knows nothing about Swing widgets: [ScrubberPanel] subscribes via
 * [addListener] and re-renders itself. That split is what lets the editor close
 * mid-scrub without taking the UI down with it.
 *
 * EDT only. Every public method asserts it.
 */
class RewindController(
    private val editor: Editor,
    val session: TraceSession
) : Disposable {

    /** Number of recorded passes. `visitIndex` is 0-based, so this is max + 1. */
    val passCount: Int = if (session.events.isEmpty()) 1 else RewindInlayRenderer.maxVisitIndex(session) + 1

    /** False when there is nothing to drag through: the UI disables itself instead of faking motion. */
    val isScrubbable: Boolean = session.events.isNotEmpty() && passCount > 1

    var currentPass: Int = 0
        private set

    var isPlaying: Boolean = false
        private set

    private val lifetime: Disposable = Disposer.newDisposable("Ripple Rewind")
    private val listeners = mutableListOf<(Int, Boolean) -> Unit>()
    private val closeHandlers = mutableListOf<() -> Unit>()

    private var disposed = false
    private var editorGone = false

    private val timer: Timer = Timer(STEP_DELAY_MS) { onTick() }

    init {
        timer.isRepeats = true
        timer.initialDelay = STEP_DELAY_MS
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorReleased(event: EditorFactoryEvent) {
                    if (event.editor !== editor) return
                    // The editor is going away underneath us: stop touching it at once,
                    // then ask the popup to close on the next EDT pass (closing a popup
                    // from inside editor release re-enters the release machinery).
                    editorGone = true
                    timer.stop()
                    isPlaying = false
                    val handlers = closeHandlers.toList()
                    ApplicationManager.getApplication().invokeLater {
                        handlers.forEach { it() }
                    }
                }
            },
            lifetime
        )
    }

    /** Subscribe to (pass, isPlaying) changes. Fired on the EDT. */
    fun addListener(listener: (Int, Boolean) -> Unit) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        listeners += listener
    }

    /** Called when the editor disappears and the scrubber must close itself. */
    fun addCloseHandler(handler: () -> Unit) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        closeHandlers += handler
    }

    /**
     * Take over the inline chips and show pass 0.
     * The steady-state trail is cleared first so the two renderers never overlap.
     */
    fun start() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed) return
        // clear() is per-editor: inlay handles live in that editor's user data,
        // not in a global list, so scrubbing in one file cannot wipe another's
        // chips. Pass the editor we are taking over.
        TraceTrailRenderer.clear(editor)
        goTo(0)
    }

    fun goTo(pass: Int) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed) return
        val clamped = pass.coerceIn(0, passCount - 1)
        currentPass = clamped
        renderCurrent()
        fire()
    }

    fun stepBack() {
        if (!isScrubbable) return
        pause()
        goTo(currentPass - 1)
    }

    fun stepForward() {
        if (!isScrubbable) return
        pause()
        goTo(currentPass + 1)
    }

    fun togglePlay() {
        if (isPlaying) pause() else play()
    }

    fun play() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed || !isScrubbable || isPlaying) return
        if (isEditorUnusable()) return
        // Standard media behaviour: pressing play at the end restarts the recording.
        if (currentPass >= passCount - 1) {
            currentPass = 0
            renderCurrent()
        }
        isPlaying = true
        timer.restart()
        fire()
    }

    fun pause() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed) return
        if (!isPlaying) return
        isPlaying = false
        timer.stop()
        fire()
    }

    override fun dispose() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        if (disposed) return
        disposed = true
        isPlaying = false
        timer.stop()
        Disposer.dispose(lifetime)
        // Always drop our own chips. Only rebuild the steady-state trail if the
        // editor is still alive — otherwise its inlays died with it.
        RewindInlayRenderer.clear()
        if (!isEditorUnusable()) {
            TraceTrailRenderer.render(editor, session)
        }
        listeners.clear()
        closeHandlers.clear()
    }

    private fun onTick() {
        if (disposed) {
            timer.stop()
            return
        }
        if (isEditorUnusable()) {
            pause()
            return
        }
        if (currentPass >= passCount - 1) {
            pause()
            return
        }
        goTo(currentPass + 1)
    }

    private fun renderCurrent() {
        if (isEditorUnusable()) {
            timer.stop()
            isPlaying = false
            return
        }
        RewindInlayRenderer.renderPass(editor, session, currentPass)
    }

    private fun isEditorUnusable(): Boolean = editorGone || editor.isDisposed

    private fun fire() {
        val pass = currentPass
        val playing = isPlaying
        for (listener in listeners.toList()) {
            listener(pass, playing)
        }
    }

    companion object {
        /** Slow enough that a room can follow a value changing while you talk over it. */
        const val STEP_DELAY_MS: Int = 400
    }
}
