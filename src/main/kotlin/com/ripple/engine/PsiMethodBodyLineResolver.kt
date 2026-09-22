package com.ripple.engine

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.ripple.blast.NavigationKey

/**
 * Resolves a [NavigationKey] back to the real source line range of its method.
 *
 * Without this, [BlastTraceConfig] falls back to "declaration line + 40", which
 * under-covers any method longer than 40 lines — the tail of the method simply
 * records no events, and it looks like the code did not run when in fact it was
 * never instrumented. That is the worst kind of wrong answer for this product,
 * because the whole point is telling people what did and did not execute.
 *
 * Resolution is cached per instance: a blast radius routinely contains several
 * methods from one class, and re-resolving the class each time is wasted work
 * inside a read action.
 *
 * THREADING: safe from a background thread. Every PSI touch is wrapped in its
 * own short [ReadAction]; no lock is held across the whole target list.
 */
class PsiMethodBodyLineResolver(private val project: Project) : MethodBodyLineResolver {

    private val cache = HashMap<String, IntRange?>()

    override fun lineRange(key: NavigationKey): IntRange? =
        cache.getOrPut(key.id) { resolve(key) }

    private fun resolve(key: NavigationKey): IntRange? = try {
        ReadAction.compute<IntRange?, RuntimeException> {
            if (project.isDisposed) return@compute null

            // JDI reports binary names (Outer$Inner); PSI wants the source name.
            val sourceFqcn = key.fqcn.replace('$', '.')
            val psiClass = JavaPsiFacade.getInstance(project)
                .findClass(sourceFqcn, GlobalSearchScope.projectScope(project))
                ?: return@compute null

            val candidates: List<PsiMethod> = if (key.methodName == "<init>") {
                psiClass.constructors.toList()
            } else {
                psiClass.findMethodsByName(key.methodName, false).toList()
            }
            if (candidates.isEmpty()) return@compute null

            // Prefer the overload whose parameter types match. Where they do not
            // (erasure, generics), fall back to the widest span across overloads
            // rather than guessing one: over-covering costs a few breakpoints,
            // under-covering silently loses events.
            val exact = candidates.firstOrNull { m ->
                m.parameterList.parameters.map { it.type.canonicalText } == key.parameterTypes
            }
            val chosen = if (exact != null) listOf(exact) else candidates

            val doc = PsiDocumentManager.getInstance(project)
                .getDocument(psiClass.containingFile ?: return@compute null)
                ?: return@compute null

            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            for (m in chosen) {
                val range = m.textRange ?: continue
                if (range.endOffset > doc.textLength) continue
                lo = minOf(lo, doc.getLineNumber(range.startOffset) + 1)
                hi = maxOf(hi, doc.getLineNumber(range.endOffset) + 1)
            }
            if (lo == Int.MAX_VALUE || hi < lo) null else lo..hi
        }
    } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
        throw e
    } catch (e: com.intellij.openapi.project.IndexNotReadyException) {
        // Indexing started mid-scan. Degrade to the caller's fallback rather than
        // failing the whole recording.
        null
    }
}
