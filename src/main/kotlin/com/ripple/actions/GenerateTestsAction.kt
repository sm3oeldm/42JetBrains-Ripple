package com.ripple.actions

import com.intellij.ide.scratch.ScratchRootType
import com.intellij.lang.java.JavaLanguage
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.ripple.RippleState
import com.ripple.ai.GroqClient
import com.ripple.ai.RippleSecrets
import com.ripple.ai.TestGenerator
import com.ripple.blast.BlastNode

/**
 * "Ripple: Write the Missing Tests".
 *
 * Takes the red list — the methods the blast radius says nothing tests — and
 * writes a real test for each, grounded in the values the recording actually
 * observed.
 *
 * This is the point where the two halves of the plugin stop being a pairing and
 * become one mechanism. The red list alone knows WHICH methods need a test but
 * has no data to write one with. The recording alone has the data but does not
 * know which tests are missing. Neither can do this by itself.
 *
 * Degrades on purpose: no key, no network, or a rejected request all fall back
 * to the deterministic template generator. A demo machine with bad wifi still
 * produces test files.
 */
class GenerateTestsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val ready = project != null &&
            !project.isDisposed &&
            !DumbService.getInstance(project).isDumb &&
            RippleState.getInstance(project).latest()?.blast?.redList?.isNotEmpty() == true
        e.presentation.isEnabled = ready
        e.presentation.isVisible = project != null
        e.presentation.description = if (ready) {
            "Write tests for the methods nothing is covering, using the values the recording observed"
        } else {
            "Run Ripple: Analyze first — there is no red list yet"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (project.isDisposed) return

        val analysis = RippleState.getInstance(project).latest() ?: run {
            notify(project, "Run Ripple: Analyze first.", NotificationType.WARNING); return
        }
        val redList = analysis.blast.redList
        if (redList.isEmpty()) {
            notify(project, "Nothing to write — every method in the blast radius is covered.", NotificationType.INFORMATION)
            return
        }

        val apiKey = RippleSecrets.groqKey(project)

        object : Task.Backgroundable(project, "Ripple: writing the missing tests", true) {
            private val written = ArrayList<String>()
            private var aiCount = 0
            private var fallbackCount = 0
            private var firstProblem: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                redList.forEachIndexed { index, node ->
                    indicator.checkCanceled()
                    indicator.fraction = index.toDouble() / redList.size
                    indicator.text = "Ripple: ${node.displayName.substringBefore('(')}"

                    val ctx = TestGenerator.contextFor(project, node, analysis.blast, analysis.trace)
                    val code = when {
                        ctx == null -> null
                        apiKey.isNullOrBlank() -> null
                        else -> generate(apiKey, ctx)
                    }

                    val source = code ?: DeterministicTestTemplate.forNode(node, ctx).also { fallbackCount++ }
                    if (code != null) aiCount++

                    // Name the FILE after the class the code actually declares.
                    // Java requires them to match, and generating
                    // ReceiptRenderTest.java containing `public class ReceiptTest`
                    // produces a file that cannot compile - which is worse than
                    // no file, because it looks like it worked.
                    val declared = GroqClient.publicClassName(source)
                    val fallbackName = node.key.fqcn.substringAfterLast('.').substringAfterLast('$') +
                        node.key.methodName.replaceFirstChar { it.uppercase() } + "Test"
                    val name = (declared ?: fallbackName) + ".java"
                    openScratch(project, name, source)
                    written += name
                }
            }

            private fun generate(key: String, ctx: TestGenerator.Context): String? =
                when (val r = GroqClient.complete(key, TestGenerator.systemPrompt(), TestGenerator.userPrompt(ctx))) {
                    is GroqClient.Result.Ok -> GroqClient.extractCode(r.content)
                    is GroqClient.Result.Failed -> {
                        if (firstProblem == null) firstProblem = r.reason
                        null
                    }
                }

            override fun onSuccess() {
                val head = "Ripple: wrote ${written.size} test${if (written.size == 1) "" else "s"}"
                val detail = buildString {
                    if (aiCount > 0) append(" · $aiCount from observed values")
                    if (fallbackCount > 0) {
                        append(" · $fallbackCount from the offline template")
                        firstProblem?.let { append(" ($it)") }
                        if (apiKey.isNullOrBlank()) append(" (no API key configured)")
                    }
                }
                notify(project, head + detail, if (aiCount > 0) NotificationType.INFORMATION else NotificationType.WARNING)
            }

            override fun onCancel() = notify(project, "Ripple: test generation cancelled.", NotificationType.INFORMATION)

            override fun onThrowable(error: Throwable) =
                notify(project, "Ripple: could not write tests — ${error.message}", NotificationType.ERROR)
        }.queue()
    }

    private fun openScratch(project: Project, fileName: String, text: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val file = ScratchRootType.getInstance()
                .createScratchFile(project, fileName, JavaLanguage.INSTANCE, text)
            file?.let { FileEditorManager.getInstance(project).openFile(it, true) }
        }
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

/**
 * The offline path. Not a placeholder — the thing that runs when the venue wifi
 * dies, which at a hackathon is a when, not an if.
 */
internal object DeterministicTestTemplate {

    fun forNode(node: BlastNode, ctx: TestGenerator.Context?): String {
        val simpleClass = node.key.fqcn.substringAfterLast('.').substringAfterLast('$')
        val testClass = simpleClass + node.key.methodName.replaceFirstChar { it.uppercase() } + "Test"
        val pkg = ctx?.packageName
        val observed = ctx?.observedValues.orEmpty()

        return buildString {
            pkg?.let { appendLine("package $it;"); appendLine() }
            appendLine("import org.junit.Test;")
            appendLine("import static org.junit.Assert.*;")
            appendLine()
            appendLine("/**")
            appendLine(" * Generated by Ripple because nothing in the test suite reaches")
            appendLine(" * ${node.displayName} — ${node.hops} hop(s) from the method you changed.")
            if (observed.isNotEmpty()) {
                appendLine(" *")
                appendLine(" * Values observed during a real run:")
                observed.forEach { appendLine(" *   $it") }
            } else {
                appendLine(" *")
                appendLine(" * This method never executed in the recorded run, so there are no")
                appendLine(" * observed values to pin. Choose inputs deliberately.")
            }
            appendLine(" */")
            appendLine("public class $testClass {")
            appendLine()
            appendLine("    @Test")
            appendLine("    public void ${node.key.methodName}_characterizesCurrentBehaviour() {")
            appendLine("        // TODO call ${node.displayName} with the values above and assert the result.")
            appendLine("        fail(\"not yet written\");")
            appendLine("    }")
            appendLine("}")
        }
    }
}
