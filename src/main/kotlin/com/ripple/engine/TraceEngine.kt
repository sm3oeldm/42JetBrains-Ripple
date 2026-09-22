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
        data object NoMainInClass : Failure
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

        // Phase 1 scope (§3.4 step 2): the program is entered via main() in the
        // same class. Anything else needs run-configuration selection, which the
        // spec explicitly defers.
        if (!target.hasMain) return Prepared.Failed(Failure.NoMainInClass)

        val simpleName = target.fqcn.substringAfterLast('.')
        val launch = PsiMethodUtil.resolveLaunch(project, virtualFile, simpleName)
            ?: return Prepared.Failed(Failure.NotCompiled(simpleName))

        return Prepared.Ok(
            config = JdiTraceConfig(
                javaBin = launch.javaBin,
                classpath = launch.classpath,
                mainClass = target.fqcn,
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
