package com.ripple.ai

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.util.PsiTreeUtil
import com.ripple.blast.BlastNode
import com.ripple.blast.BlastPsiKeys
import com.ripple.blast.BlastResult
import com.ripple.engine.BlastTraceResult

/**
 * Builds a test-generation prompt that is GROUNDED, and generates the test.
 *
 * The brief asks what makes a test creator "more than call the API with the
 * function body: does it use surrounding context, existing test conventions in
 * the repo, or catch edge cases a generic prompt would miss?"
 *
 * Four pieces of grounding, none of which a generic prompt has:
 *
 *  1. THE METHOD's real source, read from PSI.
 *  2. WHY IT MATTERS — its distance and route from the method the developer
 *     just changed. That is the blast radius, i.e. real codebase context rather
 *     than a selected snippet.
 *  3. OBSERVED RUNTIME VALUES — the actual arguments and results captured by
 *     tracing a real run. A generic prompt invents `new double[]{1,2,3}`; we
 *     pass the values that genuinely flowed through the method. This is the
 *     piece nothing else can supply, and it is why the two halves of this
 *     plugin belong together: the red list says WHICH method needs a test, the
 *     recording says WITH WHAT.
 *  4. THE REPO's existing test style, read from a real test file, so the output
 *     matches the conventions already in the project instead of the model's
 *     defaults.
 */
object TestGenerator {

    /** Everything the prompt needs, gathered off the EDT and holding no PSI. */
    data class Context(
        val targetSource: String,
        val targetDisplay: String,
        val packageName: String?,
        val hops: Int,
        val viaPath: List<String>,
        val changedMethod: String,
        val observedValues: List<String>,
        val styleExample: String?
    ) {
        val hasRuntimeEvidence: Boolean get() = observedValues.isNotEmpty()
    }

    private const val MAX_SOURCE_CHARS = 2_500
    private const val MAX_STYLE_CHARS = 1_200
    private const val MAX_OBSERVED_LINES = 12

    /**
     * Collect grounding for [node]. Call from a background thread; every PSI
     * touch is wrapped in its own short read action.
     */
    fun contextFor(
        project: Project,
        node: BlastNode,
        result: BlastResult,
        trace: BlastTraceResult?
    ): Context? = ReadAction.compute<Context?, RuntimeException> {
        if (project.isDisposed) return@compute null
        val method = BlastPsiKeys.resolve(project, node.key) ?: return@compute null

        val source = method.text?.take(MAX_SOURCE_CHARS) ?: return@compute null
        val owner = PsiTreeUtil.getParentOfType(method, PsiClass::class.java)
        val pkg = (method.containingFile as? PsiJavaFile)?.packageName?.takeIf { it.isNotBlank() }

        Context(
            targetSource = source,
            targetDisplay = node.displayName,
            packageName = pkg,
            hops = node.hops,
            viaPath = routeTo(node, result),
            changedMethod = result.roots.firstOrNull()?.displayName?.substringBefore('(') ?: "the changed method",
            observedValues = observedFor(node, trace),
            styleExample = existingTestStyle(project, owner)
        )
    }

    /**
     * The chain from the changed method out to [node], so the model knows why
     * this method is worth testing at all.
     */
    private fun routeTo(node: BlastNode, result: BlastResult): List<String> {
        fun walk(current: BlastNode, trail: List<String>): List<String>? {
            if (current.key.id == node.key.id) return trail
            for (child in current.children) {
                walk(child, trail + current.displayName.substringBefore('('))?.let { return it }
            }
            return null
        }
        for (root in result.roots) {
            walk(root, emptyList())?.let { return it }
        }
        return emptyList()
    }

    /**
     * Real values seen at runtime, formatted for the prompt.
     *
     * Deliberately the FIRST snapshot on each distinct line: at method entry the
     * parameters are in scope and still hold the caller's arguments, which is
     * exactly what a test needs to reproduce the call.
     */
    private fun observedFor(node: BlastNode, trace: BlastTraceResult?): List<String> {
        val nodeTrace = trace?.byNode?.get(node.key.id) ?: return emptyList()
        return nodeTrace.events
            .asSequence()
            .distinctBy { it.lineNumber }
            .filter { it.variableSnapshots.isNotEmpty() }
            .take(MAX_OBSERVED_LINES)
            .map { e ->
                "line ${e.lineNumber}: " + e.variableSnapshots.entries.joinToString(", ") { "${it.key}=${it.value}" }
            }
            .toList()
    }

    /**
     * A real test from this repo, used as a style exemplar.
     *
     * Matching the project's own conventions (JUnit 4 vs 5, naming, assertion
     * style) is the difference between a test someone keeps and a test someone
     * rewrites.
     */
    private fun existingTestStyle(project: Project, owner: PsiClass?): String? = try {
        val scope = GlobalSearchScope.projectScope(project)
        val cache = PsiShortNamesCache.getInstance(project)
        val ownerName = owner?.name
        val candidates = cache.allClassNames
            .asSequence()
            .filter { it.endsWith("Test") || it.endsWith("Tests") || it.endsWith("Spec") }
            .flatMap { cache.getClassesByName(it, scope).asSequence() }
            .toList()
        // Prefer a test for a sibling in the same package: closest conventions.
        val preferred = candidates.firstOrNull { c ->
            ownerName != null && c.name?.startsWith(ownerName.take(3)) == true
        } ?: candidates.firstOrNull()
        preferred?.containingFile?.text?.take(MAX_STYLE_CHARS)
    } catch (e: com.intellij.openapi.project.IndexNotReadyException) {
        null
    }

    // ------------------------------------------------------------------
    // Prompt
    // ------------------------------------------------------------------

    fun systemPrompt(): String =
        "You write Java unit tests. Output ONLY one complete Java test class inside a single " +
            "```java code block. No prose, no explanation outside the code. The class must compile: " +
            "correct package, correct imports, no placeholder identifiers, no TODO."

    fun userPrompt(ctx: Context): String = buildString {
        appendLine("Write a characterization test for the method below. It currently has NO test reaching it.")
        appendLine()
        appendLine("WHY IT MATTERS:")
        append("It is ${ctx.hops} call hop${if (ctx.hops == 1) "" else "s"} from ${ctx.changedMethod}, ")
        appendLine("which the developer just changed.")
        if (ctx.viaPath.isNotEmpty()) appendLine("Route: ${ctx.changedMethod} <- ${ctx.viaPath.joinToString(" <- ")}")
        appendLine("Nothing in the test suite currently reaches it, so a regression here would be silent.")
        appendLine()
        ctx.packageName?.let { appendLine("PACKAGE: $it") }
        appendLine("METHOD UNDER TEST (${ctx.targetDisplay}):")
        appendLine(ctx.targetSource)
        appendLine()
        if (ctx.hasRuntimeEvidence) {
            appendLine("OBSERVED RUNTIME VALUES — captured by tracing a REAL run of this program.")
            appendLine("These are facts, not guesses. Use them as the test inputs and expected outputs")
            appendLine("so the test pins what the code ACTUALLY does today:")
            ctx.observedValues.forEach { appendLine("  $it") }
            appendLine()
            appendLine("Because these values describe current behaviour, add a short comment saying the")
            appendLine("asserted value reflects existing behaviour and may itself be a bug.")
            appendLine()
        } else {
            appendLine("No runtime values were captured for this method — it never executed in the")
            appendLine("recorded run. Choose boundary inputs and assert on behaviour you can derive")
            appendLine("from the source alone. Do not invent a specific expected value you cannot justify.")
            appendLine()
        }
        ctx.styleExample?.let {
            appendLine("EXISTING TEST STYLE IN THIS REPO — match this framework, imports and naming:")
            appendLine(it)
        }
    }
}
