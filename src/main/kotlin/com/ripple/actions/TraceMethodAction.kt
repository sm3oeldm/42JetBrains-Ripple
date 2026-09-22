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
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import com.ripple.engine.TraceEngine
import com.ripple.engine.TraceSession
import com.ripple.inlay.TraceTrailRenderer

/**
 * "Trace This Method" (§3.4 step 1). Thin: resolve on the EDT, hand everything
 * else to [TraceEngine], render on success.
 */
class TraceMethodAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file is PsiJavaFile
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) as? PsiJavaFile ?: return
        val vfile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        // On the EDT: read access is implicit, so PSI/document reads are safe.
        when (val prepared = TraceEngine.prepare(project, psiFile, vfile, editor.caretModel.offset)) {
            is TraceEngine.Prepared.Failed -> notify(project, message(prepared.failure), NotificationType.WARNING)
            is TraceEngine.Prepared.Ok -> {
                val config = prepared.config
                object : Task.Backgroundable(project, "Ripple: tracing ${config.methodQualifiedName}", true) {
                    var session: TraceSession? = null

                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        session = TraceEngine.run(project, config, indicator)
                    }

                    override fun onSuccess() {
                        val s = session ?: return
                        if (editor.isDisposed) return
                        TraceTrailRenderer.render(editor, s)
                        val tail = if (s.truncated) " (capped — partial trail)" else ""
                        notify(
                            project,
                            "Traced ${s.methodQualifiedName}: ${s.events.size} snapshots$tail.",
                            if (s.events.isEmpty()) NotificationType.WARNING else NotificationType.INFORMATION
                        )
                    }

                    // onCancel is separate from onThrowable: cancelling is not an error
                    // and must not pop a red balloon.
                    override fun onCancel() {
                        notify(project, "Trace cancelled.", NotificationType.INFORMATION)
                    }

                    override fun onThrowable(error: Throwable) {
                        notify(project, "Trace failed: ${error.message}", NotificationType.ERROR)
                    }
                }.queue()
            }
        }
    }

    private fun message(f: TraceEngine.Failure): String = when (f) {
        TraceEngine.Failure.NoMethodAtCaret ->
            "Place the caret inside a Java method first."
        TraceEngine.Failure.NoMainInClass ->
            "Ripple traces the program from main() in the same class — " +
                "put the caret in a method of a class that has a main()."
        is TraceEngine.Failure.NotCompiled ->
            "Could not find compiled ${f.simpleName}.class — build the project first (javac -g)."
    }

    private fun notify(project: Project, text: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Ripple")
                .createNotification(text, type)
                .notify(project)
        }
    }
}
