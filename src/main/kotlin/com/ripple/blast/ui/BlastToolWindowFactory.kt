package com.ripple.blast.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.ripple.blast.BlastResult

/**
 * Creates the Ripple tool window and publishes its [BlastPanel] so the central
 * wiring can push scan results in later.
 *
 * The panel is stashed in the project's user data rather than in a registered
 * service, because a service would need its own plugin.xml entry and this
 * module cannot edit plugin.xml. User data on the Project is disposed with the
 * project, so nothing leaks between projects or across a project close.
 *
 * NOTE: a tool window that is not declared in plugin.xml silently does not
 * exist. The exact XML is in this module's wiring notes.
 */
class BlastToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = BlastPanel(project)
        project.putUserData(PANEL_KEY, panel)

        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        /** Must match the id in the plugin.xml <toolWindow> declaration. */
        const val TOOL_WINDOW_ID: String = "Ripple"

        private val PANEL_KEY: Key<BlastPanel> = Key.create("ripple.blast.panel")

        /**
         * The live panel, or null if the tool window has never been opened.
         * IntelliJ builds tool window content lazily, so a caller that needs
         * the panel to exist should use [showResult] instead.
         */
        fun panel(project: Project): BlastPanel? = project.getUserData(PANEL_KEY)

        /**
         * Opens/activates the tool window and pushes [result] into it.
         * Safe to call from any thread. This is the one call the central wiring
         * needs after a scan finishes.
         */
        fun showResult(project: Project, result: BlastResult) {
            withPanel(project) { it.setResult(result) }
        }

        /** Opens/activates the tool window and shows the scanning state. */
        fun showLoading(project: Project) {
            withPanel(project) { it.setLoading() }
        }

        /**
         * Activates the tool window (forcing content creation if this is the
         * first time) and then hands the panel to [block] on the EDT.
         */
        fun withPanel(project: Project, block: (BlastPanel) -> Unit) {
            onEdt(project) {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
                if (toolWindow == null) {
                    // Not registered in plugin.xml — see wiring notes.
                    return@onEdt
                }
                toolWindow.activate({
                    val panel = project.getUserData(PANEL_KEY)
                    if (panel != null) block(panel)
                }, true, true)
            }
        }

        private fun onEdt(project: Project, block: () -> Unit) {
            val application = ApplicationManager.getApplication()
            if (application.isDispatchThread) {
                if (!project.isDisposed) block()
            } else {
                application.invokeLater({ if (!project.isDisposed) block() }, ModalityState.any())
            }
        }
    }
}
