package com.ripple.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.ripple.blast.BlastResult
import com.ripple.blast.Execution
import com.ripple.blast.BlastScanner
import com.ripple.blast.ChangeDetector
import com.ripple.blast.TraceCorrelator
import com.ripple.blast.ui.BlastToolWindowFactory
import com.ripple.engine.TraceEngine
import com.ripple.engine.TraceSession
import com.ripple.inlay.TraceTrailRenderer

/**
 * "Ripple: Analyze" — the whole product in one keystroke.
 *
 *   1. Work out what changed (falling back to the method at the caret, so a
 *      clean working tree still demos).
 *   2. Compute the blast radius and the red list, and show it.
 *   3. Record the edited method running, and paint the values inline.
 *   4. Correlate the recording back onto the radius, so nodes that actually ran
 *      are marked EXECUTED.
 *
 * Steps 3 and 4 are best-effort: if there is no entry point to launch, or the
 * class was compiled without debug info, the blast radius still stands on its
 * own and the user is told why the recording did not happen. A partial result
 * is far better than an error dialog in front of judges.
 */
class RippleAnalyzeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        // Never throw from update(): it runs on every menu open and every
        // toolbar tick, and the platform logs a throw as an IDE error.
        e.presentation.isEnabled = project != null &&
            !project.isDisposed &&
            !DumbService.getInstance(project).isDumb &&
            e.getData(CommonDataKeys.PSI_FILE) is PsiJavaFile
        e.presentation.isVisible = project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (project.isDisposed) return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) as? PsiJavaFile ?: return
        val vfile: VirtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val caret = editor.caretModel.offset

        // Resolve the trace target here, on the EDT, where read access is
        // implicit. The config it returns holds no PSI, so it is safe to carry
        // onto the background thread below.
        val prepared = TraceEngine.prepare(project, psiFile, vfile, caret)

        BlastToolWindowFactory.showLoading(project)

        object : Task.Backgroundable(project, "Ripple: analyzing", true) {
            private var result: BlastResult = BlastResult.empty()
            private var session: TraceSession? = null
            private var traceProblem: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                indicator.text = "Ripple: finding what changed"
                // CaretMethod, not None: on a clean working tree the panel would
                // otherwise be empty, which reads as "broken" rather than "no
                // changes". The ChangeSet records which path was taken.
                result = BlastScanner.detectAndScan(
                    project,
                    ChangeDetector.Fallback.CaretMethod(vfile, caret),
                    indicator
                )

                indicator.checkCanceled()

                when (prepared) {
                    is TraceEngine.Prepared.Ok -> {
                        indicator.text = "Ripple: recording ${prepared.config.methodQualifiedName}"
                        session = try {
                            TraceEngine.run(project, prepared.config, indicator)
                        } catch (t: com.intellij.openapi.progress.ProcessCanceledException) {
                            throw t
                        } catch (t: Throwable) {
                            // A failed recording must not lose the blast radius.
                            traceProblem = t.message ?: t.javaClass.simpleName
                            null
                        }
                    }
                    is TraceEngine.Prepared.Failed -> {
                        traceProblem = describe(prepared.failure)
                    }
                }

                // NOT_RECORDED, not NEVER_EXECUTED, for anything we did not
                // instrument.
                //
                // We record ONE method today, so every other node in the radius is
                // simply unobserved. Defaulting those to NEVER_EXECUTED produced a
                // flatly false claim on screen: Main.main was badged "never ran"
                // when main is the entry point that launched the program. One
                // obviously-wrong badge discredits every other badge next to it.
                //
                // "Never ran" only becomes truthful once the tracer instruments
                // the whole radius; until then we say nothing rather than
                // something wrong.
                session?.let {
                    result = TraceCorrelator.correlate(result, it, unmatched = Execution.NOT_RECORDED)
                }
            }

            override fun onSuccess() {
                BlastToolWindowFactory.showResult(project, result)
                session?.let { s ->
                    if (!editor.isDisposed) TraceTrailRenderer.render(editor, s)
                }
                notify(project, summary(result, session, traceProblem), level(result, session))
            }

            override fun onCancel() {
                BlastToolWindowFactory.showResult(project, result)
                notify(project, "Ripple: analysis cancelled.", NotificationType.INFORMATION)
            }

            override fun onThrowable(error: Throwable) {
                BlastToolWindowFactory.showResult(project, BlastResult.empty())
                notify(project, "Ripple failed: ${error.message}", NotificationType.ERROR)
            }
        }.queue()
    }

    private fun describe(f: TraceEngine.Failure): String = when (f) {
        TraceEngine.Failure.NoMethodAtCaret -> "no method at the caret to record"
        TraceEngine.Failure.NoMainInProject -> "no runnable main() in this project to launch"
        is TraceEngine.Failure.NotCompiled -> "${f.simpleName}.class not found — build first (javac -g)"
    }

    private fun summary(result: BlastResult, session: TraceSession?, problem: String?): String {
        if (result.isEmpty) return "Ripple: nothing to analyze — no changes and no method at the caret."
        val head = "${result.totalInRadius} in blast radius, ${result.redList.size} with no test " +
            "(${result.uncoveredPercent}%)"
        val tail = when {
            session != null -> " · recorded ${session.events.size} snapshots"
            problem != null -> " · not recorded: $problem"
            else -> ""
        }
        val capped = if (result.truncated) " · capped" else ""
        return "Ripple: $head$tail$capped"
    }

    private fun level(result: BlastResult, session: TraceSession?): NotificationType = when {
        result.isEmpty -> NotificationType.WARNING
        session == null -> NotificationType.WARNING
        else -> NotificationType.INFORMATION
    }

    private fun notify(project: Project, text: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Ripple")
                .createNotification(text, type)
                .notify(project)
        }
    }
}
