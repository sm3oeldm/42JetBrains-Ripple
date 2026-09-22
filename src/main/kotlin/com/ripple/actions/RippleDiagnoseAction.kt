package com.ripple.actions

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiForStatement
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.util.PsiTreeUtil

// Temporary diagnostic (delete before submission): reports from INSIDE the
// sandbox whether the Ripple inspection is registered/enabled, and whether
// the heuristics fire on the exact open file. Ends all guessing.
class RippleDiagnoseAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val out = StringBuilder()

        // 1. Profile registration (public API only)
        // NB: Tools-menu actions carry no editor/file in their DataContext,
        // so resolve the open file via the editor manager instead of PSI_FILE.
        val editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedTextEditor
        val psiFile0 = editor?.document?.let {
            com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(it)
        }
        try {
            val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
            val key = HighlightDisplayKey.find("RippleEdgeCase")
            out.append("HighlightDisplayKey found: ${key != null}\n")
            if (key != null) {
                val el = psiFile0 as? com.intellij.psi.PsiElement
                val level = try {
                    if (el != null) profile.getErrorLevel(key, el).toString()
                    else "(no file open)"
                } catch (t: Throwable) { "lookup failed: $t" }
                out.append("error level in current profile: $level\n")
            }
        } catch (t: Throwable) {
            out.append("profile lookup failed: $t\n")
        }

        // 2. Manual walk of the open file with the same heuristics
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) as? PsiJavaFile
        if (psiFile == null) {
            out.append("current file is NOT a Java file: ${e.getData(CommonDataKeys.PSI_FILE)?.javaClass}\n")
        } else {
            val fors = PsiTreeUtil.findChildrenOfType(psiFile, PsiForStatement::class.java)
            out.append("PsiForStatement count: ${fors.size}\n")
            for (f in fors) {
                val cond = f.condition as? PsiBinaryExpression
                val op = cond?.operationSign?.text
                val rhs = cond?.rOperand?.text
                val flag = op == "<=" && (rhs?.contains(".length") == true || rhs?.contains(".size(") == true)
                out.append("  for @line=${lineOf(psiFile, f)} op=$op rhs=$rhs -> flag=$flag\n")
            }
            val methods = PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java)
            out.append("PsiMethod count: ${methods.map { it.name }}\n")
            for (m in methods) {
                val body = m.body ?: continue
                val self = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java)
                    .filter { it.resolveMethod() == m }
                val guarded = PsiTreeUtil.findChildrenOfType(body, PsiIfStatement::class.java)
                    .any { PsiTreeUtil.findChildOfType(it.thenBranch, PsiReturnStatement::class.java) != null }
                out.append("  method ${m.name}: selfCalls=${self.size} guarded=$guarded -> flag=${self.isNotEmpty() && !guarded}\n")
            }
        }

        Messages.showMessageDialog(project, out.toString(), "Ripple Diagnosis", null)
    }

    private fun lineOf(file: PsiJavaFile, element: com.intellij.psi.PsiElement): Int {
        val doc = com.intellij.psi.PsiDocumentManager.getInstance(file.project).getDocument(file)
            ?: return -1
        return doc.getLineNumber(element.textRange.startOffset) + 1
    }
}
