package com.ripple.blast

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

/**
 * Which nodes in the radius are actually protected by a test?
 *
 * This is the hero feature. Everything else on screen is context; the red list
 * is the finding. So the arithmetic below is written to be readable and
 * checkable rather than clever.
 *
 * ------------------------------------------------------------------------
 * THE ALGORITHM — reverse reachability over the edges we already collected
 * ------------------------------------------------------------------------
 *
 * [BlastRadiusEngine] walked OUTWARD from the changed methods, recording, for
 * each method X, the set of methods that call X:
 *
 *     dependents[X] = { callers of X }
 *
 * Read the other way round, that same map is a call graph:
 *
 *     for every C in dependents[X]:  C --calls--> X
 *
 * So step 1 inverts the map into `callees`, giving normal caller -> callee
 * edges. Step 2 is a plain breadth-first flood starting from every node the
 * index considers test code, following callees:
 *
 *     reached = BFS(callees, from = all test nodes)
 *
 * Step 3 labels every node:
 *
 *     node is test code           -> Coverage.IS_TEST   (the question is meaningless)
 *     node in reached             -> Coverage.COVERED   (some test can reach it)
 *     otherwise                   -> Coverage.UNCOVERED (THE RED LIST)
 *
 * Worked example. Change `Target.changed`. The engine finds two callers,
 * `Caller.coveredPath` and `Other.lonelyPath`, and then finds that
 * `TargetTest.testIt` calls `Caller.coveredPath`:
 *
 *     dependents[changed]     = { coveredPath, lonelyPath }
 *     dependents[coveredPath] = { testIt }
 *
 * inverted:
 *
 *     callees[coveredPath] = { changed }
 *     callees[lonelyPath]  = { changed }
 *     callees[testIt]      = { coveredPath }
 *
 * BFS from { testIt } reaches coveredPath, then changed. `lonelyPath` is never
 * reached, so it is the single red node. `changed` itself is COVERED, because a
 * test does reach it — through the other branch.
 *
 * WHAT THIS IS AND IS NOT. It is static reachability over the edges inside the
 * scanned radius, not line coverage. A test that reaches a method proves the
 * method is *exercised by some test*, not that its behaviour is asserted. The
 * inverse direction is the strong one and the one we lean on: if NO test
 * reaches a node, then no test can possibly be checking it, and that statement
 * is sound as long as the traversal was not truncated — which is exactly why
 * [BlastResult.truncated] is surfaced.
 *
 * THREADING. [computeCoverage] is pure map arithmetic: no PSI, no read action,
 * safe anywhere. [isInTestSourceRoot] and [looksLikeTest] touch PSI/roots and
 * MUST be called with read access held — `ProjectFileIndex.isInTestSourceContent`
 * is `@RequiresReadLock` and will assert without one.
 */
object TestIndex {

    private val TEST_CLASS_SUFFIXES = listOf("Test", "Tests", "TestCase", "Spec", "IT")

    private val TEST_PATH_MARKERS = listOf("/src/test/", "/test/java/", "/src/androidTest/", "/src/integrationTest/")

    /**
     * Authoritative answer from the module model.
     *
     * READ ACTION REQUIRED: `isInTestSourceContent` is `@RequiresReadLock`.
     */
    fun isInTestSourceRoot(project: Project, file: VirtualFile?): Boolean {
        if (file == null || !file.isValid) return false
        if (project.isDisposed) return false
        return ProjectFileIndex.getInstance(project).isInTestSourceContent(file)
    }

    /**
     * Naming/path fallback for projects whose roots are not marked (a plain
     * folder opened as a project, a Gradle import that has not finished, a
     * light test fixture). Without this the red list would claim that a project
     * with a perfectly good `FooTest` has no tests at all.
     *
     * READ ACTION REQUIRED (walks PSI parents).
     */
    fun looksLikeTest(project: Project, method: PsiMethod): Boolean {
        val file = method.containingFile?.virtualFile
        if (isInTestSourceRoot(project, file)) return true

        val path = file?.path?.replace('\\', '/')
        if (path != null && TEST_PATH_MARKERS.any { path.contains(it, ignoreCase = false) }) return true

        var cls: PsiClass? = PsiTreeUtil.getParentOfType(method, PsiClass::class.java)
        while (cls != null) {
            val name = cls.name
            if (name != null && TEST_CLASS_SUFFIXES.any { name.endsWith(it) }) return true
            cls = cls.containingClass
        }
        return false
    }

    /**
     * The set arithmetic described in the class comment.
     *
     * Pure. No PSI, no read action, deterministic for a given graph — which is
     * what makes it unit-testable, and it is unit-tested in
     * `com.ripple.blast.BlastScannerTest`.
     */
    fun computeCoverage(graph: BlastGraph): Map<String, Coverage> {
        if (graph.infos.isEmpty()) return emptyMap()

        // Step 1 — invert dependents (callee -> callers) into callees (caller -> callees).
        val callees = HashMap<String, MutableSet<String>>()
        for ((calleeId, callerIds) in graph.dependents) {
            for (callerId in callerIds) {
                callees.getOrPut(callerId) { LinkedHashSet() }.add(calleeId)
            }
        }

        // Step 2 — flood forward from every test node.
        val testIds = graph.infos.values.filter { it.isTest }.map { it.key.id }
        val reached = HashSet<String>(testIds)
        val queue = ArrayDeque(testIds)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (next in callees[current].orEmpty()) {
                if (reached.add(next)) queue.addLast(next)
            }
        }

        // Step 3 — label.
        return graph.infos.mapValues { (id, info) ->
            when {
                info.isTest -> Coverage.IS_TEST
                reached.contains(id) -> Coverage.COVERED
                else -> Coverage.UNCOVERED
            }
        }
    }

    /** Convenience: the ids that came out UNCOVERED. The red list, before tree shaping. */
    fun redListIds(graph: BlastGraph): Set<String> =
        computeCoverage(graph).filterValues { it == Coverage.UNCOVERED }.keys
}
