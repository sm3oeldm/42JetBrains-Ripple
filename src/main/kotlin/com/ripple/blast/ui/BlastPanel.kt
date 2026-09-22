package com.ripple.blast.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.ripple.blast.BlastLimits
import com.ripple.blast.BlastNode
import com.ripple.blast.BlastResult
import com.ripple.blast.NavigationKey
import com.ripple.blast.NodeKind
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * The unified Ripple tool window: blast radius on top, red list front and centre.
 *
 * This panel is a pure VIEW. It never runs a scan itself — the central wiring
 * owns the scanner and pushes finished results in through [setResult]. The only
 * thing the panel does outbound is fire [setRescanListener] when the user asks
 * for a rescan. That keeps this file free of PSI traversal and free of any
 * dependency on the scanning module's construction order.
 *
 * The model holds no PSI (see com.ripple.blast.BlastModel), so navigation
 * re-resolves from a [NavigationKey] in a background read action and only then
 * touches the EDT.
 */
class BlastPanel(
    private val project: Project,
    rescanRequested: () -> Unit = {}
) : SimpleToolWindowPanel(true, true), Disposable {

    private var rescanListener: () -> Unit = rescanRequested

    /** Last result pushed in. Re-read when the filter toggle flips. */
    private var current: BlastResult = BlastResult.empty()
    private var showOnlyUncovered: Boolean = false

    // --- stats header ---------------------------------------------------
    //
    // ONE hero number, not four.
    //
    // Four big numbers of equal weight give the eye nowhere to land - you have
    // to read all four to learn which matters. Only one of them is the finding:
    // how much of what you touched has nothing protecting it. The rest are
    // context for that number, so they belong in a quiet line beside it.
    private val noTestValue = heroNumberLabel()
    private val supportingStats = JBLabel("").apply {
        font = JBUI.Fonts.label(12f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }
    private val truncationNote = JBLabel("").apply {
        font = JBUI.Fonts.label(11f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
        border = JBUI.Borders.empty(0, 16, 6, 16)
        isVisible = false
    }

    // --- tree -----------------------------------------------------------
    private val treeRoot = DefaultMutableTreeNode("blast")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = BlastTreeRenderer()
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }

    // --- state switching ------------------------------------------------
    /**
     * One plain-English line under the numbers saying what the tree MEANS.
     *
     * Without it the panel is a list of method names with no stated relationship,
     * and the nesting is the opposite of a normal call tree: here a child is
     * something that DEPENDS ON its parent, not something the parent calls.
     * Nobody guesses that, and a judge has about ten seconds.
     */
    private val explanation = JBLabel("").apply {
        font = JBUI.Fonts.label(11f)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    private val cards = CardLayout()
    private val cardHost = JPanel(cards)

    init {
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(14, 16, 4, 16)
            add(statCell(noTestValue, "with no test"))
            add(Box.createHorizontalStrut(JBUI.scale(20)))
            // Bottom-aligned so the quiet line sits on the hero number's
            // baseline instead of floating beside its cap height.
            add(JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentY = Component.BOTTOM_ALIGNMENT
                add(Box.createVerticalGlue())
                add(supportingStats)
                add(Box.createVerticalStrut(JBUI.scale(6)))
            })
            add(Box.createHorizontalGlue())
        }

        cardHost.add(buildEmptyState(), CARD_EMPTY)
        cardHost.add(buildLoadingState(), CARD_LOADING)
        cardHost.add(ScrollPaneFactory.createScrollPane(tree, true), CARD_TREE)

        val body = JPanel(BorderLayout()).apply {
            add(
                JPanel(BorderLayout()).apply {
                    add(header, BorderLayout.NORTH)
                    add(
                        JPanel(BorderLayout()).apply {
                            isOpaque = false
                            border = JBUI.Borders.empty(0, 16, 10, 16)
                            add(explanation, BorderLayout.NORTH)
                            add(truncationNote, BorderLayout.SOUTH)
                        },
                        BorderLayout.SOUTH
                    )
                },
                BorderLayout.NORTH
            )
            add(cardHost, BorderLayout.CENTER)
        }

        toolbar = buildToolbar(body)
        setContent(body)

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigateToSelection()
            }
        })

        applyResult(BlastResult.empty())
    }

    // ------------------------------------------------------------------
    // Public API used by the central wiring
    // ------------------------------------------------------------------

    /**
     * Accepts a finished scan and repaints. Safe to call from any thread —
     * the swap itself always happens on the EDT.
     */
    fun setResult(result: BlastResult) = onEdt { applyResult(result) }

    /** Switches to the spinner while a scan runs. Safe from any thread. */
    fun setLoading() = onEdt {
        truncationNote.isVisible = false
        cards.show(cardHost, CARD_LOADING)
    }

    /** The wiring installs the real scanner trigger here. */
    fun setRescanListener(listener: () -> Unit) {
        rescanListener = listener
    }

    override fun dispose() {
        // Nothing retained: no PSI, no listeners outside this component tree.
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun applyResult(result: BlastResult) {
        current = result

        val neverRan = result.distinctNodes.count { it.isUnprovenAndUnrun }
        noTestValue.text = result.redList.size.toString()
        supportingStats.text = buildString {
            append("of ${result.totalInRadius} in the blast radius")
            append("  ·  ${result.uncoveredPercent}% uncovered")
            if (neverRan > 0) append("  ·  $neverRan never ran")
        }

        val changed = result.roots.firstOrNull()?.displayName?.substringBefore('(')
        explanation.text = when {
            result.isEmpty -> ""
            changed == null -> "Methods that break if the code you changed is wrong. Red = nothing tests it."
            neverRan > 0 ->
                "These ${result.totalInRadius} methods break if $changed is wrong. " +
                    "Red = nothing tests it. \"Never ran\" = it did not execute in this run either."
            else ->
                "These ${result.totalInRadius} methods break if $changed is wrong. " +
                    "Red = nothing tests it."
        }
        explanation.isVisible = explanation.text.isNotEmpty()

        truncationNote.text = if (result.truncated) {
            "Partial result: stopped at ${BlastLimits.MAX_NODES} nodes / ${result.maxHops} hops."
        } else {
            ""
        }
        truncationNote.isVisible = result.truncated

        if (result.isEmpty) {
            cards.show(cardHost, CARD_EMPTY)
        } else {
            rebuildTree()
            cards.show(cardHost, CARD_TREE)
        }
        revalidate()
        repaint()
    }

    private fun rebuildTree() {
        treeRoot.removeAllChildren()

        val roots = if (showOnlyUncovered) {
            current.roots.mapNotNull { keepRedPaths(it) }
        } else {
            current.roots
        }

        val budget = intArrayOf(BlastLimits.MAX_NODES)
        addChildren(treeRoot, roots, budget)

        if (treeRoot.childCount == 0) {
            val message = if (showOnlyUncovered) {
                "Nothing uncovered. Every method in the radius has a test."
            } else {
                "No methods in the blast radius."
            }
            treeRoot.add(DefaultMutableTreeNode(message))
        } else if (budget[0] <= 0) {
            treeRoot.add(DefaultMutableTreeNode("Stopped at ${BlastLimits.MAX_NODES} nodes."))
        }

        treeModel.reload()
        TreeUtil.expandAll(tree)
    }

    /** Red first, then nearest, then alphabetical. The hero list floats up. */
    private fun addChildren(parent: DefaultMutableTreeNode, nodes: List<BlastNode>, budget: IntArray) {
        for (node in nodes.sortedWith(NODE_ORDER)) {
            if (budget[0] <= 0) return
            budget[0]--
            val treeNode = DefaultMutableTreeNode(node)
            parent.add(treeNode)
            addChildren(treeNode, node.children, budget)
        }
    }

    /** Keeps a node only if it is red or leads to something red. */
    private fun keepRedPaths(node: BlastNode): BlastNode? {
        val kept = node.children.mapNotNull { keepRedPaths(it) }
        return if (node.isRedListed || kept.isNotEmpty()) node.copy(children = kept) else null
    }

    // ------------------------------------------------------------------
    // Navigation — re-resolve from NavigationKey, never from stored PSI
    // ------------------------------------------------------------------

    private fun navigateToSelection() {
        val selected = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val node = selected.userObject as? BlastNode ?: return
        val key = node.navigationKey

        ReadAction.nonBlocking<List<OpenFileDescriptor>> { resolve(key) }
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) { targets ->
                targets.firstOrNull()?.navigate(true)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * Runs inside a read action. Returns a descriptor (file + offset) rather
     * than a PsiElement so nothing PSI-shaped escapes the read action.
     *
     * A list is used instead of a nullable result because NonBlockingReadAction
     * is happier with a non-null value.
     */
    private fun resolve(key: NavigationKey): List<OpenFileDescriptor> {
        if (project.isDisposed) return emptyList()

        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)

        // fqcn is the BINARY name, so inner classes arrive as Outer$Inner.
        val psiClass = facade.findClass(key.fqcn, scope)
            ?: facade.findClass(key.fqcn.replace('$', '.'), scope)
            ?: return emptyList()

        val candidates = psiClass.findMethodsByName(key.methodName, true)
        val target = if (candidates.isEmpty()) {
            psiClass.navigationElement
        } else {
            (candidates.firstOrNull { matchesSignature(it, key) } ?: candidates.first()).navigationElement
        }

        val virtualFile = target.containingFile?.virtualFile ?: return emptyList()
        return listOf(OpenFileDescriptor(project, virtualFile, target.textOffset))
    }

    private fun matchesSignature(method: PsiMethod, key: NavigationKey): Boolean {
        val parameters = method.parameterList.parameters
        if (parameters.size != key.parameterTypes.size) return false
        return parameters.indices.all { i ->
            val actual = parameters[i].type
            val expected = key.parameterTypes[i]
            actual.canonicalText == expected ||
                actual.presentableText == expected ||
                actual.canonicalText.substringAfterLast('.') == expected.substringAfterLast('.')
        }
    }

    // ------------------------------------------------------------------
    // Chrome
    // ------------------------------------------------------------------

    private fun buildToolbar(target: JComponent): JComponent {
        val group = DefaultActionGroup()

        group.add(object : AnAction("Rescan", "Recompute the blast radius", AllIcons.Actions.Refresh), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = rescanListener()
        })

        group.add(object : ToggleAction(
            "Show Only Uncovered",
            "Hide everything a test already protects",
            AllIcons.General.Filter
        ), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent): Boolean = showOnlyUncovered
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                showOnlyUncovered = state
                if (!current.isEmpty) rebuildTree()
            }
        })

        group.addSeparator()

        group.add(object : AnAction("Expand All", "Expand every branch", AllIcons.Actions.Expandall), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
        })

        group.add(object : AnAction("Collapse All", "Collapse every branch", AllIcons.Actions.Collapseall), DumbAware {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = TreeUtil.collapseAll(tree, 1)
        })

        val actionToolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
        actionToolbar.targetComponent = target
        return actionToolbar.component
    }

    private fun buildEmptyState(): JComponent {
        val headline = JBLabel("No changes to analyse").apply {
            font = JBUI.Fonts.label(18f).asBold()
            alignmentX = Component.CENTER_ALIGNMENT
        }
        val detail = JBLabel("Edit a method, then press Rescan to see everything it could break.").apply {
            font = JBUI.Fonts.label(12f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            alignmentX = Component.CENTER_ALIGNMENT
        }
        val button = JButton("Scan now").apply {
            alignmentX = Component.CENTER_ALIGNMENT
            addActionListener { rescanListener() }
        }

        return JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    border = JBUI.Borders.empty(48, 24, 24, 24)
                    add(headline)
                    add(Box.createVerticalStrut(JBUI.scale(8)))
                    add(detail)
                    add(Box.createVerticalStrut(JBUI.scale(18)))
                    add(button)
                }
            )
        }
    }

    private fun buildLoadingState(): JComponent {
        val label = JBLabel("Scanning blast radius...").apply {
            font = JBUI.Fonts.label(14f)
            alignmentX = Component.CENTER_ALIGNMENT
        }
        val bar = JProgressBar().apply {
            isIndeterminate = true
            alignmentX = Component.CENTER_ALIGNMENT
            maximumSize = Dimension(JBUI.scale(240), preferredSize.height)
        }

        return JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    border = JBUI.Borders.empty(56, 24, 24, 24)
                    add(label)
                    add(Box.createVerticalStrut(JBUI.scale(14)))
                    add(bar)
                }
            )
        }
    }

    private fun statCell(value: JBLabel, caption: String): JPanel {
        val captionLabel = JBLabel(caption.uppercase()).apply {
            font = JBUI.Fonts.label(10f)
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
            alignmentX = Component.LEFT_ALIGNMENT
        }
        value.alignmentX = Component.LEFT_ALIGNMENT
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentY = Component.TOP_ALIGNMENT
            add(value)
            add(captionLabel)
        }
    }


    /** The loudest thing in the panel. */
    private fun heroNumberLabel(): JBLabel = JBLabel("0%", SwingConstants.LEFT).apply {
        font = JBUI.Fonts.label(40f).asBold()
        foreground = BlastTreeRenderer.RED
    }

    private fun onEdt(block: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            block()
        } else {
            application.invokeLater(block, ModalityState.any())
        }
    }

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_LOADING = "loading"
        private const val CARD_TREE = "tree"
        private const val TOOLBAR_PLACE = "RippleBlastToolbar"

        /**
         * Never-ran red first, then red, then ordinary callers, then tests.
         * Ties break on hop distance so the closest blast lands highest.
         */
        private val NODE_ORDER: Comparator<BlastNode> = compareBy(
            { node: BlastNode ->
                when {
                    node.isUnprovenAndUnrun -> 0
                    node.isRedListed -> 1
                    node.kind == NodeKind.TEST -> 3
                    else -> 2
                }
            },
            { node: BlastNode -> node.hops },
            { node: BlastNode -> node.displayName }
        )
    }
}
