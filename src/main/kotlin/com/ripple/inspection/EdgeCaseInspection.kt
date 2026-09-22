package com.ripple.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.psi.PsiElementVisitor

class EdgeCaseInspection : LocalInspectionTool() {
    override fun getDisplayName() = "Ripple edge-case hypothesizer"
    override fun getGroupDisplayName() = "Ripple"
    override fun getShortName() = "RippleEdgeCase"

    override fun buildVisitor(
        holder: com.intellij.codeInspection.ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor = EdgeCaseVisitor(holder)
}
