package ad42.devrescue

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.wm.ToolWindowManager

class ExplainErrorAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)

        // Read PSI-adjacent data inside a read action (never hold it across writes).
        val (errorText, codeContext) = ReadAction.compute<Pair<String, String>, RuntimeException> {
            val err = editor?.let {
                val sel = it.selectionModel.selectedText
                if (!sel.isNullOrBlank()) sel
                else it.document.text.lines().getOrNull(it.caretModel.logicalPosition.line) ?: ""
            } ?: ""
            val ctx = editor?.document?.text?.take(3000) ?: ""
            err to ctx
        }

        ToolWindowManager.getInstance(project).getToolWindow("DevRescue")?.show()
        if (errorText.isBlank()) {
            DevRescueToolWindowFactory.showError(project, OfflineRuleEngine.explain(""), "", false, "")
            return
        }
        DevRescueToolWindowFactory.showLoading(project)
        val frame = TraceParser.firstUserFrame(errorText)
        LlmService.getInstance().explainInBackground(project, errorText, codeContext) { exp, usedAi, model ->
            DevRescueToolWindowFactory.showError(project, exp, errorText.take(1200), usedAi, model, frame)
        }
    }
}
