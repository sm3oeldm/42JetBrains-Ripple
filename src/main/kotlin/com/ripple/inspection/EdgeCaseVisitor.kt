package com.ripple.inspection

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiForStatement
import com.intellij.psi.PsiIfStatement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.util.PsiTreeUtil

// Deterministic heuristics (§3.6): 2 narrow patterns with demo-reliable triggers.
// False negatives are fine; false positives kill the demo — keep it narrow.
class EdgeCaseVisitor(private val holder: ProblemsHolder) : JavaElementVisitor() {

    override fun visitForStatement(statement: PsiForStatement) {
        super.visitForStatement(statement)
        ProgressManager.checkCanceled()
        val condition = statement.condition as? PsiBinaryExpression ?: return
        // Classic off-by-one risk: loop bound compares to a `.length`/`.size()` call using `<=`.
        val opText = condition.operationSign.text
        val rhsText = condition.rOperand?.text.orEmpty()
        if (opText == "<=" && (rhsText.contains(".length") || rhsText.contains(".size("))) {
            holder.registerProblem(
                condition,
                "Possible off-by-one: loop bound uses '<=' against a length/size expression",
                GenerateMicroTestFix(statement)
            )
        }
    }

    override fun visitMethod(method: PsiMethod) {
        super.visitMethod(method)
        // Unguarded recursion: method calls itself with no visible base-case if/return.
        val body = method.body ?: return
        ProgressManager.checkCanceled()

        // Pre-filter on the reference NAME before resolving.
        //
        // resolveMethod() is expensive, and running it on every call site of
        // every method of every open file on every inspection pass made typing
        // in a large file feel like the IDE had hung. A self-call must be
        // spelled with this method's own name, so a string comparison discards
        // essentially all of them for free. Only survivors get resolved.
        val selfCalls = PsiTreeUtil.findChildrenOfType(body, PsiMethodCallExpression::class.java)
            .filter { it.methodExpression.referenceName == method.name }
            .filter {
                ProgressManager.checkCanceled()
                it.resolveMethod() == method
            }
        if (selfCalls.isNotEmpty()) {
            val hasEarlyReturn = PsiTreeUtil.findChildrenOfType(body, PsiIfStatement::class.java)
                .any { PsiTreeUtil.findChildOfType(it.thenBranch, PsiReturnStatement::class.java) != null }
            if (!hasEarlyReturn) {
                holder.registerProblem(
                    method.nameIdentifier ?: method,
                    "Recursive method with no obvious guarded base case — " +
                        "risk of unbounded recursion / StackOverflowError",
                    GenerateMicroTestFix(method)
                )
            }
        }
    }
}
