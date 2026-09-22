package com.ripple.engine

import com.ripple.blast.BlastLimits
import com.ripple.blast.BlastNode
import com.ripple.blast.BlastResult
import com.ripple.blast.NavigationKey
import com.ripple.blast.NodeKind

/**
 * One method the tracer should instrument, and the blast node it belongs to.
 *
 * This is the join between the two halves of Ripple. [key] is the SAME
 * [NavigationKey] the blast radius produced, so every captured
 * [TraceEvent] can be attributed back to the exact node the tree is painting,
 * without the tracer ever knowing what a BlastNode is.
 *
 * [methodName] is the JDI-facing name, not the PSI name: JDI reports a
 * constructor as `<init>` and a static initialiser as `<clinit>`, so those are
 * translated once here rather than at every comparison site.
 *
 * [lines] is a 1-based, inclusive source line range. It may be a superset of the
 * method body — lines with no executable code simply yield no JDI location and
 * are skipped, and arming additionally verifies that each candidate location
 * really belongs to [methodName], so an over-wide range can never leak
 * breakpoints into a neighbouring method of the same class.
 */
data class TraceTarget(
    val key: NavigationKey,
    val methodName: String,
    val lines: IntRange,
    val displayName: String
) {
    /** Binary class name, exactly as JDI's `ReferenceType.name()` reports it. */
    val fqcn: String get() = key.fqcn

    /** Stable identity shared with the blast tree. Visited sets key on this. */
    val nodeId: String get() = key.id

    /** Display/storage key, matching [TraceSession.methodQualifiedName]. */
    val methodQualifiedName: String get() = "${key.fqcn}#$methodName"
}

/**
 * A multi-target trace run.
 *
 * [JdiTraceConfig] carries ONE class and ONE line range, which is all a single
 * "Trace This Method" needs. A blast-scoped recording is the opposite shape: the
 * changed method plus every caller out to [BlastLimits.MAX_HOPS], spread across
 * many classes that load at different moments during the run.
 *
 * The blast radius is what makes this affordable. Line-stepping a whole
 * application is far too slow to be usable, but the radius is by construction
 * the only part of the program the edit can reach — so the radius tells the
 * tracer precisely which methods to instrument, and the tracer's worst
 * limitation becomes the thing that scopes it.
 */
data class BlastTraceConfig(
    val javaBin: String,
    val classpath: String,
    val mainClass: String,
    val mainArgs: List<String> = emptyList(),
    val targets: List<TraceTarget>,
    /** Global cap across ALL targets, not per method. */
    val maxEvents: Int = DEFAULT_MAX_EVENTS,
    /** Wall clock budget for the whole run. The debuggee is killed when it expires. */
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    /**
     * Every class that needs its own ClassPrepareRequest.
     *
     * Order is stable so logs and the never-loaded report read the same way twice.
     */
    val distinctClasses: List<String> get() = targets.map { it.fqcn }.distinct()

    val targetedNodeIds: Set<String> get() = targets.map { it.nodeId }.toSet()

    companion object {
        /** Generous: a loop in the radius can legitimately produce thousands of events. */
        const val DEFAULT_MAX_EVENTS: Int = 20_000

        /** Longer than a single-method trace because several classes must load. */
        const val DEFAULT_TIMEOUT_MS: Long = 30_000L
    }
}

/**
 * The narrow input this lane needs from whatever produced the [BlastResult].
 *
 * A [BlastNode] carries only the declaration line, but breakpoints need the
 * whole body. Resolving that is PSI work, which must happen in a read action and
 * must not be done from inside the JDI loop — so it is a one-method interface
 * the caller implements, rather than a dependency on the scanner.
 *
 * Implementations run on the caller's thread during config construction. Return
 * null when the method cannot be resolved; [BlastTraceTargets] then falls back
 * to a bounded span around the declaration line, which is safe because arming
 * re-checks the owning method of every location.
 */
interface MethodBodyLineResolver {

    /** 1-based inclusive source range of the body of [key], or null if unknown. */
    fun lineRange(key: NavigationKey): IntRange?

    companion object {
        /** Always falls back to the span heuristic. Useful for tests and headless runs. */
        val NONE: MethodBodyLineResolver = object : MethodBodyLineResolver {
            override fun lineRange(key: NavigationKey): IntRange? = null
        }
    }
}

/**
 * Turns a [BlastResult] into a target list.
 *
 * Kept separate from [BlastTraceSession] so the session has no knowledge of the
 * blast model at all: it takes targets and gives back events keyed by
 * [NavigationKey]. That keeps the JDI code testable without a scanner, and keeps
 * this lane from duplicating the other team's work.
 */
object BlastTraceTargets {

    /** Used when [MethodBodyLineResolver] cannot supply a real range. */
    const val FALLBACK_METHOD_SPAN: Int = 40

    /**
     * Flatten [result] into instrumentation targets.
     *
     * Deduplicates on [NavigationKey.id] — never on a node object — because one
     * method is commonly reached through several paths and would otherwise be
     * armed (and therefore counted) more than once.
     *
     * Test nodes are excluded by default: a Rewind recording launches the
     * application's own main class, so a test method genuinely cannot run in it,
     * and reporting it as NEVER_EXECUTED would be a lie dressed as a finding.
     */
    fun from(
        result: BlastResult,
        resolver: MethodBodyLineResolver = MethodBodyLineResolver.NONE,
        includeTests: Boolean = false,
        maxTargets: Int = BlastLimits.MAX_NODES
    ): List<TraceTarget> {
        val out = ArrayList<TraceTarget>()
        val seen = HashSet<String>()
        for (node in result.distinctNodes) {
            if (out.size >= maxTargets) break
            if (!includeTests && node.kind == NodeKind.TEST) continue
            if (!seen.add(node.key.id)) continue
            val target = target(node, resolver) ?: continue
            out += target
        }
        return out
    }

    /** Single-node form, for "trace just this branch of the tree". */
    fun target(node: BlastNode, resolver: MethodBodyLineResolver): TraceTarget? {
        val resolved = resolver.lineRange(node.key) ?: fallbackRange(node) ?: return null
        return TraceTarget(
            key = node.key,
            methodName = jdiMethodName(node.key),
            lines = resolved,
            displayName = node.displayName
        )
    }

    /**
     * PSI names a constructor after its class; JDI calls it `<init>`. Translating
     * once, here, is the difference between a constructor in the radius being
     * traced and it silently never matching a single breakpoint.
     */
    fun jdiMethodName(key: NavigationKey): String {
        val simpleName = key.fqcn.substringAfterLast('.').substringAfterLast('$')
        return if (key.methodName == simpleName) "<init>" else key.methodName
    }

    private fun fallbackRange(node: BlastNode): IntRange? {
        if (node.line <= 0) return null
        return node.line..(node.line + FALLBACK_METHOD_SPAN)
    }
}
