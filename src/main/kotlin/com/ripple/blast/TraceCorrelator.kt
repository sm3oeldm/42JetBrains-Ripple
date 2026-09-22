package com.ripple.blast

import com.ripple.engine.TraceSession

/**
 * Fuses the two halves of the product: the static blast radius and the recorded
 * execution.
 *
 * ------------------------------------------------------------------------
 * HOW THE MATCH ACTUALLY WORKS — and what it cannot do
 * ------------------------------------------------------------------------
 *
 * Be honest about the data we have. [com.ripple.engine.TraceEvent] carries
 * `lineNumber`, `variableSnapshots`, `visitIndex`, `callDepth` and `threadName`.
 * It carries NO class name and NO method name. The only identity in a
 * [TraceSession] is [TraceSession.methodQualifiedName], which
 * `com.ripple.util.PsiMethodUtil` builds as `"<fqcn>#<methodName>"` for the ONE
 * method the user asked to trace.
 *
 * So the correlation is:
 *
 *  1. Split `methodQualifiedName` on '#' into a class name and a method name.
 *  2. A node is EXECUTED when its [NavigationKey] has the same class and the
 *     same method name, and the session recorded at least one event.
 *  3. Widening, same class only: a node whose declaration line is one of the
 *     recorded `lineNumber`s is also EXECUTED. `JdiTraceConfig` restricts
 *     breakpoints to the traced method's line range, so in practice this only
 *     fires for a nested declaration inside that range — it is a small, sound
 *     bonus, not the main path.
 *  4. Everything else becomes [unmatched], which defaults to
 *     [Execution.NEVER_EXECUTED] as the product spec asks.
 *
 * THE LIMITATION, STATED PLAINLY. Because only one method is instrumented per
 * recording, "NEVER_EXECUTED" here means *"this did not appear in the
 * recordings we have"*, NOT *"this cannot run"*. A caller three hops away may
 * well have executed; we simply were not listening. Two consequences:
 *
 *  - [BlastNode.isUnprovenAndUnrun] over-reports until enough of the radius has
 *    been traced. Use [correlateAll] with every session in [TraceStore] rather
 *    than a single one, and prefer scoping the trace to the radius so the two
 *    sets line up.
 *  - Pass `unmatched = Execution.NOT_RECORDED` when you would rather say "we
 *    do not know" than "it never ran". That is the intellectually honest
 *    default for a partial recording, and it is one argument away.
 *
 * Class names are compared with '$' normalised to '.': a [NavigationKey] holds
 * the JVM binary name (`Outer${'$'}Inner`) so it matches what JDI reports, while
 * `methodQualifiedName` comes from `PsiClass.getQualifiedName()`, which is
 * dotted. For a top-level class the two are identical; for a nested one they
 * are not, and normalising is what makes the nested case work.
 *
 * Pure function, no PSI, no read action, no I/O. Returns a NEW [BlastResult];
 * the input is untouched.
 */
object TraceCorrelator {

    /** Correlate one recording onto [result]. */
    fun correlate(
        result: BlastResult,
        session: TraceSession,
        unmatched: Execution = Execution.NEVER_EXECUTED
    ): BlastResult = correlateAll(result, listOf(session), unmatched)

    /**
     * Correlate several recordings onto [result]. Prefer this: the more of the
     * radius that has been traced, the less [Execution.NEVER_EXECUTED]
     * over-reports.
     */
    fun correlateAll(
        result: BlastResult,
        sessions: Collection<TraceSession>,
        unmatched: Execution = Execution.NEVER_EXECUTED
    ): BlastResult {
        if (result.roots.isEmpty()) return result
        val live = sessions.filter { it.events.isNotEmpty() }
        if (live.isEmpty()) return result

        val executed = executedNodeIds(result, live)
        return result.copy(roots = result.roots.map { retag(it, executed, unmatched) })
    }

    /** The ids a recording proves ran. Exposed so the UI can badge a count without re-walking. */
    fun executedNodeIds(result: BlastResult, sessions: Collection<TraceSession>): Set<String> {
        val executed = LinkedHashSet<String>()
        val nodes = result.distinctNodes
        for (session in sessions) {
            if (session.events.isEmpty()) continue
            val qualified = session.methodQualifiedName
            val tracedClass = normalise(qualified.substringBefore('#'))
            val tracedMethod = qualified.substringAfter('#', "")
            if (tracedClass.isEmpty() || tracedMethod.isEmpty()) continue
            val executedLines = session.events.mapTo(HashSet()) { it.lineNumber }

            for (node in nodes) {
                if (normalise(node.key.fqcn) != tracedClass) continue
                if (node.key.methodName == tracedMethod) {
                    executed.add(node.key.id)
                } else if (node.line > 0 && executedLines.contains(node.line)) {
                    executed.add(node.key.id)
                }
            }
        }
        return executed
    }

    private fun normalise(fqcn: String): String = fqcn.replace('$', '.')

    private fun retag(node: BlastNode, executed: Set<String>, unmatched: Execution): BlastNode =
        node.copy(
            execution = if (executed.contains(node.key.id)) Execution.EXECUTED else unmatched,
            children = node.children.map { retag(it, executed, unmatched) }
        )
}
