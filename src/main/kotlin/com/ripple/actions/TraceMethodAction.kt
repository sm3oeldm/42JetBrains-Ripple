package com.ripple.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

// Phase 0 stub: proves action wiring + plugin load. Real JDI engine lands in Phase 1.
class TraceMethodAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Ripple")
            .createNotification(
                "Ripple loaded. Tracing engine lands in Phase 1.",
                NotificationType.INFORMATION
            )
            .notify(e.project)
    }
}
