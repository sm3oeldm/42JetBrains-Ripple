package com.ripple.engine

import com.ripple.blast.NavigationKey

/**
 * One captured event, tagged with the blast node it came from.
 *
 * [globalIndex] is the scrubber's coordinate system: it is the position of this
 * event in the single, real, cross-class execution order. Per-node lists cannot
 * express "Main called applyDiscount and then came back", which is exactly the
 * story a blast-scoped recording is for.
 */
data class BlastTraceEvent(
    val globalIndex: Int,
    val key: NavigationKey,
    val methodQualifiedName: String,
    val displayName: String,
    val event: TraceEvent
) {
    val nodeId: String get() = key.id
    val lineNumber: Int get() = event.lineNumber
}

/**
 * Everything captured for one blast node.
 *
 * [events] is in execution order for THIS node, with [TraceEvent.visitIndex]
 * counted per line within the node — so an inlay trail for a caller reads the
 * same way a single-method trace does.
 */
data class NodeTrace(
    val key: NavigationKey,
    val displayName: String,
    val methodQualifiedName: String,
    val events: List<TraceEvent>
) {
    val nodeId: String get() = key.id

    /**
     * Adapt to the existing single-method model so
     * [com.ripple.inlay.TraceTrailRenderer] and [TraceStore] work unchanged on a
     * node picked out of a blast recording.
     */
    fun toTraceSession(startedAt: Long, truncated: Boolean): TraceSession = TraceSession(
        methodQualifiedName = methodQualifiedName,
        events = events,
        startedAt = startedAt,
        truncated = truncated
    )
}

/**
 * The output of one blast-scoped recording.
 *
 * Deliberately exposes the same data twice:
 *  - [orderedEvents] for the scrubber, which needs one global timeline.
 *  - [byNode] for the tree, which needs per-node grouping.
 * Recomputing either from the other at paint time would be wasted work on the
 * EDT, and the global ordering cannot be reconstructed from the grouped form at
 * all.
 *
 * [neverLoadedClasses] and [targetedNodeIds] are what let the product degrade
 * honestly. A class that never loaded is not a failed run — it is the finding.
 */
data class BlastTraceResult(
    val orderedEvents: List<BlastTraceEvent>,
    val byNode: Map<String, NodeTrace>,
    /** Nodes we actually instrumented. Only these may be judged NEVER_EXECUTED. */
    val targetedNodeIds: Set<String>,
    /** Nodes observed running. Always a subset of [targetedNodeIds]. */
    val executedNodeIds: Set<String>,
    /** Classes for which at least one breakpoint was installed. */
    val armedClasses: Set<String>,
    /** Targeted classes the debuggee never loaded during this run. */
    val neverLoadedClasses: Set<String>,
    /** Hit the event cap, lost the VM early, or ran out of time. */
    val truncated: Boolean,
    /** Specifically: the wall-clock budget expired and the debuggee was killed. */
    val timedOut: Boolean,
    val startedAt: Long,
    val durationMillis: Long
) {
    val eventCount: Int get() = orderedEvents.size

    /** Nodes with at least one event. */
    val executedNodeCount: Int get() = byNode.count { it.value.events.isNotEmpty() }

    /** Targeted but never observed — the "zero evidence this works" set. */
    val neverExecutedNodeIds: Set<String> get() = targetedNodeIds - executedNodeIds

    /** Distinct classes that actually produced events. */
    val coveredClasses: Set<String> get() = orderedEvents.map { it.key.fqcn }.toSet()

    val isEmpty: Boolean get() = orderedEvents.isEmpty()

    /** A single-method view of one node, for the existing inlay renderer. */
    fun sessionFor(nodeId: String): TraceSession? =
        byNode[nodeId]?.toTraceSession(startedAt, truncated)

    /** The event the scrubber is parked on, or null if the index is out of range. */
    fun eventAt(globalIndex: Int): BlastTraceEvent? = orderedEvents.getOrNull(globalIndex)

    /** Global indices belonging to [nodeId], for painting a per-node mini timeline. */
    fun globalIndicesFor(nodeId: String): List<Int> =
        orderedEvents.filter { it.nodeId == nodeId }.map { it.globalIndex }

    companion object {
        fun empty(config: BlastTraceConfig): BlastTraceResult = BlastTraceResult(
            orderedEvents = emptyList(),
            byNode = emptyMap(),
            targetedNodeIds = config.targetedNodeIds,
            executedNodeIds = emptySet(),
            armedClasses = emptySet(),
            neverLoadedClasses = config.distinctClasses.toSet(),
            truncated = false,
            timedOut = false,
            startedAt = System.currentTimeMillis(),
            durationMillis = 0L
        )
    }
}
