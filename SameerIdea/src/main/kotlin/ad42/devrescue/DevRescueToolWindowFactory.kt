package ad42.devrescue

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.ui.ColorUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JSeparator

// Clean + theme-correct: every color comes from the current LaF (Light/Dark/New UI/High-contrast).
// No hardcoded Color(), no <font color='gray'>. HTML panes get injected theme CSS.
class DevRescueToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val root = JBPanel<JBPanel<*>>(BorderLayout(0, 0))

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 10, 8)).apply {
            border = JBUI.Borders.emptyBottom(4)
        }
        val title = JBLabel("DevRescue").apply { font = font.deriveFont(Font.BOLD, 14f) }
        val subtitle = JBLabel("Error explainer • Health check").apply {
            font = font.deriveFont(12f)
            foreground = UIUtil.getContextHelpForeground()
        }
        val status = JBLabel().apply { font = font.deriveFont(12f) }
        header.add(title); header.add(subtitle); header.add(status)
        header.putClientProperty("status", status)

        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12)
        }
        val scroll = JBScrollPane(body)

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            border = JBUI.Borders.emptyTop(4)
        }
        val btnExplain = JButton("Explain Error")
        val btnScan = JButton("Scan This File")
        btnExplain.toolTipText = "Alt+Shift+E"
        btnScan.toolTipText = "Alt+Shift+H"
        btnExplain.addActionListener { runAction(project, "DevRescue.ExplainError") }
        btnScan.addActionListener { runAction(project, "DevRescue.ScanFile") }
        actions.add(btnExplain); actions.add(btnScan)

        root.add(header, BorderLayout.NORTH)
        root.add(scroll, BorderLayout.CENTER)
        root.add(actions, BorderLayout.SOUTH)

        val content = ContentFactory.getInstance().createContent(root, "", false)
        content.putUserData(ROOT_KEY, root)
        content.putUserData(BODY_KEY, body)
        toolWindow.contentManager.addContent(content)
        refreshStatus(project)
        showWelcome(project)
    }

    companion object {
        private val ROOT_KEY = com.intellij.openapi.util.Key.create<JPanel>("devrescue.root3")
        private val BODY_KEY = com.intellij.openapi.util.Key.create<JPanel>("devrescue.body3")

        private fun runAction(project: Project, id: String) {
            val mgr = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction(id) ?: return
            val ctx = com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project)
            @Suppress("DEPRECATION")
            val ev = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext("", null, ctx)
            mgr.actionPerformed(ev)
        }

        fun refreshStatus(project: Project) {
            val tw = ToolWindowManager.getInstance(project).getToolWindow("DevRescue") ?: return
            val root = tw.contentManager.getContent(0)?.getUserData(ROOT_KEY) ?: return
            val header = root.getComponent(0) as JPanel
            val status = header.getClientProperty("status") as JBLabel
            val s = DevRescueSettings.getInstance()
            val hasKey = try { s.getApiKey().isNotBlank() } catch (_: Exception) { false }
            status.text = if (hasKey) "• ${s.model}" else "• offline mode"
            status.foreground = UIUtil.getContextHelpForeground()
        }

        private fun bodyOf(project: Project): JPanel? {
            val tw = ToolWindowManager.getInstance(project).getToolWindow("DevRescue") ?: return null
            return tw.contentManager.getContent(0)?.getUserData(BODY_KEY)
        }

        private fun replaceBody(project: Project, card: JPanel) {
            val body = bodyOf(project) ?: return
            body.removeAll()
            body.add(card)
            body.revalidate(); body.repaint()
            refreshStatus(project)
        }

        private fun appendCard(project: Project, card: JPanel) {
            val body = bodyOf(project) ?: return
            if (body.componentCount > 0) {
                body.add(Box.createVerticalStrut(8))
                body.add(JSeparator())
                body.add(Box.createVerticalStrut(8))
            }
            body.add(card)
            body.revalidate(); body.repaint()
            refreshStatus(project)
        }

        fun setContent(project: Project, html: String) {
            if (html.contains("Analyzing")) { showLoading(project); return }
            replaceBody(project, textCard("Result", stripHtml(html)))
        }

        fun showLoading(project: Project) {
            replaceBody(project, textCard("Working…", "Analyzing. This takes a few seconds with AI enabled."))
        }

        fun showNotice(project: Project, msg: String) {
            appendCard(project, textCard("Note", msg))
        }

        fun showWelcome(project: Project) {
            replaceBody(project, textCard(
                "Welcome to DevRescue",
                "1. Select a stacktrace, press Alt+Shift+E for an explanation.\n" +
                    "2. Open any file, press Alt+Shift+H for a health check.\n" +
                    "3. Set your API key under Tools > DevRescue (stored in system keychain).\n\n" +
                    "Fixed: ${GameState.bugsFixed} errors • Scanned: ${GameState.filesScanned} files"
            ))
        }

        // Back-compat overload (old callers without frame)
        fun showError(project: Project, exp: Explanation, raw: String, usedAi: Boolean, model: String) =
            showError(project, exp, raw, usedAi, model, null)

        fun showError(project: Project, exp: Explanation, raw: String, usedAi: Boolean, model: String, frame: TraceFrame?) {
            GameState.bugFixed()
            val card = vbox()
            card.add(sectionTitle(exp.title))
            card.add(metaLine(if (usedAi) "Explained with $model" else "Explained offline (no API key)"))
            card.add(gap(8))
            if (frame != null) {
                card.add(jumpRow(project, "Your code: ${frame.file}:${frame.line}", frame.raw) {
                    TraceParser.navigate(project, frame)
                })
                card.add(gap(8))
            }
            card.add(sectionLabel("Why"))
            card.add(themedHtml(exp.why))
            card.add(gap(8))
            card.add(sectionLabel("How to fix"))
            card.add(themedHtml(exp.fix))
            if (raw.isNotBlank()) {
                card.add(gap(8))
                card.add(sectionLabel("Original error"))
                card.add(codeBlock(raw.take(800)))
            }
            replaceBody(project, wrap(card))
        }

        // Back-compat overload (old callers without VirtualFile)
        fun showHealth(project: Project, fileName: String, score: Int, issues: List<HealthIssue>) =
            showHealth(project, null, fileName, score, issues)

        fun showHealth(project: Project, file: VirtualFile?, fileName: String, score: Int, issues: List<HealthIssue>) {
            GameState.fileScanned(score)
            val card = vbox()
            card.add(sectionTitle("Health: $fileName"))
            card.add(scoreRow(score, issues.size))
            card.add(gap(8))
            if (issues.isEmpty()) {
                card.add(themedHtml("No issues found. This file looks clean."))
            } else {
                card.add(sectionLabel("${issues.size} findings"))
                card.add(gap(4))
                issues.take(30).forEach { card.add(findingRow(project, file, it)) }
                if (issues.size > 30) card.add(metaLine("…and ${issues.size - 30} more"))
            }
            replaceBody(project, wrap(card))
        }

        // ---- builders (theme-inheriting, no overlay) ----

        private fun vbox() = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = JPanel.LEFT_ALIGNMENT
        }
        private fun wrap(inner: JPanel) = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(inner, BorderLayout.NORTH)
        }
        private fun gap(px: Int) = Box.createVerticalStrut(px)

        private fun sectionTitle(t: String) = JBLabel(t).apply {
            font = font.deriveFont(Font.BOLD, 15f)
            alignmentX = JBLabel.LEFT_ALIGNMENT
        }
        private fun sectionLabel(t: String) = JBLabel(t).apply {
            font = font.deriveFont(Font.BOLD, 13f)
            alignmentX = JBLabel.LEFT_ALIGNMENT
        }
        private fun metaLine(t: String) = JBLabel(t).apply {
            font = font.deriveFont(12f)
            foreground = UIUtil.getContextHelpForeground()
            alignmentX = JBLabel.LEFT_ALIGNMENT
        }

        // Theme-aware HTML: inject current LaF foreground + muted color as CSS.
        private fun themedHtml(t: String): JEditorPane {
            val fg = ColorUtil.toHex(UIUtil.getLabelForeground())
            val muted = ColorUtil.toHex(UIUtil.getContextHelpForeground())
            val fontSize = UIUtil.getLabelFont().size
            val html = "<html><body style='color:#$fg;font-size:${fontSize}pt'>" +
                esc(t).replace("\n", "<br>") +
                "<div style='color:#$muted'></div></body></html>"
            return JEditorPane("text/html", html).apply {
                isEditable = false
                isOpaque = false
                border = JBUI.Borders.empty()
                alignmentX = JEditorPane.LEFT_ALIGNMENT
                putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            }
        }

        // Secondary text via explicit theme hex (never literal 'gray').
        private fun mutedHtml(inner: String): JEditorPane {
            val muted = ColorUtil.toHex(UIUtil.getContextHelpForeground())
            val fg = ColorUtil.toHex(UIUtil.getLabelForeground())
            return JEditorPane(
                "text/html",
                "<html><body style='color:#$fg'><b>$inner</b></body></html>".let {
                    it.replace("<b>", "<span style='color:#$fg'><b>").replace("</b>", "</b></span>")
                }
            ).apply {
                isEditable = false
                isOpaque = false
                border = JBUI.Borders.empty()
                putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            }
        }

        private fun codeBlock(t: String): JBScrollPane {
            val pane = JEditorPane("text/plain", t).apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                // inherit theme bg/fg for contrast
            }
            return JBScrollPane(pane).apply {
                alignmentX = JBScrollPane.LEFT_ALIGNMENT
                // Let it size naturally, cap height instead of fixed overlay box
                preferredSize = java.awt.Dimension(450, 120)
                maximumSize = java.awt.Dimension(Int.MAX_VALUE, 160)
            }
        }

        private fun scoreRow(score: Int, count: Int): JPanel {
            val row = JPanel(BorderLayout(8, 0)).apply { isOpaque = false; alignmentX = JPanel.LEFT_ALIGNMENT }
            val bar = JProgressBar(0, 100).apply { value = score; isStringPainted = true; string = "$score / 100" }
            row.add(bar, BorderLayout.CENTER)
            row.add(metaLine(if (count == 0) "clean" else "$count findings"), BorderLayout.EAST)
            return row
        }

        private fun jumpRow(project: Project, label: String, tooltip: String, onJump: () -> Unit): JPanel {
            val row = JPanel(BorderLayout(8, 0)).apply { isOpaque = false; alignmentX = JPanel.LEFT_ALIGNMENT }
            val link = JButton("Open code").apply {
                toolTipText = tooltip.take(300)
                addActionListener { onJump() }
            }
            row.add(JBLabel(label).apply { font = font.deriveFont(Font.BOLD, 12f) }, BorderLayout.CENTER)
            row.add(link, BorderLayout.EAST)
            return row
        }

        private fun findingRow(project: Project, file: VirtualFile?, i: HealthIssue): JPanel {
            val row = JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                alignmentX = JPanel.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(2, 0)
            }
            val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
            val line = JBLabel("L${i.line}").apply { font = font.deriveFont(Font.BOLD, 12f) }
            left.add(line)
            if (file != null) {
                val go = JButton("Open").apply {
                    toolTipText = "Jump to line ${i.line}"
                    addActionListener { TraceParser.openLine(project, file, i.line) }
                }
                left.add(go)
            }
            val muted = ColorUtil.toHex(UIUtil.getContextHelpForeground())
            val fg = ColorUtil.toHex(UIUtil.getLabelForeground())
            val msg = JEditorPane(
                "text/html",
                "<html><body style='color:#$fg'><b>${esc(i.kind)}</b> — ${esc(i.message)}" +
                    "<br><span style='color:#$muted'>${esc(i.suggestion)}</span></body></html>"
            ).apply {
                isEditable = false
                isOpaque = false
                border = JBUI.Borders.empty()
                putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            }
            row.add(left, BorderLayout.WEST)
            row.add(msg, BorderLayout.CENTER)
            return row
        }

        private fun textCard(title: String, body: String): JPanel {
            val card = vbox()
            card.add(sectionTitle(title))
            card.add(gap(6))
            card.add(themedHtml(body))
            return wrap(card)
        }

        private fun stripHtml(h: String) = h.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(2000)
        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
