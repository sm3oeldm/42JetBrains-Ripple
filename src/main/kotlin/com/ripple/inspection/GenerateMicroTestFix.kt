package com.ripple.inspection

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil

// String-templated micro-test into a scratch buffer (§3.6). No LLM: boundary
// literals per parameter type, TODO asserts. Must save typing on stage, not
// be semantically perfect.
class GenerateMicroTestFix(private val target: PsiElement) : LocalQuickFix {
    override fun getFamilyName() = "Generate micro-test for this edge case"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val method = PsiTreeUtil.getParentOfType(target, PsiMethod::class.java, false) ?: return
        val testSource = buildTestSkeleton(method)
        val scratchFile = ScratchRootType.getInstance().createScratchFile(
            project, "${method.name}EdgeCaseTest.java", JavaLanguage.INSTANCE, testSource
        )
        scratchFile?.let { FileEditorManager.getInstance(project).openFile(it, true) }
    }

    private fun buildTestSkeleton(method: PsiMethod): String {
        val params = method.parameterList.parameters
        val cls = PsiTreeUtil.getParentOfType(method, com.intellij.psi.PsiClass::class.java)
        val clsName = cls?.name ?: "Target"
        val argSets = params.map { boundaryValues(it.type, it.name) }
        val cases = listOf("boundary", "empty/zero", "extreme").mapIndexed { i, label ->
            val args = argSets.map { vals -> vals.getOrElse(i) { vals.last() } }.joinToString(", ")
            "    @Test\n" +
                "    void ${method.name}_${label.replace("/", "_")}() {\n" +
                "        // TODO: assert expected behavior at boundary\n" +
                "        // ${clsName}.${method.name}($args)\n" +
                "    }"
        }.joinToString("\n\n")
        return "import org.junit.jupiter.api.Test;\n\n" +
            "class ${method.name}EdgeCaseTest {\n\n$cases\n}\n"
    }

    private fun boundaryValues(type: PsiType, name: String): List<String> {
        val text = type.canonicalText
        return when {
            type is PsiArrayType -> {
                val c = type.componentType.canonicalText
                listOf("new $c[0]", "new $c[]{1}", "null")
            }
            text == "int" || text == "long" -> listOf("0", "-1", if (text == "int") "Integer.MAX_VALUE" else "Long.MAX_VALUE")
            text == "double" || text == "float" -> listOf("0.0", "-1.0", "Double.MAX_VALUE")
            text == "boolean" -> listOf("true", "false", "true")
            text == "java.lang.String" -> listOf("\"\"", "\"x\"", "null")
            type is PsiPrimitiveType -> listOf("0", "-1", "0")
            else -> listOf("null", "/* new ${type.presentableText}() */null", "null")
        }
    }
}
