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

        // Which variables actually MOVE during this run?
        //
        // A chip has maybe 60 characters of usable width before it runs off the
        // right edge of the editor. Rendering in map order put the method's
        // parameters first — and a parameter like prices=[100.0, 50.0, 25.0] is
        // both long and constant, so it consumed the whole chip and pushed
        // `total` (the only variable the bug is visible in) off screen entirely.
        //
        // The interesting variables are the ones that change. Lead with those.
        val varying: Set<String> = session.events
            .flatMap { it.variableSnapshots.entries }
            .groupBy({ it.key }, { it.value })
            .filterValues { it.distinct().size > 1 }
            .keys

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
                append(summarize(chosen.variableSnapshots, varying))
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

    /** Usable chip width before the text runs off the right edge of the editor. */
    private const val MAX_LABEL = 72

    /** Long constants get shortened rather than dropped, so context survives. */
    private const val MAX_CONSTANT_VALUE = 18

    /**
     * Render one line's snapshot, most informative part first.
     *
     * Order: variables that CHANGE during the run, then the ones that do not.
     * Constants are also abbreviated, because a long unchanging array is the
     * least useful thing on the line and the most expensive in width.
     */
    private fun summarize(vars: Map<String, String>, varying: Set<String>): String {
        if (vars.isEmpty()) return ""

        val (changing, constant) = vars.entries.partition { it.key in varying }

        val parts = ArrayList<String>(vars.size)
        changing.sortedBy { it.key }.forEach { parts += "${it.key}=${it.value}" }
        constant.sortedBy { it.key }.forEach { (k, v) ->
            val short = if (v.length > MAX_CONSTANT_VALUE) v.take(MAX_CONSTANT_VALUE - 1) + "…" else v
            parts += "$k=$short"
        }

        // Build up to the width budget, then say how many were hidden rather
        // than silently cutting mid-token.
        val out = StringBuilder()
        var shown = 0
        for (p in parts) {
            val addition = if (out.isEmpty()) p.length else p.length + 2
            if (out.isNotEmpty() && out.length + addition > MAX_LABEL) break
            if (out.isNotEmpty()) out.append("  ")
            out.append(p)
            shown++
        }
        if (shown < parts.size) out.append("  +${parts.size - shown}")
        return out.toString()
    }
}
