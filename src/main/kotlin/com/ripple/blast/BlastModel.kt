package com.ripple.blast

/**
 * Shared contract for the blast-radius layer.
 *
 * Every other blast/rewind file compiles against this, so it is deliberately
 * small, immutable, and free of PSI references. PSI elements must never be
 * stored here: a blast result outlives the read action that produced it, and a
 * held PsiElement would be stale (or throw) by the time the UI paints it.
 * Navigation therefore goes through [BlastNode.navigationKey], which the UI
 * re-resolves on demand.
 */

/** What kind of thing a node is. Drives the icon and the sort order. */
enum class NodeKind {
    /** A method the user actually edited. The centre of the blast. */
    CHANGED_ROOT,

    /** A method that reaches a changed root through 1..N call hops. */
    CALLER,

    /** A test method or a method inside a test source root. */
    TEST
}

/**
 * Whether anything is protecting this node.
 *
 * This is the hero signal of the whole product, so the states are deliberately
 * coarse — a judge has to read it off a projector in one second.
 */
enum class Coverage {
    /** At least one test reaches this node through the call graph. */
    COVERED,

    /** In the blast radius and NO test reaches it. The red list. */
    UNCOVERED,

    /** The node is itself test code; asking about its coverage is meaningless. */
    IS_TEST,

    /** Not yet computed, or the index was unavailable (dumb mode). */
    UNKNOWN
}

/**
 * Whether a node was observed running during a Rewind recording.
 *
 * This is the half that static analysis cannot supply, and it is what makes the
 * two halves of the product worth combining:
 *
 *   UNCOVERED + NEVER_EXECUTED -> "nothing tests this, and it did not even run.
 *                                  You have zero evidence it works."
 *   UNCOVERED + EXECUTED       -> "this ran with real values and nothing was
 *                                  checking the result."
 */
enum class Execution {
    /** A trace observed this node executing. */
    EXECUTED,

    /** A trace ran, and this node never appeared in it. */
    NEVER_EXECUTED,

    /** No recording has been made yet. */
    NOT_RECORDED
}

/**
 * How to find this element again later, without holding PSI.
 *
 * [fqcn] is the binary class name so it matches what JDI reports, letting a
 * recorded [com.ripple.engine.TraceEvent] be matched back to a blast node.
 */
data class NavigationKey(
    val fqcn: String,
    val methodName: String,
    val parameterTypes: List<String>
) {
    /** Stable identity used for visited-sets and trace correlation. */
    val id: String get() = "$fqcn#$methodName(${parameterTypes.joinToString(",")})"

    override fun toString(): String = id
}

/**
 * One node in the blast radius.
 *
 * [children] are the things that depend on THIS node, so the tree reads
 * outward from the change — the direction the ripple actually travels.
 */
data class BlastNode(
    val key: NavigationKey,
    val displayName: String,
    val kind: NodeKind,
    val hops: Int,
    val coverage: Coverage = Coverage.UNKNOWN,
    val execution: Execution = Execution.NOT_RECORDED,
    /** 1-based source line of the declaration, or -1 if unknown. */
    val line: Int = -1,
    /** Path of the file containing this node, for display only. */
    val filePath: String? = null,
    val children: List<BlastNode> = emptyList()
) {
    val navigationKey: NavigationKey get() = key

    /** Depth-first walk including this node. */
    fun walk(): Sequence<BlastNode> = sequence {
        yield(this@BlastNode)
        children.forEach { yieldAll(it.walk()) }
    }

    /**
     * The headline finding: in the radius, not a test, and nothing tests it.
     * Sorted to the top of the UI and counted in the stats bar.
     */
    val isRedListed: Boolean
        get() = coverage == Coverage.UNCOVERED && kind != NodeKind.TEST

    /** The strongest possible finding — see [Execution]. */
    val isUnprovenAndUnrun: Boolean
        get() = isRedListed && execution == Execution.NEVER_EXECUTED
}

/**
 * The result of one blast-radius scan.
 *
 * [roots] are the changed methods. Everything else hangs beneath them.
 */
data class BlastResult(
    val roots: List<BlastNode>,
    val changedFileCount: Int,
    val maxHops: Int,
    val truncated: Boolean,
    val scanMillis: Long
) {
    val allNodes: List<BlastNode> get() = roots.flatMap { it.walk().toList() }

    /** Distinct nodes, since one method can be reached by several paths. */
    val distinctNodes: List<BlastNode>
        get() = allNodes.distinctBy { it.key.id }

    val redList: List<BlastNode>
        get() = distinctNodes.filter { it.isRedListed }

    val totalInRadius: Int get() = distinctNodes.count { it.kind != NodeKind.CHANGED_ROOT }

    val uncoveredPercent: Int
        get() = if (totalInRadius == 0) 0 else (redList.size * 100) / totalInRadius

    /** True when the working tree had no changes — the UI renders an empty state. */
    val isEmpty: Boolean get() = changedFileCount == 0 || roots.isEmpty()

    companion object {
        fun empty(): BlastResult = BlastResult(
            roots = emptyList(),
            changedFileCount = 0,
            maxHops = 0,
            truncated = false,
            scanMillis = 0
        )
    }
}

/** Hard limits. Not suggestions — an unbounded traversal freezes the IDE. */
object BlastLimits {
    const val MAX_HOPS = 3
    const val MAX_NODES = 500
    const val MAX_REFS_PER_SYMBOL = 200
}
