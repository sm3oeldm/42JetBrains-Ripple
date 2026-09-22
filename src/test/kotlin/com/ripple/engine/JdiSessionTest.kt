package com.ripple.engine

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * End-to-end proof that the tracer actually captures runtime state.
 *
 * This launches a real JVM under JDWP and traces `Demo.reverse`, so it covers
 * the whole P0 path except the inlay painting: launch, attach, class-prepare,
 * breakpoint install, variable snapshot, auto-resume, teardown.
 *
 * WHY THIS TEST EXISTS
 * --------------------
 * The JDI path was silently flaky — roughly 1 run in 6 captured anything, the
 * rest returned zero events — because of a double resume (see JdiSession).
 * A compile-time green build said nothing about it, and the failure mode is
 * indistinguishable from "the demo machine is being weird". [testTraceIsNotFlaky]
 * would have caught it on the first run.
 *
 * Requires `sample-trace-demo/out/Demo.class`, built with local variable info:
 *     cd sample-trace-demo && javac -g --release 21 -d out Demo.java
 * If that is missing the tests skip rather than fail, so a fresh clone is green.
 */
class JdiSessionTest : BasePlatformTestCase() {

    private companion object {
        // Body of Demo.reverse(). Lines without executable code yield no
        // location and are skipped, so a superset of the method is safe.
        const val REVERSE_FIRST_LINE = 22
        const val REVERSE_LAST_LINE = 26
    }

    private fun sampleClasspath(): File? {
        val out = File("sample-trace-demo/out")
        if (!File(out, "Demo.class").isFile) {
            println("RIPPLE-TEST-SKIP: no Demo.class at ${out.absolutePath} (cwd=${File(".").absolutePath})")
            return null
        }
        println("RIPPLE-TEST-USING: ${out.absolutePath}")
        return out.absoluteFile
    }

    private fun javaBin(): String {
        val exe = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return File(File(System.getProperty("java.home"), "bin"), exe).absolutePath
    }

    private fun config(cp: File) = JdiTraceConfig(
        javaBin = javaBin(),
        classpath = cp.absolutePath,
        mainClass = "Demo",
        targetClass = "Demo",
        methodQualifiedName = "Demo#reverse",
        lines = REVERSE_FIRST_LINE..REVERSE_LAST_LINE
    )

    fun testCapturesRuntimeStateFromRealJvm() {
        val cp = sampleClasspath() ?: return
        val session = JdiSession(config(cp)).run(null)

        assertTrue("no events captured — the tracer is not working", session.events.isNotEmpty())
        assertEquals("Demo#reverse", session.methodQualifiedName)

        // Every event must carry a line inside the requested range.
        session.events.forEach {
            assertTrue("line ${it.lineNumber} outside traced range", it.lineNumber in REVERSE_FIRST_LINE..REVERSE_LAST_LINE)
        }

        // The array parameter must be snapshotted BY CONTENTS. ArrayReference's
        // own toString() is "instance of int[]", which would be useless on stage.
        val arrays = session.events.mapNotNull { it.variableSnapshots["a"] }
        assertTrue("parameter 'a' was never captured", arrays.isNotEmpty())
        assertTrue(
            "array rendered as an object handle instead of its contents: ${arrays.first()}",
            arrays.first().startsWith("[")
        )
    }

    fun testTrailShowsTheBug() {
        val cp = sampleClasspath() ?: return
        val session = JdiSession(config(cp)).run(null)

        val arrays = session.events.mapNotNull { it.variableSnapshots["a"] }

        // The whole demo rests on these three states being visible in order:
        // the original array, the moment slot 0 is overwritten, and the moment
        // the value 2 is destroyed without ever being copied to slot 3.
        assertTrue("initial array state missing: $arrays", arrays.any { it == "[1, 2, 3, 4, 5]" })
        assertTrue("mid-loop state missing: $arrays", arrays.any { it == "[5, 2, 3, 4, 5]" })
        assertTrue("final corrupted state missing: $arrays", arrays.any { it == "[5, 4, 3, 4, 5]" })

        // And the loop must be recorded as multiple passes, not collapsed into one.
        val loopVisits = session.events.filter { it.lineNumber == 23 }.map { it.visitIndex }
        assertTrue("loop recorded only $loopVisits — visit indexing is broken", loopVisits.size >= 2)
    }

    fun testTraceIsNotFlaky() {
        val cp = sampleClasspath() ?: return
        // Five runs. The double-resume bug passed ~1 time in 6, so a single run
        // was not evidence of anything.
        val counts = (1..5).map { JdiSession(config(cp)).run(null).events.size }
        assertTrue("trace is flaky across runs: $counts", counts.all { it > 0 })
        assertEquals("event count is not deterministic: $counts", 1, counts.distinct().size)
    }
}
