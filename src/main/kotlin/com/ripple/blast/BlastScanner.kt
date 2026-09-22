package com.ripple.blast

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project

/**
 * The façade. Detect -> traverse -> score -> shape, in one call.
 *
 * Designed to be the body of a `Task.Backgroundable`:
 *
 * ```
 * object : Task.Backgroundable(project, "Ripple: computing blast radius", true) {
 *     override fun run(indicator: ProgressIndicator) {
 *         val changes = ChangeDetector.detect(project, fallback)
 *         val result  = BlastScanner.scan(project, changes, indicator)
 *         ApplicationManager.getApplication().invokeLater { panel.show(result) }
 *     }
 * }
 * ```
 *
 * It never returns null — [BlastResult.empty] is the "nothing to show" value, so
 * the UI has exactly one shape to render and no null branch to forget.
 *
 * THREADING. Background thread. Takes its own short read actions internally
 * (via [ChangeDetector] and [BlastRadiusEngine]); holds none across the scan.
 * Must run in smart mode: [ReferencesSearch] needs the indexes, and asking for
 * them during indexing throws `IndexNotReadyException`. Rather than catching
 * that (a broad catch here would swallow `ProcessCanceledException` too), the
 * scan checks [DumbService] up front and returns an empty result, and the
 * caller is expected to schedule through `DumbService.smartInvokeLater`.
 */
object BlastScanner {

    /**
     * Scan outward from [roots].
     *
     * @param roots the changed-method seed set from [ChangeDetector.detect].
     * @param indicator cancellable progress, or null when called from a test.
     */
    fun scan(project: Project, roots: ChangeSet, indicator: ProgressIndicator?): BlastResult {
        if (project.isDisposed) return BlastResult.empty()
        if (roots.isEmpty) return BlastResult.empty()
        if (DumbService.getInstance(project).isDumb) return BlastResult.empty()

        val started = System.currentTimeMillis()
        indicator?.isIndeterminate = true
        indicator?.text = "Ripple: tracing the blast radius"

        val graph = BlastRadiusEngine.traverse(project, roots.seeds, indicator)
        indicator?.checkCanceled()
        ProgressManager.checkCanceled()

        val coverage = TestIndex.computeCoverage(graph)
        val budget = intArrayOf(TREE_NODE_BUDGET)
        val rootIdSet = graph.rootIds.toSet()

        val tree = graph.rootIds.mapNotNull { id ->
            buildNode(id, graph, coverage, rootIdSet, emptySet(), budget)
        }

        return BlastResult(
            roots = tree,
            changedFileCount = roots.changedFileCount,
            maxHops = graph.hopsReached,
            truncated = graph.truncated || roots.truncated || budget[0] <= 0,
            scanMillis = System.currentTimeMillis() - started
        )
    }

    /** Convenience for callers that have not run [ChangeDetector] themselves. */
    fun detectAndScan(
        project: Project,
        fallback: ChangeDetector.Fallback,
        indicator: ProgressIndicator?
    ): BlastResult = scan(project, ChangeDetector.detect(project, fallback), indicator)

    /**
     * A dense graph is a DAG, and a DAG expanded into a tree can blow up
     * combinatorially even while staying under [BlastLimits.MAX_NODES] distinct
     * nodes. This caps the number of *emitted* tree nodes; running out sets
     * [BlastResult.truncated] like any other limit.
     */
    private const val TREE_NODE_BUDGET = 2000

    /**
     * Shape one subtree.
     *
     * [path] is the set of ancestor ids on the current branch. A node already on
     * its own path is emitted as a leaf, which terminates mutual recursion
     * (`a -> b -> a`) without losing the fact that the cycle exists.
     *
     * Children are sorted by id so two scans of an unchanged project produce an
     * identical tree — a demo that reorders itself between runs looks broken.
     */
    private fun buildNode(
        id: String,
        graph: BlastGraph,
        coverage: Map<String, Coverage>,
        rootIds: Set<String>,
        path: Set<String>,
        budget: IntArray
    ): BlastNode? {
        val info = graph.infos[id] ?: return null
        if (budget[0] <= 0) return null
        budget[0]--

        val kind = when {
            rootIds.contains(id) -> NodeKind.CHANGED_ROOT
            info.isTest -> NodeKind.TEST
            else -> NodeKind.CALLER
        }

        val children = if (path.contains(id)) {
            emptyList()
        } else {
            val nextPath = path + id
            graph.dependents[id].orEmpty()
                .sorted()
                .mapNotNull { buildNode(it, graph, coverage, rootIds, nextPath, budget) }
        }

        return BlastNode(
            key = info.key,
            displayName = info.displayName,
            kind = kind,
            hops = info.hops,
            coverage = coverage[id] ?: Coverage.UNKNOWN,
            execution = Execution.NOT_RECORDED,
            line = info.line,
            filePath = info.filePath,
            children = children
        )
    }
}
