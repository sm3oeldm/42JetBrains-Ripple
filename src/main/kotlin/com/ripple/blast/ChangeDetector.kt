package com.ripple.blast

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

/**
 * One changed method: the centre of a blast radius.
 *
 * PSI-free on purpose — see [BlastPsiKeys]. [BlastRadiusEngine] re-resolves the
 * key inside its own read action when it needs the live element again.
 */
data class RootSeed(
    val key: NavigationKey,
    val displayName: String,
    val line: Int,
    val filePath: String?,
    val isTest: Boolean
)

/** The result of [ChangeDetector.detect]: what we are going to blast from, and why. */
data class ChangeSet(
    val seeds: List<RootSeed>,
    val changedFileCount: Int,
    val source: Source,
    /** True when [BlastLimits.MAX_NODES] cut the seed list short. */
    val truncated: Boolean = false
) {
    /** Where the seeds came from. The UI shows this so nobody is misled about scope. */
    enum class Source {
        /** Real uncommitted changes from the VCS change list. */
        VCS,

        /** No VCS changes; we used the method under the caret. */
        FALLBACK_CARET,

        /** No VCS changes; we used every method in the supplied file. */
        FALLBACK_FILE,

        /** Nothing to scan. */
        NONE
    }

    val isEmpty: Boolean get() = seeds.isEmpty()

    companion object {
        fun none(): ChangeSet = ChangeSet(emptyList(), 0, Source.NONE)
    }
}

/**
 * Which methods did the user edit?
 *
 * GRANULARITY IS DELIBERATELY WHOLE-FILE. If a file is in the change list,
 * every method declared in it counts as changed. Mapping VCS line ranges onto
 * PSI offsets is hours of work, breaks the moment a diff hunk straddles a
 * method header, and produces a blast radius that is indistinguishable from
 * this one on any realistic edit. Whole-file is the honest, robust choice.
 *
 * DEMO SAFETY. [ChangeListManager]'s change list is an asynchronously refreshed
 * cache: right after IDE startup, right after an edit, or on a project with no
 * VCS at all, it is legitimately empty. An empty panel in that situation reads
 * as "the feature is broken". So the caller passes an explicit [Fallback] —
 * never a hidden guess — and gets a seed set either way. [ChangeSet.source]
 * records which path was taken so the UI can say so out loud.
 *
 * THREADING. Safe to call from a background thread (a `Task.Backgroundable`).
 * Every PSI touch below happens inside its own short [ReadAction]; no read
 * action is held across the whole file list.
 */
object ChangeDetector {

    /** What to do when the VCS change list yields nothing. Always explicit. */
    sealed interface Fallback {
        /** Yield an empty [ChangeSet]. Use when the caller wants the truth, not a demo. */
        data object None : Fallback

        /**
         * Use the method containing [caretOffset] in [file]. If the caret is not
         * inside a method, degrade to every method in that file.
         */
        data class CaretMethod(val file: VirtualFile, val caretOffset: Int) : Fallback

        /** Use every method declared in [file]. */
        data class WholeFile(val file: VirtualFile) : Fallback
    }

    /**
     * Collect the changed-method seed set.
     *
     * @param fallback what to do if the VCS change list is empty (or contains no
     *        Java methods). Explicit by contract — see the class comment.
     */
    fun detect(project: Project, fallback: Fallback): ChangeSet {
        val changedFiles = javaFilesFromVcs(project)

        val vcsSeeds = LinkedHashMap<String, RootSeed>()
        var truncated = false
        for (file in changedFiles) {
            ProgressManager.checkCanceled()
            // One read action per file: short, cancellable, never held across the loop.
            val seeds = readMethodsIn(project, file)
            truncated = mergeCapped(vcsSeeds, seeds) || truncated
            if (truncated) break
        }
        if (vcsSeeds.isNotEmpty()) {
            return ChangeSet(
                seeds = vcsSeeds.values.toList(),
                changedFileCount = changedFiles.size,
                source = ChangeSet.Source.VCS,
                truncated = truncated
            )
        }

        return when (fallback) {
            is Fallback.None -> ChangeSet.none()

            is Fallback.WholeFile -> fileFallback(project, fallback.file, ChangeSet.Source.FALLBACK_FILE)

            is Fallback.CaretMethod -> {
                val atCaret = readMethodAtCaret(project, fallback.file, fallback.caretOffset)
                if (atCaret != null) {
                    ChangeSet(listOf(atCaret), 1, ChangeSet.Source.FALLBACK_CARET)
                } else {
                    fileFallback(project, fallback.file, ChangeSet.Source.FALLBACK_FILE)
                }
            }
        }
    }

    private fun fileFallback(project: Project, file: VirtualFile, source: ChangeSet.Source): ChangeSet {
        val acc = LinkedHashMap<String, RootSeed>()
        val truncated = mergeCapped(acc, readMethodsIn(project, file))
        return if (acc.isEmpty()) {
            ChangeSet.none()
        } else {
            ChangeSet(acc.values.toList(), 1, source, truncated)
        }
    }

    /**
     * Java files with uncommitted modifications.
     *
     * [ChangeListManager.getAffectedFiles] is a plain accessor over an async
     * cache — no read action required, and it can legitimately return an empty
     * list. That emptiness is handled by the fallback, not by retrying.
     */
    private fun javaFilesFromVcs(project: Project): List<VirtualFile> =
        ChangeListManager.getInstance(project).affectedFiles
            .asSequence()
            .filter { it.isValid && !it.isDirectory && it.extension.equals("java", ignoreCase = true) }
            .distinct()
            .toList()

    /** Adds [seeds] to [into], stopping at [BlastLimits.MAX_NODES]. Returns true if it had to stop. */
    private fun mergeCapped(into: LinkedHashMap<String, RootSeed>, seeds: List<RootSeed>): Boolean {
        for (seed in seeds) {
            if (into.size >= BlastLimits.MAX_NODES) return true
            into.putIfAbsent(seed.key.id, seed)
        }
        return false
    }

    /** Every method declared anywhere in [file], including methods of nested classes. */
    private fun readMethodsIn(project: Project, file: VirtualFile): List<RootSeed> =
        ReadAction.compute<List<RootSeed>, RuntimeException> {
            if (!file.isValid) return@compute emptyList()
            val psiFile = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile
                ?: return@compute emptyList()
            val inTestRoot = TestIndex.isInTestSourceRoot(project, file)
            PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java)
                .mapNotNull { seedOf(project, it, inTestRoot) }
        }

    /** The method containing [caretOffset], or null if the caret is between members. */
    private fun readMethodAtCaret(project: Project, file: VirtualFile, caretOffset: Int): RootSeed? =
        ReadAction.compute<RootSeed?, RuntimeException> {
            if (!file.isValid) return@compute null
            val psiFile = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile
                ?: return@compute null
            if (caretOffset < 0 || caretOffset > psiFile.textLength) return@compute null
            val element = psiFile.findElementAt(caretOffset) ?: return@compute null
            val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return@compute null
            seedOf(project, method, TestIndex.isInTestSourceRoot(project, file))
        }

    /** Read action required. Returns null for methods of anonymous/local classes. */
    private fun seedOf(project: Project, method: PsiMethod, inTestRoot: Boolean): RootSeed? {
        val key = BlastPsiKeys.keyOf(method) ?: return null
        return RootSeed(
            key = key,
            displayName = BlastPsiKeys.displayNameOf(method),
            line = BlastPsiKeys.declarationLine(method),
            filePath = BlastPsiKeys.filePathOf(method),
            isTest = inTestRoot || TestIndex.looksLikeTest(project, method)
        )
    }
}
