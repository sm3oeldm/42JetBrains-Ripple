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
import com.intellij.psi.PsiJavaFile
import com.ripple.engine.JdiSession
import com.ripple.engine.JdiTraceConfig
import com.ripple.engine.TraceSession
import com.ripple.engine.TraceStore
import com.ripple.inlay.TraceTrailRenderer
import com.ripple.util.PsiMethodUtil

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

        // Runs on EDT: PSI/document reads are safe here.
        val target = PsiMethodUtil.methodAtCaret(psiFile, editor.caretModel.offset)
        if (target == null) {
            notify(project, "Place the caret inside a Java method first.", NotificationType.WARNING)
            return
        }
        if (!target.hasMain) {
            notify(
                project,
                "Phase 1 traces the program from main() in the same class — " +
                    "put the caret in a method of a class with a main().",
                NotificationType.WARNING
            )
            return
        }
        val simpleName = target.fqcn.substringAfterLast('.')
        val launch = PsiMethodUtil.resolveLaunch(project, vfile, simpleName)
        if (launch == null) {
            notify(
                project,
                "Could not find compiled $simpleName.class — build the project first (javac -g).",
                NotificationType.WARNING
            )
            return
        }

        val config = JdiTraceConfig(
            javaBin = launch.javaBin,
            classpath = launch.classpath,
            mainClass = target.fqcn,
            targetClass = target.fqcn,
            methodQualifiedName = target.methodQualifiedName,
            lines = target.lineRange
        )

        object : Task.Backgroundable(project, "Ripple: tracing ${target.methodQualifiedName}", true) {
            var session: TraceSession? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                session = JdiSession(config).run(indicator)
            }

            override fun onSuccess() {
                val s = session ?: return
                TraceStore.getInstance(project).put(s)
                if (editor.isDisposed) return
                TraceTrailRenderer.render(editor, s)
                val tail = if (s.truncated) " (capped — partial trail)" else ""
                notify(
                    project,
                    "Traced ${s.methodQualifiedName}: ${s.events.size} snapshots$tail.",
                    if (s.events.isEmpty()) NotificationType.WARNING else NotificationType.INFORMATION
                )
            }

            override fun onThrowable(error: Throwable) {
                notify(project, "Trace failed: ${error.message}", NotificationType.ERROR)
            }
        }.queue()
    }

    private fun notify(project: com.intellij.openapi.project.Project, text: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Ripple")
                .createNotification(text, type)
                .notify(project)
        }
    }
}
