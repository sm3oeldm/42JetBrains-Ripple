package com.ripple.blast

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil

/**
 * The PSI <-> [NavigationKey] bridge.
 *
 * [BlastModel] is deliberately PSI-free: a [BlastResult] outlives the read
 * action that produced it, so it may only carry strings. This object is the one
 * place that crosses the boundary, in both directions:
 *
 *   - [keyOf] turns a live [PsiMethod] into a durable key (requires a read action)
 *   - [resolve] turns a durable key back into a live [PsiMethod] (requires a read action)
 *
 * The key's `fqcn` is the **binary** class name (`Outer${'$'}Inner`), because that is
 * what JDI reports and therefore what lets a recorded trace be matched back onto
 * a blast node. See [TraceCorrelator] for the caveats there.
 *
 * EVERY function in this file must be called with read access held. None of
 * them takes one itself — that is the caller's job, so a caller can batch many
 * lookups into a single read action instead of taking hundreds of tiny ones.
 */
internal object BlastPsiKeys {

    /** Separator used in JVM binary names for nested classes. */
    private const val NESTED = "$"

    /**
     * Durable identity for [method], or null when the method lives somewhere
     * that has no stable name (an anonymous or local class).
     */
    fun keyOf(method: PsiMethod): NavigationKey? {
        val owner = PsiTreeUtil.getParentOfType(method, PsiClass::class.java) ?: return null
        val binary = binaryName(owner) ?: return null
        return NavigationKey(
            fqcn = binary,
            methodName = method.name,
            parameterTypes = parameterTypes(method)
        )
    }

    /**
     * JVM binary name for [cls] (`com.acme.Outer${'$'}Inner`).
     *
     * Returns null for anonymous/local classes: they have no `name` or no
     * enclosing qualified name, and a blast node we cannot navigate back to is
     * worse than no node at all.
     */
    fun binaryName(cls: PsiClass): String? {
        val nested = ArrayDeque<String>()
        var current: PsiClass = cls
        while (true) {
            val outer = current.containingClass
            if (outer == null) {
                val top = current.qualifiedName ?: return null
                return if (nested.isEmpty()) top else top + NESTED + nested.joinToString(NESTED)
            }
            nested.addFirst(current.name ?: return null)
            current = outer
        }
    }

    /**
     * Erased parameter type names, so an overload is distinguishable but a
     * generic instantiation is not accidentally a different method.
     */
    fun parameterTypes(method: PsiMethod): List<String> =
        method.parameterList.parameters.map { erasedName(it.type) }

    private fun erasedName(type: PsiType): String =
        (TypeConversionUtil.erasure(type) ?: type).canonicalText

    /** `Owner.name(Int, String)` — display only, never used for identity. */
    fun displayNameOf(method: PsiMethod): String {
        val owner = PsiTreeUtil.getParentOfType(method, PsiClass::class.java)?.name
        val params = method.parameterList.parameters.joinToString(", ") { it.type.presentableText }
        return if (owner != null) "$owner.${method.name}($params)" else "${method.name}($params)"
    }

    /** 1-based declaration line, or -1 when the file has no document. */
    fun declarationLine(method: PsiMethod): Int {
        val file: PsiFile = method.containingFile ?: return -1
        val doc = PsiDocumentManager.getInstance(method.project).getDocument(file) ?: return -1
        val offset = method.textRange?.startOffset ?: return -1
        if (offset < 0 || offset > doc.textLength) return -1
        return doc.getLineNumber(offset) + 1
    }

    /** Absolute path of the declaring file, for display only. */
    fun filePathOf(method: PsiMethod): String? =
        method.containingFile?.virtualFile?.path

    /**
     * Re-resolve a key to a live [PsiMethod].
     *
     * Splits the binary name back into its nesting chain rather than relying on
     * a helper, so the behaviour is identical whether the class is top-level or
     * deeply nested, and so a missing inner class fails fast instead of
     * silently resolving to the outer one.
     */
    fun resolve(project: Project, key: NavigationKey): PsiMethod? {
        val scope = GlobalSearchScope.allScope(project)
        val chain = key.fqcn.split(NESTED)
        var cls: PsiClass = JavaPsiFacade.getInstance(project).findClass(chain[0], scope) ?: return null
        for (i in 1 until chain.size) {
            cls = cls.innerClasses.firstOrNull { it.name == chain[i] } ?: return null
        }
        return cls.methods.firstOrNull { candidate ->
            candidate.name == key.methodName && parameterTypes(candidate) == key.parameterTypes
        }
    }
}
