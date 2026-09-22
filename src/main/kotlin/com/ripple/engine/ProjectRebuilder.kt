package com.ripple.engine

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Compile the project before tracing it.
 *
 * The point is that Ripple should work on code you just wrote. Writing a new
 * method and being told "nothing ran" because you had not rebuilt is a bad
 * answer — the tool knows how to rebuild, so it should.
 *
 * Best-effort by design. A project whose build is delegated to Gradle, or which
 * has no compiler output configured, may not produce classes this way; in that
 * case [StalenessCheck] still catches the problem and the user is told what to
 * run. Never the only line of defence.
 */
object ProjectRebuilder {

    private val log = Logger.getInstance(ProjectRebuilder::class.java)

    private const val TIMEOUT_SECONDS = 90L

    sealed interface Outcome {
        data object Succeeded : Outcome
        data object NothingToDo : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Blocking. Background threads only.
     *
     * The build itself is scheduled on the EDT because that is what
     * CompilerManager expects; this call simply waits for its callback.
     */
    fun rebuild(project: Project, indicator: ProgressIndicator?): Outcome {
        if (project.isDisposed) return Outcome.NothingToDo
        indicator?.text = "Ripple: rebuilding so the trace matches your code"

        val latch = CountDownLatch(1)
        // AtomicReference, not a local var: written on the EDT, read here.
        val outcome = java.util.concurrent.atomic.AtomicReference<Outcome>(Outcome.NothingToDo)

        try {
            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed) {
                    latch.countDown()
                    return@invokeLater
                }
                try {
                    CompilerManager.getInstance(project).make { aborted, errors, _, _ ->
                        outcome.set(
                            when {
                                aborted -> Outcome.Failed("the build was cancelled")
                                errors > 0 -> Outcome.Failed("the project has $errors compile error(s)")
                                else -> Outcome.Succeeded
                            }
                        )
                        latch.countDown()
                    }
                } catch (t: Throwable) {
                    // A project with no compilable modules throws rather than
                    // reporting; that is not an error worth surfacing.
                    log.info("Ripple: build could not be started (${t.javaClass.simpleName})")
                    outcome.set(Outcome.NothingToDo)
                    latch.countDown()
                }
            }, com.intellij.openapi.application.ModalityState.any())

            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return Outcome.Failed("the build did not finish within ${TIMEOUT_SECONDS}s")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Outcome.Failed("interrupted")
        }
        return outcome.get()
    }
}
