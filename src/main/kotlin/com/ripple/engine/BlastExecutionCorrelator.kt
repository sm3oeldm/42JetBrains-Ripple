package com.ripple.engine

import com.ripple.blast.BlastNode
import com.ripple.blast.BlastResult
import com.ripple.blast.Execution

/**
 * Folds a recording back into the blast radius.
 *
 * This is the second half of why the two features belong in one product. Static
 * analysis only ever guesses: a reference search says a method *can* be reached,
 * never that it *was*. Once a recording exists, that guess becomes an
 * observation — and the intersection is the strongest thing Ripple can say:
 *
 *   UNCOVERED + NEVER_EXECUTED -> nothing tests this, and it did not even run.
 *                                 You have zero evidence it works.
 *
 * Deliberately conservative. Only nodes this run actually INSTRUMENTED can be
 * marked [Execution.NEVER_EXECUTED]; everything else keeps whatever it had.
 * A node that was out of the target set — because the radius was truncated, or
 * because it is test code that a main()-launched recording could never reach —
 * is left at [Execution.NOT_RECORDED]. Reporting it as never-executed would be a
 * guess wearing the costume of evidence, which is exactly what this feature is
 * supposed to replace.
 *
 * Pure data, no PSI, no JDI. Safe to call on any thread.
 */
object BlastExecutionCorrelator {

    /** Return [result] with [Execution] filled in from [trace]. */
    fun correlate(result: BlastResult, trace: BlastTraceResult): BlastResult =
        correlate(result, listOf(trace))

    /**
     * Correlate against several recordings at once.
     *
     * The sets are unioned BEFORE marking rather than folded one recording at a
     * time: folding would let a later run that instrumented a node without
     * reaching it overwrite an earlier run's direct observation of it, silently
     * downgrading EXECUTED to NEVER_EXECUTED. A node counts as executed if ANY
     * recording saw it, and as never-executed only when some run instrumented it
     * and none ever reached it.
     */
    fun correlate(result: BlastResult, traces: List<BlastTraceResult>): BlastResult {
        if (result.roots.isEmpty() || traces.isEmpty()) return result
        val executed = HashSet<String>()
        val targeted = HashSet<String>()
        for (trace in traces) {
            executed += trace.executedNodeIds
            targeted += trace.targetedNodeIds
        }
        return result.copy(roots = result.roots.map { mark(it, executed, targeted) })
    }

    /**
     * The headline finding, already sorted for the UI: in the radius, untested,
     * instrumented, and never observed running.
     */
    fun unprovenAndUnrun(result: BlastResult): List<BlastNode> =
        result.distinctNodes.filter { it.isUnprovenAndUnrun }.sortedBy { it.hops }

    /** Per-node event counts, for the "12 events" badge on a tree row. */
    fun eventCounts(trace: BlastTraceResult): Map<String, Int> =
        trace.byNode.mapValues { (_, node) -> node.events.size }

    /**
     * Recursion is bounded by [com.ripple.blast.BlastLimits.MAX_HOPS], so the
     * depth here is at most a handful of frames even on a pathological graph.
     * Keys on [com.ripple.blast.NavigationKey.id] — never on a node object,
     * which is not identity-stable across a rebuild of the tree.
     */
    private fun mark(node: BlastNode, executed: Set<String>, targeted: Set<String>): BlastNode {
        val children =
            if (node.children.isEmpty()) node.children
            else node.children.map { mark(it, executed, targeted) }
        val id = node.key.id
        val execution = when {
            id in executed -> Execution.EXECUTED
            id in targeted -> Execution.NEVER_EXECUTED
            // Not instrumented by this run: previous verdict stands. Upgrading a
            // silence to a finding is how a tool loses a judge's trust.
            else -> node.execution
        }
        if (execution == node.execution && children === node.children) return node
        return node.copy(execution = execution, children = children)
    }
}
