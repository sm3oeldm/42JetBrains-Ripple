package ad42.devrescue

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JPanel

class OpenSettingsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }

    override fun actionPerformed(e: AnActionEvent) {
        val s = DevRescueSettings.getInstance()
        val dlg = object : DialogWrapper(e.project, true) {
            val url = JBTextField(s.baseUrl).apply { columns = 40 }
            val model = JBTextField(s.model).apply { columns = 40 }
            val key = JBPasswordField().apply {
                columns = 40
                text = s.getApiKey()
            }
            init { title = "DevRescue Settings"; init() }
            override fun createCenterPanel(): JComponent = JPanel(GridLayout(0, 1, 0, 6)).apply {
                border = JBUI.Borders.empty(12)
                add(JBLabel("Base URL (OpenAI-compatible):")); add(url)
                add(JBLabel("Model:")); add(model)
                add(JBLabel("API key (stored in system keychain, empty = offline):")); add(key)
            }
            fun applyTo(s: DevRescueSettings) {
                s.baseUrl = url.text
                s.model = model.text.ifBlank { s.model }
                s.setApiKey(String(key.password))
            }
        }
        if (dlg.showAndGet()) {
            dlg.applyTo(s)
            e.project?.let { DevRescueToolWindowFactory.refreshStatus(it) }
        }
    }
}
