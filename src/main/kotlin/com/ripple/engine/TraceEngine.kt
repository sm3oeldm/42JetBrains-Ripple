package com.ripple.engine

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.ripple.util.PsiMethodUtil

/**
 * Orchestrates a JDI trace run (§3.2 / §3.4).
 *
 * Exists so the trace pipeline has ONE entry point instead of living inside an
 * action. Anything that wants a trace — the Trace This Method action, the time
 * machine scrubber, a future gutter action, a test — calls through here.
 *
 * Split in two on purpose:
 *  - [prepare] is PSI/document work. Needs read access; cheap; returns a plain
 *    config with no PSI references held.
 *  - [run] is the blocking JDI session. No PSI, no EDT, safe on a background
 *    thread, and holds nothing that a later PSI change could invalidate.
 *
 * That split is what keeps a long trace from pinning PSI, and it is why the
 * config carries only primitives and strings.
 */
object TraceEngine {

    /** Why a trace could not be started. Each maps to one user-facing message. */
    sealed interface Failure {
        data object NoMethodAtCaret : Failure
        data object NoMainInProject : Failure
        data class NotCompiled(val simpleName: String) : Failure
    }

    sealed interface Prepared {
        data class Ok(val config: JdiTraceConfig, val target: PsiMethodUtil.Target) : Prepared
        data class Failed(val failure: Failure) : Prepared
    }

    /**
     * Resolve the caret position into a runnable trace config.
     *
     * Call with read access (the EDT has it implicitly). Returns quickly and
     * keeps no PSI beyond the returned [PsiMethodUtil.Target], which the caller
     * should only use for display.
     */
    fun prepare(
        project: Project,
        psiFile: PsiJavaFile,
        virtualFile: VirtualFile,
        caretOffset: Int
    ): Prepared {
        val target = PsiMethodUtil.methodAtCaret(psiFile, caretOffset)
            ?: return Prepared.Failed(Failure.NoMethodAtCaret)

        // The ENTRY POINT and the TRACED CLASS are different things.
        //
        // We used to require main() in the same class as the traced method, which
        // made most real code untraceable — you almost never edit the class that
        // holds main. Our own demo is the proof: PriceCalculator.applyDiscount is
        // the method worth tracing, and main lives in com.shop.Main.
        //
        // So: launch whatever entry point the project has, and set breakpoints on
        // the class we actually care about. JdiTraceConfig already carried
        // mainClass and targetClass as separate fields; only this resolver was
        // collapsing them.
        val mainClass = if (target.hasMain) {
            target.fqcn
        } else {
            PsiMethodUtil.findMainClasses(project, preferPackage = target.fqcn.substringBeforeLast('.', ""))
                .firstOrNull() ?: return Prepared.Failed(Failure.NoMainInProject)
        }

        // Resolve the classpath from the file being traced; in a single-module
        // project that is also where the entry point compiles to.
        val simpleName = target.fqcn.substringAfterLast('.')
        val launch = PsiMethodUtil.resolveLaunch(project, virtualFile, simpleName)
            ?: return Prepared.Failed(Failure.NotCompiled(simpleName))

        return Prepared.Ok(
            config = JdiTraceConfig(
                javaBin = launch.javaBin,
                classpath = launch.classpath,
                mainClass = mainClass,
                targetClass = target.fqcn,
                methodQualifiedName = target.methodQualifiedName,
                lines = target.lineRange
            ),
            target = target
        )
    }

    /**
     * Run the trace and store it. Blocking — background thread only.
     *
     * Throws [com.intellij.openapi.progress.ProcessCanceledException] if the user
     * cancels, and [IllegalStateException] if the debuggee never loaded the target
     * class (the message carries the debuggee's own output, which is what you
     * actually need to diagnose a bad classpath).
     */
    fun run(project: Project, config: JdiTraceConfig, indicator: ProgressIndicator?): TraceSession {
        val session = JdiSession(config).run(indicator)
        TraceStore.getInstance(project).put(session)
        return session
    }

    /** Last trace recorded for [methodQualifiedName], if any. */
    fun lastSession(project: Project, methodQualifiedName: String): TraceSession? =
        TraceStore.getInstance(project).get(methodQualifiedName)
}
