package com.ripple.rewind

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiJavaFile
import com.ripple.engine.TraceStore
import com.ripple.util.PsiMethodUtil

/**
 * Opens the Rewind scrubber for the last recording of the method at the caret.
 *
 * PSI is touched only inside a read action, and only a String
 * (`methodQualifiedName`) escapes it — no PsiMethod is retained past the read,
 * because PsiMethod identity is not valid outside one.
 */
class OpenScrubberAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible =
            e.project != null && file is PsiJavaFile && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) as? PsiJavaFile ?: return
        if (editor.isDisposed) return

        val caretOffset = editor.caretModel.offset
        val methodQualifiedName: String? = ReadAction.compute<String?, RuntimeException> {
            PsiMethodUtil.methodAtCaret(psiFile, caretOffset)?.methodQualifiedName
        }

        if (methodQualifiedName == null) {
            notify(project, "Place the caret inside a Java method to rewind it.", NotificationType.WARNING)
            return
        }

        val session = TraceStore.getInstance(project).get(methodQualifiedName)
        if (session == null) {
            notify(
                project,
                "No recording for $methodQualifiedName yet — run \"Ripple: Analyze This Change\" (Ctrl+Alt+R) first.",
                NotificationType.WARNING
            )
            return
        }
        if (session.events.isEmpty()) {
            notify(
                project,
                "$methodQualifiedName never ran, so there is nothing to scrub. " +
                    "Either nothing calls it from the entry point, or the class was " +
                    "compiled without debug info (javac -g).",
                NotificationType.WARNING
            )
            return
        }

        ScrubberPanel.open(project, editor, session)
    }

    private fun notify(project: Project, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Ripple")
            .createNotification(text, type)
            .notify(project)
    }
}
