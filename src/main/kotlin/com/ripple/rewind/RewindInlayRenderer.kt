package com.ripple.rewind

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.ripple.engine.TraceEvent
import com.ripple.engine.TraceSession

/**
 * Renders the inline value chips for ONE pass of a recording.
 *
 * `TraceEvent.visitIndex` is assigned per source line by the JDI driver
 * (`JdiSession` keeps a `line -> visits` counter), so visitIndex N means
 * "the Nth time this particular line was reached". Scrubbing to pass N
 * therefore shows, for every line, the most recent event at or before N:
 *  - the line that actually ran on pass N is painted "live",
 *  - lines that last ran on an earlier pass keep their value, dimmed.
 *
 * That carry-forward is what makes the scrub feel like video rather than a
 * flickering subset of chips appearing and vanishing.
 *
 * This object owns its own inlay list, entirely separate from
 * [com.ripple.inlay.TraceTrailRenderer], so the two never fight over the same
 * handles. EDT only.
 */
object RewindInlayRenderer {

    private val active = mutableListOf<Inlay<*>>()

    fun clear() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val inlay = iterator.next()
            if (inlay.isValid) {
                inlay.dispose()
            }
            iterator.remove()
        }
    }

    /**
     * Paints the state of the traced method as of [visitIndex].
     * Safe to call on a disposed editor: it simply clears and returns.
     */
    fun renderPass(editor: Editor, session: TraceSession, visitIndex: Int) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        clear()
        if (editor.isDisposed) return

        val chosen = LinkedHashMap<Int, TraceEvent>()
        for (event in session.events) {
            if (event.visitIndex > visitIndex) continue
            val current = chosen[event.lineNumber]
            if (current == null || event.visitIndex >= current.visitIndex) {
                chosen[event.lineNumber] = event
            }
        }
        if (chosen.isEmpty()) return

        val document = editor.document
        val inlayModel = editor.inlayModel
        val lineCount = document.lineCount
        for (line in chosen.keys.sorted()) {
            val event = chosen.getValue(line)
            val label = summarize(event.variableSnapshots)
            if (label.isEmpty()) continue
            val documentLine = line - 1
            if (documentLine < 0 || documentLine >= lineCount) continue
            val offset = document.getLineEndOffset(documentLine)
            val renderer = RewindChipRenderer(label, event.visitIndex == visitIndex)
            val inlay = inlayModel.addInlineElement(offset, true, renderer)
            if (inlay != null) {
                active += inlay
            }
        }
    }

    /** Highest pass number present in the recording. 0 when there is nothing to scrub. */
    fun maxVisitIndex(session: TraceSession): Int =
        session.events.maxOfOrNull { it.visitIndex } ?: 0

    private fun summarize(variables: Map<String, String>): String {
        if (variables.isEmpty()) return ""
        val text = variables.entries.joinToString("  ") { entry -> "${entry.key}=${entry.value}" }
        return if (text.length > MAX_CHIP_CHARS) text.substring(0, MAX_CHIP_CHARS - 3) + "..." else text
    }

    private const val MAX_CHIP_CHARS = 120
}
