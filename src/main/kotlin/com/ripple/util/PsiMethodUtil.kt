package com.ripple.util

import com.intellij.openapi.editor.Document
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.CommonClassNames
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import java.io.File

object PsiMethodUtil {

    data class Target(
        val method: PsiMethod,
        val fqcn: String,
        val methodQualifiedName: String,
        val lineRange: IntRange, // 1-based, inclusive
        val hasMain: Boolean
    )

    fun methodAtCaret(file: PsiJavaFile, caretOffset: Int): Target? {
        val element = file.findElementAt(caretOffset) ?: return null
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return null
        val cls: PsiClass = PsiTreeUtil.getParentOfType(method, PsiClass::class.java) ?: return null
        val fqcn = cls.qualifiedName ?: return null
        val doc: Document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        val startLine = doc.getLineNumber(method.textRange.startOffset) + 1
        val endLine = doc.getLineNumber(method.textRange.endOffset) + 1
        val hasMain = method.name == "main" ||
            cls.methods.any { it.name == "main" && it.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC) }
        return Target(method, fqcn, "$fqcn#${method.name}", startLine..endLine, hasMain)
    }

    /**
     * Fully-qualified names of every class in the project with a runnable
     * `public static void main(String[])`.
     *
     * The tracer needs an ENTRY POINT to launch, which is almost never the class
     * you are editing. PriceCalculator has no main; Main does. Requiring the
     * traced method to live in the same class as main made most real code
     * untraceable, including our own demo.
     *
     * [preferPackage] floats entry points in the same package to the front, so
     * the obvious one wins without asking the user.
     *
     * Call with read access.
     */
    fun findMainClasses(project: Project, preferPackage: String? = null): List<String> {
        val scope = GlobalSearchScope.projectScope(project)
        val names = PsiShortNamesCache.getInstance(project)
            .getMethodsByName("main", scope)
            .asSequence()
            .filter { m ->
                m.hasModifierProperty(PsiModifier.PUBLIC) &&
                    m.hasModifierProperty(PsiModifier.STATIC) &&
                    m.returnType?.equalsToText("void") == true &&
                    m.parameterList.parametersCount == 1 &&
                    (m.parameterList.parameters[0].type as? PsiArrayType)
                        ?.componentType?.equalsToText(CommonClassNames.JAVA_LANG_STRING) == true
            }
            .mapNotNull { PsiTreeUtil.getParentOfType(it, PsiClass::class.java)?.qualifiedName }
            .distinct()
            .toList()

        if (preferPackage.isNullOrEmpty()) return names
        val (samePkg, rest) = names.partition { it.substringBeforeLast('.', "") == preferPackage }
        return samePkg + rest
    }

    data class Launch(val javaBin: String, val classpath: String)

    // Module SDK preferred (idiomatic); falls back to the current runtime + a
    // <ClassName>.class hunt so the sample project works with zero setup.
    fun resolveLaunch(project: Project, file: VirtualFile, simpleName: String): Launch? {
        val module: Module? = ModuleUtil.findModuleForFile(file, project)
        if (module != null) {
            val sdk = ModuleRootManager.getInstance(module).sdk
                ?: ProjectRootManager.getInstance(project).projectSdk
            val javaBin = sdk?.let { JavaSdk.getInstance().getVMExecutablePath(it) }
            val out = CompilerModuleExtension.getInstance(module)?.compilerOutputPath
            if (javaBin != null && out != null && File(out.path).isDirectory) {
                return Launch(javaBin, out.path)
            }
        }
        // Fallback: current runtime + hunt for the compiled class.
        val javaBin = System.getProperty("java.home") + File.separator + "bin" +
            File.separator + "java" + (if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        val base = project.basePath ?: return null
        val candidates = listOf(
            File(base, "out"),
            File(file.parent.path, "out"),
            File(file.parent.path)
        )
        for (dir in candidates) {
            if (File(dir, "$simpleName.class").isFile) {
                return Launch(javaBin, dir.absolutePath)
            }
            // one level of nesting (e.g. sample-trace-demo/out)
            dir.listFiles { f -> f.isDirectory }?.forEach { sub ->
                if (File(sub, "$simpleName.class").isFile) {
                    return Launch(javaBin, sub.absolutePath)
                }
            }
        }
        return null
    }
}
