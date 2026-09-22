package com.ripple.engine

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.ripple.blast.BlastNode
import com.ripple.blast.BlastResult
import com.ripple.blast.Coverage
import com.ripple.blast.Execution
import com.ripple.blast.NavigationKey
import com.ripple.blast.NodeKind
import java.io.File

/**
 * End-to-end proof that a blast radius can be recorded in ONE run.
 *
 * Traces the real sample-blast-demo across two classes at once — `com.shop.Main`
 * and `com.shop.PriceCalculator` — and asserts that every captured event lands
 * on the right blast node. That is the whole integration: without correct
 * attribution the scrubber shows one method's values under another method's
 * name, which looks like working software right up until a judge reads it.
 *
 * `com.shop.Report` is targeted on purpose and is NOT reachable from `Main`. It
 * is the honest-degradation case: the run must succeed, report the class as
 * never loaded, and mark its node NEVER_EXECUTED rather than failing.
 *
 * Requires the demo's compiled classes:
 *     cd sample-blast-demo && gradlew build     (or: javac -g -d out on src/main/java)
 * If they are missing the tests skip rather than fail, so a fresh clone is green.
 */
class BlastTraceSessionTest : BasePlatformTestCase() {

    private companion object {
        /** The compounded total the bug produces: 135.90 instead of 157.50. */
        const val BUGGY_TOTAL = 135.9

        private const val SRC = "sample-blast-demo/src/main/java/com/shop"

        /**
         * Find a method's line range by READING THE SOURCE.
         *
         * Hardcoded line constants rot the moment anyone edits a comment above
         * the method. That has now bitten this project three separate times: the
         * standalone JDI probe silently reported "0 events" for an afternoon, the
         * cross-class trace test broke, and this suite broke — all from the same
         * one-line comment edit. Derive, never hardcode.
         *
         * Lines with no executable code yield no JDI location and are skipped, so
         * a generous superset of the body is safe.
         */
        fun rangeOf(file: String, signatureFragment: String, span: Int = 12): IntRange {
            val f = File("$SRC/$file")
            if (!f.isFile) return 1..1
            val lines = f.readLines()
            val start = lines.indexOfFirst { it.contains(signatureFragment) }
            if (start < 0) return 1..1
            return (start + 1)..minOf(start + span, lines.size)
        }

        /** First line INSIDE the body, i.e. the first statement. */
        fun firstBodyLine(file: String, signatureFragment: String): Int =
            rangeOf(file, signatureFragment).first + 1
    }

    private val applyRange = rangeOf("PriceCalculator.java", "static double applyDiscount")
    private val roundRange = rangeOf("PriceCalculator.java", "static double round", span = 4)
    private val mainRange = rangeOf("Main.java", "public static void main", span = 14)
    private val summariseRange = rangeOf("Report.java", "double summarise", span = 5)

    /** `for (int i = 0; ...` — the loop header inside applyDiscount. */
    private val applyLoopLine = applyRange.first + 2

    /** `total += prices[i];` — the first statement in the loop body. */
    private val applyLoopBodyLine = applyRange.first + 3

    private val mainKey = NavigationKey("com.shop.Main", "main", listOf("java.lang.String[]"))
    private val applyKey = NavigationKey("com.shop.PriceCalculator", "applyDiscount", listOf("double[]", "double"))
    private val roundKey = NavigationKey("com.shop.PriceCalculator", "round", listOf("double"))
    private val summariseKey = NavigationKey("com.shop.Report", "summarise", listOf("double[]", "double"))

    private fun demoClasspath(): File? {
        val out = File("sample-blast-demo/out")
        val classes = File(out, "com/shop/PriceCalculator.class")
        if (!classes.isFile) {
            println("RIPPLE-TEST-SKIP: no PriceCalculator.class at ${out.absolutePath} (cwd=${File(".").absolutePath})")
            return null
        }
        println("RIPPLE-TEST-USING: ${out.absolutePath}")
        return out.absoluteFile
    }

    private fun javaBin(): String {
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return File(File(System.getProperty("java.home"), "bin"), exe).absolutePath
    }

    private fun targets(): List<TraceTarget> = listOf(
        TraceTarget(mainKey, "main", mainRange, "Main.main"),
        TraceTarget(applyKey, "applyDiscount", applyRange, "PriceCalculator.applyDiscount"),
        TraceTarget(roundKey, "round", roundRange, "PriceCalculator.round"),
        // Deliberately unreachable from Main — the never-loaded case.
        TraceTarget(summariseKey, "summarise", summariseRange, "Report.summarise")
    )

    private fun config(cp: File) = BlastTraceConfig(
        javaBin = javaBin(),
        classpath = cp.absolutePath,
        mainClass = "com.shop.Main",
        targets = targets()
    )

    private fun record(): BlastTraceResult? {
        val cp = demoClasspath() ?: return null
        return BlastTraceSession(config(cp)).run(null)
    }

    // ------------------------------------------------------- the integration

    fun testAttributesEventsToMoreThanOneNodeAcrossTwoClasses() {
        val trace = record() ?: return

        assertTrue("no events captured — the blast tracer is not working", trace.orderedEvents.isNotEmpty())

        val nodesWithEvents = trace.byNode.filterValues { it.events.isNotEmpty() }.keys
        assertTrue(
            "events were attributed to only $nodesWithEvents — multi-target tracing collapsed to one node",
            nodesWithEvents.size > 1
        )
        assertTrue(
            "only one class produced events: ${trace.coveredClasses}",
            trace.coveredClasses.size >= 2
        )
        assertTrue("com.shop.Main produced no events", mainKey.id in nodesWithEvents)
        assertTrue("PriceCalculator.applyDiscount produced no events", applyKey.id in nodesWithEvents)

        // Attribution must be exact, not merely non-empty: every event filed
        // under a node has to carry a line from that node's own range.
        trace.byNode[applyKey.id]!!.events.forEach {
            assertTrue("line ${it.lineNumber} filed under applyDiscount", it.lineNumber in applyRange)
        }
        trace.byNode[mainKey.id]!!.events.forEach {
            assertTrue("line ${it.lineNumber} filed under Main.main", it.lineNumber in mainRange)
        }
    }

    fun testGlobalOrderingInterleavesCallerAndCallee() {
        val trace = record() ?: return

        trace.orderedEvents.forEachIndexed { i, e ->
            assertEquals("globalIndex is not the position in the timeline", i, e.globalIndex)
        }

        val mainIndices = trace.globalIndicesFor(mainKey.id)
        val applyIndices = trace.globalIndicesFor(applyKey.id)
        assertTrue("no Main events", mainIndices.isNotEmpty())
        assertTrue("no applyDiscount events", applyIndices.isNotEmpty())

        // Main sets up, calls in, and prints afterwards. If the timeline is not
        // a real global ordering, one of these two will fail.
        assertTrue(
            "no Main event before applyDiscount — the timeline is grouped, not ordered",
            mainIndices.first() < applyIndices.first()
        )
        assertTrue(
            "no Main event after applyDiscount returned — the caller's tail is missing",
            mainIndices.last() > applyIndices.last()
        )
    }

    fun testLoopIsRecordedPerIterationAndShowsTheCompoundingBug() {
        val trace = record() ?: return
        val apply = trace.byNode[applyKey.id]!!

        // Four passes over three items (three bodies plus the failing condition),
        // so the loop must not be collapsed into a single visit.
        val loopVisits = apply.events.filter { it.lineNumber == applyLoopLine }.map { it.visitIndex }
        assertTrue(
            "loop line recorded only $loopVisits — breakpoints took one location per line",
            loopVisits.size >= 2
        )
        assertEquals("visit indexing is not sequential: $loopVisits", loopVisits.sorted(), loopVisits)

        // The bug itself: the discount is applied inside the loop, so the total
        // compounds down to 135.90 instead of 157.50.
        val totals = apply.events.mapNotNull { it.variableSnapshots["total"]?.toDoubleOrNull() }
        assertTrue("local 'total' was never captured", totals.isNotEmpty())
        assertTrue(
            "compounded total $BUGGY_TOTAL never appeared in $totals — the demo bug is not visible",
            totals.any { Math.abs(it - BUGGY_TOTAL) < 1e-6 }
        )

        // Arrays must be rendered BY CONTENTS. ArrayReference.toString() is
        // "instance of double[]", which is useless on stage.
        val prices = apply.events.mapNotNull { it.variableSnapshots["prices"] }
        assertTrue("parameter 'prices' was never captured", prices.isNotEmpty())
        assertTrue(
            "array rendered as an object handle instead of its contents: ${prices.first()}",
            prices.first().startsWith("[")
        )
    }

    /**
     * The regression that actually matters.
     *
     * A double resume — `vm.resume()` in addition to the event loop's
     * `EventSet.resume()` — drives the per-thread suspend count negative, so the
     * NEXT suspension silently fails and the debuggee runs to completion before
     * a single breakpoint is installed. It passed roughly 1 run in 6, which is
     * indistinguishable from "the demo machine is being weird". One green run
     * proves nothing; five identical ones do.
     */
    fun testRecordingIsNotFlakyAcrossRuns() {
        val cp = demoClasspath() ?: return
        val runs = (1..5).map { BlastTraceSession(config(cp)).run(null) }

        val counts = runs.map { it.orderedEvents.size }
        assertTrue("a run captured nothing: $counts", counts.all { it > 0 })
        assertEquals("event count is not deterministic: $counts", 1, counts.distinct().size)

        val nodeSets = runs.map { it.executedNodeIds }
        assertEquals("attribution is not deterministic: $nodeSets", 1, nodeSets.distinct().size)
        assertEquals(
            "a run lost a class: ${runs.map { it.armedClasses }}",
            1,
            runs.map { it.armedClasses }.distinct().size
        )
    }

    // --------------------------------------------------- honest degradation

    fun testClassThatNeverLoadsDegradesInsteadOfFailing() {
        val trace = record() ?: return

        assertTrue(
            "com.shop.Report loaded unexpectedly; armed=${trace.armedClasses}",
            "com.shop.Report" in trace.neverLoadedClasses
        )
        assertTrue("com.shop.Main was never armed", "com.shop.Main" in trace.armedClasses)
        assertTrue(
            "Report.summarise must be reported as never executed",
            summariseKey.id in trace.neverExecutedNodeIds
        )
        // A class that never loads is a finding, not a crash.
        assertFalse("run was wrongly marked truncated", trace.truncated)
        assertFalse("run wrongly timed out", trace.timedOut)
    }

    fun testCorrelationPopulatesExecutionOnTheBlastResult() {
        val trace = record() ?: return

        val blast = blastResult()
        val correlated = BlastExecutionCorrelator.correlate(blast, trace)
        val byId = correlated.distinctNodes.associateBy { it.key.id }

        assertEquals(Execution.EXECUTED, byId.getValue(applyKey.id).execution)
        assertEquals(Execution.EXECUTED, byId.getValue(mainKey.id).execution)
        assertEquals(Execution.NEVER_EXECUTED, byId.getValue(summariseKey.id).execution)

        // The hero finding: uncovered AND never observed running.
        val unproven = BlastExecutionCorrelator.unprovenAndUnrun(correlated).map { it.key.id }
        assertEquals(listOf(summariseKey.id), unproven)

        // A node this run did not instrument must keep its previous verdict.
        val untargeted = NavigationKey("com.shop.Receipt", "render", listOf("double[]", "double"))
        assertEquals(Execution.NOT_RECORDED, byId.getValue(untargeted.id).execution)
    }

    fun testSessionForAdaptsOneNodeToTheExistingInlayModel() {
        val trace = record() ?: return

        val session = trace.sessionFor(applyKey.id)
        assertNotNull("no TraceSession for applyDiscount", session)
        assertEquals("com.shop.PriceCalculator#applyDiscount", session!!.methodQualifiedName)
        assertTrue("adapted session lost its events", session.events.isNotEmpty())
        assertTrue("eventsForLine is empty for the loop body", session.eventsForLine(applyLoopBodyLine).isNotEmpty())

        assertNull("unknown node must not fabricate a session", trace.sessionFor("com.shop.Nope#nope()"))
    }

    // ------------------------------------------------------------- pure data

    fun testTargetsDerivedFromABlastResultSkipTestsAndDedupe() {
        val resolver = object : MethodBodyLineResolver {
            override fun lineRange(key: NavigationKey): IntRange? =
                if (key.id == applyKey.id) applyRange else null
        }
        val targets = BlastTraceTargets.from(blastResult(), resolver)
        val ids = targets.map { it.nodeId }

        assertEquals("a node was armed twice: $ids", ids.distinct().size, ids.size)
        assertTrue("test nodes must not be instrumented: $ids", ids.none { it.startsWith("com.shop.CartTest") })
        assertTrue("the changed root is missing: $ids", applyKey.id in ids)
        assertEquals(applyRange, targets.first { it.nodeId == applyKey.id }.lines)

        // Unresolved nodes fall back to a bounded span around the declaration.
        val receipt = targets.first { it.key.fqcn == "com.shop.Receipt" }
        assertEquals(5, receipt.lines.first)
        assertEquals(5 + BlastTraceTargets.FALLBACK_METHOD_SPAN, receipt.lines.last)
    }

    fun testConstructorsAreTranslatedToJdiNames() {
        val ctor = NavigationKey("com.shop.Cart", "Cart", listOf("double[]"))
        assertEquals("<init>", BlastTraceTargets.jdiMethodName(ctor))
        assertEquals("checkoutTotal", BlastTraceTargets.jdiMethodName(NavigationKey("com.shop.Cart", "checkoutTotal", emptyList())))

        val inner = NavigationKey("com.shop.Cart\$Line", "Line", emptyList())
        assertEquals("<init>", BlastTraceTargets.jdiMethodName(inner))
    }

    /**
     * The demo's radius, hand-built so this lane does not depend on the scanner.
     * Shape matches the real one: applyDiscount at the centre, four callers, two
     * of them covered, Report reached at two hops.
     */
    private fun blastResult(): BlastResult {
        fun node(
            key: NavigationKey,
            name: String,
            kind: NodeKind,
            hops: Int,
            coverage: Coverage,
            line: Int,
            children: List<BlastNode> = emptyList()
        ) = BlastNode(
            key = key,
            displayName = name,
            kind = kind,
            hops = hops,
            coverage = coverage,
            line = line,
            children = children
        )

        val monthly = node(
            NavigationKey("com.shop.Report", "monthlyTotal", listOf("double[][]", "double")),
            "Report.monthlyTotal", NodeKind.CALLER, 2, Coverage.COVERED, 11
        )
        val summarise = node(
            summariseKey, "Report.summarise", NodeKind.CALLER, 1, Coverage.UNCOVERED, summariseRange.first,
            listOf(monthly)
        )
        val cartTest = node(
            NavigationKey("com.shop.CartTest", "discountReducesTotal", emptyList()),
            "CartTest.discountReducesTotal", NodeKind.TEST, 2, Coverage.IS_TEST, 15
        )
        val cart = node(
            NavigationKey("com.shop.Cart", "checkoutTotal", listOf("double")),
            "Cart.checkoutTotal", NodeKind.CALLER, 1, Coverage.COVERED, 11, listOf(cartTest)
        )
        val invoice = node(
            NavigationKey("com.shop.Invoice", "amountDue", listOf("double")),
            "Invoice.amountDue", NodeKind.CALLER, 1, Coverage.COVERED, 11
        )
        val receipt = node(
            NavigationKey("com.shop.Receipt", "render", listOf("double[]", "double")),
            "Receipt.render", NodeKind.CALLER, 1, Coverage.UNCOVERED, 5
        )
        val main = node(
            mainKey, "Main.main", NodeKind.CALLER, 1, Coverage.UNCOVERED, mainRange.first
        )
        val root = node(
            applyKey, "PriceCalculator.applyDiscount", NodeKind.CHANGED_ROOT, 0, Coverage.UNCOVERED, applyRange.first,
            listOf(cart, invoice, summarise, receipt, main)
        )
        return BlastResult(
            roots = listOf(root),
            changedFileCount = 1,
            maxHops = 3,
            truncated = false,
            scanMillis = 1
        )
    }
}
