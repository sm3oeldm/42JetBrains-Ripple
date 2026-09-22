package com.ripple.blast.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.ripple.blast.BlastNode
import com.ripple.blast.Coverage
import com.ripple.blast.Execution
import com.ripple.blast.NodeKind
import java.awt.Color
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Row painter for the blast tree.
 *
 * [com.intellij.ui.ColoredTreeCellRenderer.getTreeCellRendererComponent] is
 * FINAL on the platform class, so the extension point is
 * [customizeCellRenderer] — overriding the other one does not compile.
 *
 * The visual contract is deliberately blunt because this is read off a
 * projector: exactly one accent colour (red), no gradients, no emoji. Red means
 * "nothing tests this". Everything else recedes.
 */
class BlastTreeRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val userObject = (value as? DefaultMutableTreeNode)?.userObject

        // Synthetic rows (the truncation notice) come through as plain strings.
        if (userObject is String) {
            append(userObject, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            return
        }

        val node = userObject as? BlastNode ?: return

        icon = iconFor(node)

        // 1. The name. Red and bold when nothing is protecting it.
        val nameAttributes = when {
            node.isRedListed -> RED_BOLD
            node.kind == NodeKind.TEST -> TEST_ATTRIBUTES
            node.coverage == Coverage.COVERED -> MUTED_ATTRIBUTES
            else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        }
        append(node.displayName, nameAttributes)

        // 2. The badge. A node that no test reaches AND that never executed is
        //    the strongest finding the product can make, so it gets an opaque
        //    chip rather than more grey text.
        if (node.isUnprovenAndUnrun) {
            append("  never ran  ", NEVER_RAN_BADGE)
        }

        // 3. Secondary context, always grey: hop distance then file name.
        append(hopLabel(node), SimpleTextAttributes.GRAYED_ATTRIBUTES)

        val fileName = shortFileName(node.filePath)
        if (fileName != null) {
            append("  $fileName", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        toolTipText = buildTooltip(node)
    }

    private fun iconFor(node: BlastNode) = when {
        node.isUnprovenAndUnrun -> AllIcons.General.Error
        node.isRedListed -> AllIcons.General.Warning
        node.kind == NodeKind.TEST -> AllIcons.RunConfigurations.TestState.Run
        node.kind == NodeKind.CHANGED_ROOT -> AllIcons.Actions.Edit
        else -> AllIcons.Nodes.Method
    }

    private fun hopLabel(node: BlastNode): String = when {
        node.kind == NodeKind.CHANGED_ROOT -> "  you changed this"
        node.hops == 1 -> "  1 hop"
        else -> "  ${node.hops} hops"
    }

    private fun shortFileName(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        return if (name.isBlank()) null else name
    }

    private fun buildTooltip(node: BlastNode): String {
        val coverage = when (node.coverage) {
            Coverage.COVERED -> "Reached by at least one test."
            Coverage.UNCOVERED -> "NO test reaches this method."
            Coverage.IS_TEST -> "This is test code."
            Coverage.UNKNOWN -> "Coverage not computed."
        }
        val execution = when (node.execution) {
            Execution.EXECUTED -> "Observed running in the last recording."
            Execution.NEVER_EXECUTED -> "Never appeared in the last recording."
            Execution.NOT_RECORDED -> "No recording made yet."
        }
        val where = if (node.line > 0) "${node.key.fqcn} line ${node.line}" else node.key.fqcn
        return "<html>${node.key.id}<br>$where<br><br>$coverage<br>$execution</html>"
    }

    companion object {
        /**
         * The single accent colour. Explicit light/dark values because the
         * default red is illegible on one theme or the other; both of these
         * clear 4.5:1 against their own background.
         */
        val RED: JBColor = JBColor(Color(0xC5, 0x21, 0x1A), Color(0xFF, 0x6B, 0x6B))

        private val MUTED: JBColor = JBColor(Color(0x6E, 0x6E, 0x6E), Color(0x9A, 0xA0, 0xA6))
        private val TEST_GREEN: JBColor = JBColor(Color(0x27, 0x6D, 0x2E), Color(0x6E, 0xC2, 0x7A))

        private val RED_BADGE_BACKGROUND: JBColor =
            JBColor(Color(0xFF, 0xE2, 0xE0), Color(0x5C, 0x1F, 0x1C))

        private val RED_BOLD = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, RED)
        private val MUTED_ATTRIBUTES = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, MUTED)
        private val TEST_ATTRIBUTES = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, TEST_GREEN)

        /** Opaque chip so "never ran" cannot be mistaken for more grey detail. */
        private val NEVER_RAN_BADGE = SimpleTextAttributes(
            RED_BADGE_BACKGROUND,
            RED,
            null,
            SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_OPAQUE
        )
    }
}
