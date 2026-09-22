package com.ripple.blast

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.ripple.engine.TraceEvent
import com.ripple.engine.TraceSession

/**
 * Headless proof of the red-list arithmetic.
 *
 * [LightJavaCodeInsightFixtureTestCase] IS a `BasePlatformTestCase` (it extends
 * it) and additionally gives us a Java module with working indexes, which
 * `ReferencesSearch` needs. A bare `BasePlatformTestCase` has no Java language
 * level configured and the traversal would find nothing.
 *
 * THE FIXTURE — the smallest graph that can distinguish covered from uncovered:
 *
 *     Target.changed()            <- the edited method (the blast root)
 *       ^                ^
 *       |                |
 *     Caller.coveredPath()      Other.lonelyPath()
 *       ^
 *       |
 *     TargetTest.testIt()        <- test code (naming fallback: ends in "Test")
 *
 * `Caller.coveredPath` is reachable from a test. `Other.lonelyPath` is not.
 * Exactly one node must come out red.
 *
 * Note the light fixture puts every file under `/src/`, not `/src/test/`, and
 * marks no test source root — so this also exercises [TestIndex.looksLikeTest]'s
 * naming fallback, which is the path a freshly-opened folder takes in real life.
 */
class BlastScannerTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getTestDataPath(): String = ""

    private fun setUpFixture(): PsiFile {
        val target = myFixture.addFileToProject(
            "Target.java",
            """
            public class Target {
                public static int changed(int n) { return n + 1; }
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "Caller.java",
            """
            public class Caller {
                public static int coveredPath(int n) { return Target.changed(n); }
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "Other.java",
            """
            public class Other {
                public static int lonelyPath(int n) { return Target.changed(n) * 2; }
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "TargetTest.java",
            """
            public class TargetTest {
                public void testIt() { Caller.coveredPath(1); }
            }
            """.trimIndent()
        )
        return target
    }

    /** Scan seeded from Target.java, using the explicit whole-file fallback. */
    private fun scanFromTarget(): BlastResult {
        val target = setUpFixture()
        val vf = requireNotNull(target.virtualFile) { "fixture file has no VirtualFile" }
        val changes = ChangeDetector.detect(project, ChangeDetector.Fallback.WholeFile(vf))
        assertEquals(
            "fallback must be used: the light fixture has no VCS change list",
            ChangeSet.Source.FALLBACK_FILE,
            changes.source
        )
        assertEquals("exactly one changed root expected", 1, changes.seeds.size)
        return BlastScanner.scan(project, changes, null)
    }

    fun testExactlyOneNodeIsRed() {
        val result = scanFromTarget()

        assertFalse("the scan must produce something", result.isEmpty)
        assertFalse("the fixture is far below every limit", result.truncated)

        val red = result.redList
        assertEquals("red list was ${red.map { it.key.id }}", 1, red.size)
        assertEquals("lonelyPath", red[0].key.methodName)
        assertEquals("Other", red[0].key.fqcn)
        assertEquals(Coverage.UNCOVERED, red[0].coverage)
        assertEquals(NodeKind.CALLER, red[0].kind)
    }

    fun testCoveredCallerIsNotRed() {
        val result = scanFromTarget()
        val byName = result.distinctNodes.associateBy { it.key.id }

        val covered = requireNotNull(byName["Caller#coveredPath(int)"]) {
            "coveredPath missing; nodes were ${byName.keys}"
        }
        assertEquals(Coverage.COVERED, covered.coverage)
        assertFalse(covered.isRedListed)

        // The root is reached by a test through the covered branch, so it is
        // covered too — that is the whole point of reverse reachability.
        val root = requireNotNull(byName["Target#changed(int)"]) {
            "root missing; nodes were ${byName.keys}"
        }
        assertEquals(NodeKind.CHANGED_ROOT, root.kind)
        assertEquals(Coverage.COVERED, root.coverage)

        val test = requireNotNull(byName["TargetTest#testIt()"]) {
            "test node missing; nodes were ${byName.keys}"
        }
        assertEquals(NodeKind.TEST, test.kind)
        assertEquals(Coverage.IS_TEST, test.coverage)
        assertFalse("test code is never red-listed", test.isRedListed)
    }

    /** Before any recording every node is NOT_RECORDED; after one, only the traced method ran. */
    fun testTraceCorrelationMarksOnlyTheTracedMethod() {
        val result = scanFromTarget()
        assertTrue(result.distinctNodes.all { it.execution == Execution.NOT_RECORDED })

        val session = TraceSession(
            methodQualifiedName = "Target#changed",
            events = listOf(
                TraceEvent(
                    lineNumber = 2,
                    variableSnapshots = mapOf("n" to "1"),
                    visitIndex = 0,
                    callDepth = 0,
                    threadName = "main"
                )
            ),
            startedAt = 0L,
            truncated = false
        )

        val fused = TraceCorrelator.correlate(result, session)
        val byName = fused.distinctNodes.associateBy { it.key.id }

        assertEquals(
            Execution.EXECUTED,
            requireNotNull(byName["Target#changed(int)"]).execution
        )
        assertEquals(
            Execution.NEVER_EXECUTED,
            requireNotNull(byName["Other#lonelyPath(int)"]).execution
        )

        // The strongest finding: no test reaches it AND no recording saw it run.
        val unproven = fused.distinctNodes.filter { it.isUnprovenAndUnrun }
        assertEquals(1, unproven.size)
        assertEquals("lonelyPath", unproven[0].key.methodName)
    }

    /** The arithmetic on its own, with a hand-built graph and no PSI at all. */
    fun testCoverageArithmeticIsPure() {
        fun info(id: String, isTest: Boolean) = NodeInfo(
            key = NavigationKey(id, "m", emptyList()),
            displayName = id,
            line = -1,
            filePath = null,
            isTest = isTest,
            hops = 0
        )

        val root = info("Root", false)
        val covered = info("Covered", false)
        val lonely = info("Lonely", false)
        val test = info("ATest", true)

        val graph = BlastGraph(
            infos = listOf(root, covered, lonely, test).associateBy { it.key.id },
            dependents = mapOf(
                root.key.id to setOf(covered.key.id, lonely.key.id),
                covered.key.id to setOf(test.key.id)
            ),
            rootIds = listOf(root.key.id),
            truncated = false,
            hopsReached = 2
        )

        val coverage = TestIndex.computeCoverage(graph)
        assertEquals(Coverage.COVERED, coverage[root.key.id])
        assertEquals(Coverage.COVERED, coverage[covered.key.id])
        assertEquals(Coverage.UNCOVERED, coverage[lonely.key.id])
        assertEquals(Coverage.IS_TEST, coverage[test.key.id])
        assertEquals(setOf(lonely.key.id), TestIndex.redListIds(graph))
    }
}
