package com.ripple

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.ripple.blast.BlastResult
import com.ripple.engine.BlastTraceResult
import java.util.concurrent.atomic.AtomicReference

/**
 * The last analysis, so features other than the one that produced it can use it.
 *
 * "Analyze" computes a blast radius and a recording; test generation consumes
 * both. Without somewhere to put them, the second feature would have to redo
 * the first — including relaunching the debuggee, which takes seconds and would
 * make the button feel broken.
 *
 * Deliberately in memory only. A recording is a snapshot of one run at one
 * moment; persisting it across restarts would hand people stale evidence and
 * invite them to trust it.
 */
@Service(Service.Level.PROJECT)
class RippleState {

    /** Blast radius and recording always move together, so they are one value. */
    data class Analysis(
        val blast: BlastResult,
        val trace: BlastTraceResult?,
        /** Wall-clock millis, supplied by the caller so this class stays testable. */
        val recordedAtMillis: Long
    )

    // Written from a background task, read from the EDT.
    private val last = AtomicReference<Analysis?>(null)

    /**
     * Methods we have already written a test for, by NavigationKey id.
     *
     * Without this, pressing "Write the Missing Tests" twice regenerates every
     * file: the red list is a snapshot from the last Analyze and does not know
     * a test was just produced for it. The second run costs API calls, takes
     * just as long, and replaces perfectly good files with different ones —
     * all benefit-free.
     *
     * In memory and per session on purpose. Once the generated tests are real
     * files in the project, the coverage pass stops listing those methods as
     * uncovered anyway, so this only has to bridge the gap between generating
     * and re-analysing.
     */
    private val generated = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun put(blast: BlastResult, trace: BlastTraceResult?, atMillis: Long) {
        last.set(Analysis(blast, trace, atMillis))
    }

    fun latest(): Analysis? = last.get()

    /** Record that [fileName] was written for [nodeId]. */
    fun markGenerated(nodeId: String, fileName: String) {
        generated[nodeId] = fileName
    }

    /** The file already written for [nodeId], or null. */
    fun generatedFor(nodeId: String): String? = generated[nodeId]

    /** Forget one, so the next run writes it again. */
    fun forgetGenerated(nodeId: String) {
        generated.remove(nodeId)
    }

    fun forgetAllGenerated() = generated.clear()

    fun clear() {
        last.set(null)
        generated.clear()
    }

    companion object {
        fun getInstance(project: Project): RippleState = project.service()
    }
}
