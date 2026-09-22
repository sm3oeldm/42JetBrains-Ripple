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
import com.ripple.blast.BlastScanner
import com.ripple.blast.ChangeDetector
import com.ripple.engine.BlastExecutionCorrelator
import com.ripple.engine.BlastTraceConfig
import com.ripple.engine.BlastTraceResult
import com.ripple.engine.BlastTraceSession
import com.ripple.engine.BlastTraceTargets
import com.ripple.engine.PsiMethodBodyLineResolver
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

        BlastToolWindowFactory.showLoading(project)

        object : Task.Backgroundable(project, "Ripple: analyzing", true) {
            private var result: BlastResult = BlastResult.empty()
            private var session: TraceSession? = null
            private var blastTrace: BlastTraceResult? = null
            private var traceProblem: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                // Resolve the trace target on the BACKGROUND thread, not the EDT.
                //
                // prepare() ends in resolveLaunch(), which does blocking disk I/O:
                // isDirectory() and listFiles() across several candidate roots. On
                // a cold cache or a network-mapped project that froze the UI for
                // seconds BEFORE the progress bar appeared, so the user saw a dead
                // IDE rather than a running task.
                //
                // PSI needs a read action off the EDT. The returned config holds
                // no PSI, so it is safe to use for the rest of this task.
                indicator.text = "Ripple: resolving target"
                val prepared = com.intellij.openapi.application.ReadAction.compute<TraceEngine.Prepared, RuntimeException> {
                    TraceEngine.prepare(project, psiFile, vfile, caret)
                }

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
                        // Instrument the WHOLE blast radius, not just the edited
                        // method. This is the integration: the radius decides what
                        // is worth recording (which is what keeps JDI tracing fast
                        // enough to be usable), and the recording then tells us
                        // which of those nodes actually ran.
                        val targets = BlastTraceTargets.from(
                            result,
                            resolver = PsiMethodBodyLineResolver(project)
                        )
                        indicator.text = "Ripple: recording ${targets.size} methods"

                        blastTrace = try {
                            if (targets.isEmpty()) null else BlastTraceSession(
                                BlastTraceConfig(
                                    javaBin = prepared.config.javaBin,
                                    classpath = prepared.config.classpath,
                                    mainClass = prepared.config.mainClass,
                                    targets = targets
                                )
                            ).run(indicator)
                        } catch (t: com.intellij.openapi.progress.ProcessCanceledException) {
                            throw t
                        } catch (t: Throwable) {
                            // A failed recording must not lose the blast radius.
                            traceProblem = t.message ?: t.javaClass.simpleName
                            null
                        }

                        // Inlay chips still come from the edited method's slice.
                        session = blastTrace?.sessionFor(changedRootId(result))
                    }
                    is TraceEngine.Prepared.Failed -> {
                        traceProblem = describe(prepared.failure)
                    }
                }

                // Now "never ran" is a claim we are entitled to make.
                //
                // BlastExecutionCorrelator only judges nodes in
                // BlastTraceResult.targetedNodeIds — nodes we actually
                // instrumented. Anything outside that stays NOT_RECORDED, so we
                // can never repeat the earlier bug of badging Main.main as
                // "never ran" when main is what launched the program.
                //
                // One exception, and it matters: if the recording TIMED OUT the
                // debuggee was killed mid-flight, so a node that would have run
                // later looks identical to one that never runs at all. In that
                // case we withhold the judgement rather than publish a guess.
                blastTrace?.let { t ->
                    if (t.timedOut) {
                        traceProblem = "recording timed out after ${t.orderedEvents.size} events — " +
                            "'never ran' withheld, it would not be trustworthy"
                    } else {
                        result = BlastExecutionCorrelator.correlate(result, t)
                    }
                }
            }

            override fun onSuccess() {
                // Publish before painting: the generate-tests action reads this,
                // and re-deriving it would mean relaunching the debuggee.
                com.ripple.RippleState.getInstance(project)
                    .put(result, blastTrace, System.currentTimeMillis())
                BlastToolWindowFactory.showResult(project, result)
                session?.let { s ->
                    if (!editor.isDisposed) TraceTrailRenderer.render(editor, s)
                }
                notify(project, summary(result, blastTrace, traceProblem), level(result, blastTrace))
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

    /** The edited method, whose slice of the recording drives the inline chips. */
    private fun changedRootId(result: BlastResult): String =
        result.roots.firstOrNull()?.key?.id ?: ""

    private fun describe(f: TraceEngine.Failure): String = when (f) {
        TraceEngine.Failure.NoMethodAtCaret -> "no method at the caret to record"
        TraceEngine.Failure.NoMainInProject -> "no runnable main() in this project to launch"
        is TraceEngine.Failure.NotCompiled -> "${f.simpleName}.class not found — build first (javac -g)"
    }

    private fun summary(result: BlastResult, blastTrace: BlastTraceResult?, problem: String?): String {
        if (result.isEmpty) return "Ripple: nothing to analyze — no changes and no method at the caret."
        val head = "${result.totalInRadius} in blast radius, ${result.redList.size} with no test " +
            "(${result.uncoveredPercent}%)"
        val trace = blastTrace
        val neverRan = result.distinctNodes.count { it.isUnprovenAndUnrun }
        val tail = when {
            trace != null && !trace.timedOut ->
                " · recorded ${trace.orderedEvents.size} snapshots across " +
                    "${trace.executedNodeIds.size}/${trace.targetedNodeIds.size} methods" +
                    if (neverRan > 0) " · $neverRan never ran" else ""
            problem != null -> " · not recorded: $problem"
            else -> ""
        }
        val capped = if (result.truncated) " · capped" else ""
        return "Ripple: $head$tail$capped"
    }

    private fun level(result: BlastResult, blastTrace: BlastTraceResult?): NotificationType = when {
        result.isEmpty -> NotificationType.WARNING
        blastTrace == null || blastTrace.timedOut -> NotificationType.WARNING
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
