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

    /**
     * Compile the sources ourselves when the IDE cannot.
     *
     * Not every project gives IntelliJ something to build. A folder opened
     * directly, or a Gradle project whose import never completed, has no module
     * and no compiler output — `CompilerManager.make` silently does nothing, the
     * classes stay stale, and [StalenessCheck] then refuses to record. Correct,
     * but useless: the user edited a file and the tool just says no.
     *
     * So we fall back to javac with -g, straight into the same output directory
     * we are about to trace. It is the same command the user would type, and it
     * means "edit a method, press Analyze" works on a project the IDE cannot
     * build for us.
     *
     * @return true if anything was compiled.
     */
    fun javacFallback(
        project: Project,
        javaBin: String,
        classpathRoot: String,
        indicator: ProgressIndicator?
    ): Boolean {
        val javac = javacBesideJava(javaBin) ?: return false
        val base = project.basePath ?: return false

        val sources = java.io.File(base).walkTopDown()
            .onEnter { it.name != "build" && it.name != "out" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "java" }
            .map { it.absolutePath }
            .toList()
        if (sources.isEmpty()) return false

        indicator?.text = "Ripple: compiling ${sources.size} source file(s)"
        return try {
            // -g keeps local variable names, which is the whole point: without
            // them the trace records line hits with no values.
            val cmd = listOf(javac, "-g", "-d", classpathRoot) + sources
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                log.warn("Ripple: javac fallback timed out")
                return false
            }
            if (process.exitValue() != 0) {
                log.warn("Ripple: javac fallback failed: ${output.take(400)}")
                return false
            }
            log.info("Ripple: compiled ${sources.size} file(s) into $classpathRoot")
            true
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: Exception) {
            log.warn("Ripple: javac fallback could not run (${e.javaClass.simpleName})")
            false
        }
    }

    /**
     * The javac sitting next to the java we are about to launch.
     *
     * Note the local is NOT called `java`: that shadows the `java` package, and
     * the very next `java.io.File` then resolves to the variable instead.
     */
    private fun javacBesideJava(javaBin: String): String? {
        val javaExe = java.io.File(javaBin)
        val exe = if (javaExe.name.endsWith(".exe")) "javac.exe" else "javac"
        val candidate = java.io.File(javaExe.parentFile ?: return null, exe)
        return if (candidate.isFile) candidate.absolutePath else null
    }
}
