package ad42.devrescue

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.GlobalSearchScope

// Deterministic stacktrace parsing — the part AI chat can't do reliably:
// collapse framework frames, surface the first frame that is YOUR code,
// and resolve it to a navigable file:line.
data class TraceFrame(val file: String, val line: Int, val raw: String, val yours: Boolean)

object TraceParser {
    private val javaFrame = Regex("""at\s+([\w.${'$'}+_]+)\(([\w.${'$'}\-++_]+\.java):(\d+)\)""")
    private val pyFrame = Regex("""File\s+"([^"]+)",\s+line\s+(\d+),\s+in\s+(\S+)""")
    private val ignorePkg = listOf(
        "java.", "jdk.", "kotlin.", "kotlinx.", "sun.", "com.sun.",
        "org.springframework.", "org.hibernate.", "com.intellij.",
        "gradle.", "junit", "mockito", "site-packages", "dist-packages"
    )

    fun parse(errorText: String): List<TraceFrame> {
        val out = mutableListOf<TraceFrame>()
        javaFrame.findAll(errorText).forEach { m ->
            val cls = m.groupValues[1]
            val file = m.groupValues[2]
            val line = m.groupValues[3].toIntOrNull() ?: 1
            out += TraceFrame(file, line, m.value.trim(), ignorePkg.none { cls.startsWith(it) })
        }
        pyFrame.findAll(errorText).forEach { m ->
            val path = m.groupValues[1]
            val line = m.groupValues[2].toIntOrNull() ?: 1
            val yours = !path.contains("site-packages") && !path.contains("dist-packages") &&
                !path.contains("/lib/python")
            out += TraceFrame(path.substringAfterLast('/').substringAfterLast('\\'), line, m.value.trim(), yours)
        }
        return out
    }

    fun firstUserFrame(errorText: String): TraceFrame? =
        parse(errorText).firstOrNull { it.yours } ?: parse(errorText).firstOrNull()

    fun navigate(project: Project, frame: TraceFrame) {
        // Resolve: exact path first, then filename index, then open dialog fallback.
        val exact = LocalFileSystem.getInstance().findFileByPath(frame.file.replace('\\', '/'))
        if (exact != null && !exact.isDirectory) {
            OpenFileDescriptor(project, exact, (frame.line - 1).coerceAtLeast(0), 0)
                .navigate(true)
            return
        }
        try {
            val found = ReadAction.compute<com.intellij.openapi.vfs.VirtualFile?, RuntimeException> {
                com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(
                    frame.file.substringAfterLast('/').substringAfterLast('\\'),
                    GlobalSearchScope.projectScope(project)
                ).firstOrNull()
            }
            if (found != null) {
                OpenFileDescriptor(project, found, (frame.line - 1).coerceAtLeast(0), 0).navigate(true)
            } else {
                // Fallback: at least show the file chooser context — toast via toolwindow
                DevRescueToolWindowFactory.showNotice(project, "Could not find ${frame.file}:${frame.line} in project. It may be in a library.")
            }
        } catch (_: Exception) {
            DevRescueToolWindowFactory.showNotice(project, "Navigation failed for ${frame.file}:${frame.line}.")
        }
    }

    fun openLine(project: Project, file: com.intellij.openapi.vfs.VirtualFile, line1Based: Int) {
        val editor = FileEditorManager.getInstance(project).openFile(file, true).firstOrNull()
        OpenFileDescriptor(project, file, (line1Based - 1).coerceAtLeast(0), 0).navigateInEditor(project, true)
    }
}
