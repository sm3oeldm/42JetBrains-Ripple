package com.ripple.engine

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Is the bytecode we are about to trace actually the code on screen?
 *
 * This exists because of a genuinely nasty failure. A developer added a new
 * method, pressed Analyze, and got values back — but the compiled class was two
 * and a half hours old and did not contain the new method at all. JDI happily
 * set breakpoints using the OLD class's line table, so the plugin painted values
 * from deleted code onto the new line numbers. Every number on screen was
 * wrong, and nothing said so.
 *
 * A tracer that silently reports stale values is worse than one that refuses to
 * run: wrong data that looks right is how people lose hours. So we check, and
 * when the check fails we say exactly which class is stale and what to do.
 */
object StalenessCheck {

    private val log = Logger.getInstance(StalenessCheck::class.java)

    /** Filesystem timestamps are coarse; ignore sub-second differences. */
    private const val TOLERANCE_MS = 2_000L

    sealed interface Verdict {
        data object Fresh : Verdict
        /** [details] is user-facing: short, specific, and actionable. */
        data class Stale(val details: List<String>) : Verdict {
            val summary: String
                get() = if (details.size == 1) details.first()
                else "${details.size} classes are out of date: " + details.take(3).joinToString("; ")
        }
    }

    /**
     * @param classpathRoot where the .class files live
     * @param targets fully-qualified class name to the source file it came from.
     *        A null source means we cannot judge it, and we do not guess.
     */
    fun check(classpathRoot: String, targets: Map<String, String?>): Verdict {
        val root = File(classpathRoot)
        if (!root.isDirectory) return Verdict.Fresh

        val problems = ArrayList<String>()
        for ((fqcn, sourcePath) in targets) {
            val classFile = classFileFor(root, fqcn)
            if (classFile == null) {
                problems += "${fqcn.substringAfterLast('.')} has never been compiled"
                continue
            }
            if (sourcePath == null) continue
            val source = File(sourcePath)
            if (!source.isFile) continue

            val drift = source.lastModified() - classFile.lastModified()
            if (drift > TOLERANCE_MS) {
                val minutes = drift / 60_000
                problems += if (minutes >= 1) {
                    "${fqcn.substringAfterLast('.')} was edited $minutes min after it was last compiled"
                } else {
                    "${fqcn.substringAfterLast('.')} was edited after it was last compiled"
                }
            }
        }
        if (problems.isEmpty()) return Verdict.Fresh
        log.info("Ripple: refusing to trace stale bytecode — $problems")
        return Verdict.Stale(problems)
    }

    /**
     * Also catches the case the timestamp check cannot: the class exists and is
     * newer than the file, but the method we want is not in it (because the
     * class was compiled before the method was written and the file was touched
     * afterwards by something else).
     */
    fun classContainsNothing(classpathRoot: String, fqcn: String): Boolean =
        classFileFor(File(classpathRoot), fqcn) == null

    private fun classFileFor(root: File, fqcn: String): File? {
        val relative = fqcn.replace('.', File.separatorChar) + ".class"
        val nested = File(root, relative)
        if (nested.isFile) return nested
        // Default package, or a flat output layout.
        val flat = File(root, fqcn.substringAfterLast('.') + ".class")
        return if (flat.isFile) flat else null
    }
}
