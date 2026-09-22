package com.ripple.inlay

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.util.Key
import com.ripple.engine.TraceSession

// Rendering pass (§3.5): end-of-line value chips, one per traced line.
//
// Inlay handles are stored PER EDITOR in user data, not in a global list.
// A single shared list meant tracing in a second file disposed the first
// file's chips; it also leaked handles for the lifetime of the IDE.
object TraceTrailRenderer {

    private val INLAYS = Key.create<MutableList<Inlay<*>>>("ripple.trace.inlays")

    /** Disposes only the chips this plugin added to [editor]. */
    fun clear(editor: Editor) {
        val list = editor.getUserData(INLAYS) ?: return
        for (inlay in list) {
            try {
                inlay.dispose()
            } catch (ignored: Exception) {
                // editor/inlay already disposed — nothing to do
            }
        }
        list.clear()
    }

    /**
     * Paints [session] into [editor].
     *
     * @param visitIndex which pass through each line to show.
     *   `null` (default) renders the LAST recorded pass per line — the §3.5
     *   behaviour. A non-null value renders that specific iteration, which is
     *   what the time-machine scrubber drives.
     */
    fun render(editor: Editor, session: TraceSession, visitIndex: Int? = null) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        clear(editor)
        if (editor.isDisposed) return

        val list = editor.getUserData(INLAYS) ?: mutableListOf<Inlay<*>>().also {
            editor.putUserData(INLAYS, it)
        }
        val model = editor.inlayModel

        for (line in session.events.map { it.lineNumber }.distinct().sorted()) {
            val onLine = session.eventsForLine(line)
            if (onLine.isEmpty()) continue

            // Pick the pass to display. When scrubbing past a line's last visit
            // (loops have uneven trip counts) fall back to that line's final
            // pass, so a line never blanks out mid-scrub.
            val chosen = if (visitIndex == null) {
                onLine.last()
            } else {
                onLine.firstOrNull { it.visitIndex == visitIndex } ?: onLine.last()
            }

            val label = buildString {
                append(summarize(chosen.variableSnapshots))
                if (visitIndex == null && onLine.size > 1) append("  (×${onLine.size})")
                if (visitIndex != null && onLine.size > 1) {
                    append("  [${chosen.visitIndex + 1}/${onLine.size}]")
                }
            }
            if (label.isBlank()) continue

            val docLine = (line - 1).coerceIn(0, editor.document.lineCount - 1)
            val offset = editor.document.getLineEndOffset(docLine)
            try {
                model.addInlineElement(offset, true, TraceValueRenderer(label))?.let { list += it }
            } catch (ignored: Exception) {
                // editor going away mid-render — skip this line
            }
        }
    }

    private fun summarize(vars: Map<String, String>): String =
        vars.entries.joinToString("  ") { (k, v) -> "$k=$v" }
}
