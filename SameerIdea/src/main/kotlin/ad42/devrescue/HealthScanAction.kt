package ad42.devrescue

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

data class HealthIssue(val line: Int, val kind: String, val message: String, val suggestion: String)

object HealthScanner {
    // Curated high-harm checks only (not style noise): silent failures, leaks, secrets.
    fun scan(fileName: String, text: String): Pair<Int, List<HealthIssue>> {
        val issues = mutableListOf<HealthIssue>()
        val lines = text.lines()
        val isPy = fileName.endsWith(".py")
        val isTest = fileName.contains("Test", ignoreCase = true) || fileName.contains("test_")
        lines.forEachIndexed { idx, line ->
            val ln = idx + 1
            val t = line.trim()
            if (t.startsWith("//") || t.startsWith("#") || t.startsWith("*")) {
                // Still flag TODOs in comments
                if (t.contains("TODO") && !t.contains(":"))
                    issues += HealthIssue(ln, "todo", "TODO without owner", "Use TODO(name): description + ticket")
                return@forEachIndexed
            }
            if ((t.contains("System.out.println") || t.contains("System.err.println")) && !isTest)
                issues += HealthIssue(ln, "println", "System.out.println in production code", "Replace with logger")
            if (t.contains(".printStackTrace("))
                issues += HealthIssue(ln, "stacktrace", "printStackTrace() loses context", "Use logger.error(\"msg\", e)")
            if (t.contains("print(") && isPy && !isTest)
                issues += HealthIssue(ln, "py-print", "print() left in Python code", "Use logging module")
            if (t.contains("TODO") && !t.contains(":") && !t.contains("http"))
                issues += HealthIssue(ln, "todo", "TODO without owner", "Use TODO(name): description")
            if (t.contains("Thread.sleep(") || t.contains("time.sleep("))
                issues += HealthIssue(ln, "sleep", "Blocking sleep", "Use scheduler / timeout API")
            if (t == "except:" || t == "except :")
                issues += HealthIssue(ln, "bare-except", "Bare except swallows everything", "Use except Exception:")
            if (t.matches(Regex(""".*catch\s*\(\s*Exception\s+\w+\s*\)\s*\{\s*\}.*""")))
                issues += HealthIssue(ln, "empty-catch", "Empty catch block", "Log it or rethrow")
            else if (t.contains("catch (Exception") || t.contains("catch(Exception"))
                issues += HealthIssue(ln, "swallow", "Catching generic Exception", "Catch specific type + log")
            if (looksLikeSecret(t))
                issues += HealthIssue(ln, "secret", "Possible hardcoded secret", "Move to env var / vault")
        }
        if (lines.size > 300) issues += HealthIssue(1, "big-file", "File has ${lines.size} lines", "Split into smaller units")
        var run = 0; var runStart = 1
        lines.forEachIndexed { idx, line ->
            if (line.isBlank()) {
                if (run > 60) issues += HealthIssue(runStart, "long-block", "Block of $run lines", "Extract method")
                run = 0
            } else { if (run == 0) runStart = idx + 1; run++ }
        }
        val score = (100 - issues.size * 8).coerceIn(0, 100)
        return score to issues
    }

    private fun looksLikeSecret(t: String): Boolean {
        val low = t.lowercase()
        val keyish = listOf("password", "passwd", "secret", "api_key", "apikey", "token", "sk-", "aws_", "ghp_", "xoxb-")
            .any { low.contains(it) }
        if (!keyish) return false
        if (low.contains("getenv") || low.contains("getenv(") || low.contains("system.getenv") ||
            low.contains("os.environ") || low.contains("passwordsafe") || low.contains("vault")) return false
        // Quoted literal with decent entropy/length
        val q = Regex("""["'][A-Za-z0-9_\-+/=]{12,}["']""").containsMatchIn(t)
        return q && t.contains("=")
    }
}

class HealthScanAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val vfile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val (text, name) = ReadAction.compute<Pair<String, String>, RuntimeException> {
            editor.document.text to (vfile.name)
        }
        val (score, issues) = HealthScanner.scan(name, text)
        ToolWindowManager.getInstance(project).getToolWindow("DevRescue")?.show()
        DevRescueToolWindowFactory.showHealth(project, vfile, name, score, issues)

        if (issues.any { it.kind == "println" }) {
            val result = Messages.showYesNoDialog(
                project, "Replace System.out/err.println with logger calls in this file?",
                "DevRescue Quick-Fix", "Fix it", "Skip", null
            )
            if (result == 0) {
                WriteCommandAction.runWriteCommandAction(project) {
                    val doc = editor.document
                    var t = doc.text
                    t = t.replace("System.out.println", "System.getLogger(\"devrescue\").log(System.Logger.Level.INFO, \"\") // was println:")
                    t = t.replace("System.err.println", "System.getLogger(\"devrescue\").log(System.Logger.Level.ERROR, \"\") // was err:")
                    doc.setText(t)
                }
                DevRescueToolWindowFactory.showNotice(project, "println calls replaced. Re-scan to verify.")
            }
        }
    }
}
