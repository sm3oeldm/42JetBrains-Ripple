package com.ripple.blast

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor

/**
 * One method in the radius. PSI-free; [BlastPsiKeys.resolve] gets the element back.
 */
data class NodeInfo(
    val key: NavigationKey,
    val displayName: String,
    val line: Int,
    val filePath: String?,
    val isTest: Boolean,
    /** Call hops from the nearest changed root. 0 for a root itself. */
    val hops: Int
)

/**
 * The raw blast graph, before coverage arithmetic and before tree shaping.
 *
 * [dependents] is keyed by callee and holds callers: `dependents[X]` is
 * "everything that calls X", i.e. everything X can break. That is the direction
 * the ripple actually travels, and it is the direction the tree renders in.
 *
 * Everything is a [NavigationKey.id] string. Never a [PsiMethod] — PSI identity
 * is only valid inside the single read action that produced it.
 */
data class BlastGraph(
    val infos: Map<String, NodeInfo>,
    val dependents: Map<String, Set<String>>,
    val rootIds: List<String>,
    val truncated: Boolean,
    /** How many hops we actually completed (may be < MAX_HOPS if we ran out of callers). */
    val hopsReached: Int
) {
    companion object {
        fun empty(): BlastGraph = BlastGraph(emptyMap(), emptyMap(), emptyList(), false, 0)
    }
}

/**
 * Who depends on the changed methods?
 *
 * Breadth-first over [ReferencesSearch], outward from the changed roots, at
 * most [BlastLimits.MAX_HOPS] hops.
 *
 * THREADING CONTRACT — this is the part that decides whether the IDE survives
 * the demo:
 *
 *  - ONE READ ACTION PER HOP. Not one for the whole traversal. A read action
 *    held across a multi-second traversal blocks every write the user tries to
 *    make; the editor visibly locks up. Each hop takes a fresh short read
 *    action, and the results are merged into the graph only after that hop's
 *    read action has been released.
 *  - Because PSI is dropped between hops, the frontier is carried as
 *    [NavigationKey]s and re-resolved at the start of the next hop. A method
 *    that was deleted in between simply drops out of the radius — correct, and
 *    far better than a stale-PSI exception.
 *  - Cancellation is checked between hops (the caller's [ProgressIndicator])
 *    and inside the reference processor ([ProgressManager.checkCanceled]).
 *    Nothing here catches a broad `Exception`: that would swallow
 *    `ProcessCanceledException` and turn a cancelled scan into a frozen IDE.
 *
 * LIMITS. [BlastLimits.MAX_NODES] and [BlastLimits.MAX_REFS_PER_SYMBOL] are
 * enforced, and hitting either sets [BlastGraph.truncated]. Truncating silently
 * would make the red list look complete when it is not, which is worse than
 * showing no list at all.
 */
object BlastRadiusEngine {

    /** One discovered call edge: [callerInfo] calls the method identified by [calleeId]. */
    private data class Edge(val calleeId: String, val callerInfo: NodeInfo)

    private data class HopResult(val edges: List<Edge>, val refsTruncated: Boolean)

    fun traverse(project: Project, seeds: List<RootSeed>, indicator: ProgressIndicator?): BlastGraph {
        if (seeds.isEmpty()) return BlastGraph.empty()

        val infos = LinkedHashMap<String, NodeInfo>()
        val dependents = LinkedHashMap<String, MutableSet<String>>()
        var truncated = false

        for (seed in seeds) {
            if (infos.size >= BlastLimits.MAX_NODES) {
                truncated = true
                break
            }
            infos.putIfAbsent(
                seed.key.id,
                NodeInfo(seed.key, seed.displayName, seed.line, seed.filePath, seed.isTest, hops = 0)
            )
        }
        val rootIds = infos.keys.toList()

        var frontier: List<NavigationKey> = rootIds.mapNotNull { infos[it]?.key }
        var hop = 0

        while (frontier.isNotEmpty() && hop < BlastLimits.MAX_HOPS && !truncated) {
            indicator?.checkCanceled()
            ProgressManager.checkCanceled()
            hop++
            indicator?.text2 = "Ripple: hop $hop of ${BlastLimits.MAX_HOPS} (${infos.size} nodes)"

            val captured = frontier
            // --- the hop's single read action -------------------------------
            val hopResult = ReadAction.compute<HopResult, RuntimeException> {
                val edges = ArrayList<Edge>()
                var refsCut = false
                for (key in captured) {
                    ProgressManager.checkCanceled()
                    val method = BlastPsiKeys.resolve(project, key) ?: continue
                    val (callers, cut) = callersOf(project, method)
                    if (cut) refsCut = true
                    for (callerInfo in callers) {
                        if (callerInfo.key.id == key.id) continue // self-recursion is not a ripple
                        edges.add(Edge(calleeId = key.id, callerInfo = callerInfo))
                    }
                }
                HopResult(edges, refsCut)
            }
            // --- read action released; merge now ----------------------------
            if (hopResult.refsTruncated) truncated = true

            val next = LinkedHashMap<String, NavigationKey>()
            for (edge in hopResult.edges) {
                val callerId = edge.callerInfo.key.id
                dependents.getOrPut(edge.calleeId) { LinkedHashSet() }.add(callerId)
                if (!infos.containsKey(callerId)) {
                    if (infos.size >= BlastLimits.MAX_NODES) {
                        truncated = true
                        break
                    }
                    infos[callerId] = edge.callerInfo.copy(hops = hop)
                    next[callerId] = edge.callerInfo.key
                }
            }
            frontier = next.values.toList()
        }

        // Running out of HOPS is truncation too, and it is the kind that most
        // distorts the red list: a node whose only test path is 4 hops away
        // looks uncovered when it is not. The UI must be able to say "there may
        // be more", so this is reported exactly like a node/ref cap.
        if (frontier.isNotEmpty()) truncated = true

        return BlastGraph(
            infos = infos,
            dependents = dependents.mapValues { (_, v) -> v.toSet() },
            rootIds = rootIds,
            truncated = truncated,
            hopsReached = hop
        )
    }

    /**
     * Methods that contain a reference to [method], capped at
     * [BlastLimits.MAX_REFS_PER_SYMBOL].
     *
     * Read action required — the caller already holds one for the whole hop.
     *
     * Uses an explicit [Processor] rather than `query.forEach { }`. In Kotlin a
     * trailing lambda on a `Query` binds to the STDLIB `Iterable.forEach`, which
     * silently materialises the entire result set through `findAll()` before the
     * first element is seen — the cap would never fire. Passing a real
     * `Processor` selects `Query.forEach(Processor)`, which is lazy and stops the
     * search the moment the processor returns false.
     *
     * References that are not inside a method (field initialisers, static
     * initialisers, javadoc) are skipped: there is no method-level node to
     * attribute them to, and inventing one would break navigation.
     */
    private fun callersOf(project: Project, method: PsiMethod): Pair<List<NodeInfo>, Boolean> {
        val found = LinkedHashMap<String, NodeInfo>()
        var seen = 0
        val processor = Processor<PsiReference> { reference ->
            ProgressManager.checkCanceled()
            seen++
            val container = PsiTreeUtil.getParentOfType(reference.element, PsiMethod::class.java)
            if (container != null) {
                val key = BlastPsiKeys.keyOf(container)
                if (key != null && !found.containsKey(key.id)) {
                    val file = container.containingFile?.virtualFile
                    found[key.id] = NodeInfo(
                        key = key,
                        displayName = BlastPsiKeys.displayNameOf(container),
                        line = BlastPsiKeys.declarationLine(container),
                        filePath = BlastPsiKeys.filePathOf(container),
                        isTest = TestIndex.isInTestSourceRoot(project, file) ||
                            TestIndex.looksLikeTest(project, container),
                        hops = -1 // filled in by the caller once the hop number is known
                    )
                }
            }
            seen < BlastLimits.MAX_REFS_PER_SYMBOL
        }
        // `forEach` returns false when the processor stopped the search early.
        val completed = ReferencesSearch
            .search(method, GlobalSearchScope.projectScope(project), false)
            .forEach(processor)
        return found.values.toList() to !completed
    }
}
