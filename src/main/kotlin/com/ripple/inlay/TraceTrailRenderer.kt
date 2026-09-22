package com.ripple.inlay

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.ripple.engine.TraceSession

// Rendering pass (§3.5): last recorded pass per line, end-of-line chips.
// Previous chips are disposed on every new trace so stale values never linger.
object TraceTrailRenderer {
    private val active = mutableListOf<Inlay<*>>()

    fun clear() {
        val it = active.iterator()
        while (it.hasNext()) {
            try {
                it.next().dispose()
            } catch (ignored: Exception) {
            }
            it.remove()
        }
    }

    fun render(editor: Editor, session: TraceSession) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        clear()
        val model = editor.inlayModel
        for (line in session.events.map { it.lineNumber }.distinct().sorted()) {
            val onLine = session.eventsForLine(line)
            if (onLine.isEmpty()) continue
            val last = onLine.last()
            val label = if (onLine.size > 1) {
                "${summarize(last.variableSnapshots)}  (×${onLine.size})"
            } else {
                summarize(last.variableSnapshots)
            }
            if (label.isBlank()) continue
            val docLine = (line - 1).coerceIn(0, editor.document.lineCount - 1)
            val offset = editor.document.getLineEndOffset(docLine)
            try {
                model.addInlineElement(offset, true, TraceValueRenderer(label))?.let { active += it }
            } catch (ignored: Exception) {
                // editor going away mid-render — skip
            }
        }
    }

    private fun summarize(vars: Map<String, String>): String =
        vars.entries.joinToString("  ") { (k, v) -> "$k=$v" }
}
