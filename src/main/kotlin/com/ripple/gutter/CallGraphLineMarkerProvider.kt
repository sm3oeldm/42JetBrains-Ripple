package com.ripple.gutter

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod

/**
 * Gutter lens (§3.7): an icon beside every method name that opens the
 * caller/callee popup.
 *
 * Two rules this implementation follows, both of which the platform enforces:
 *
 *  1. Only ever return a marker for a LEAF element. Anchoring on the method's
 *     [PsiIdentifier] rather than the [PsiMethod] is why this is correct —
 *     returning a marker for a composite element logs an error and can paint
 *     the icon on the wrong line.
 *  2. Do NO expensive work here. This runs on every highlighting pass for every
 *     element in the file. The actual reference search happens only when the
 *     icon is clicked, inside a background read action — see
 *     [CallGraphPopupPanel].
 */
class CallGraphLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element !is PsiIdentifier) return null
        val method = element.parent as? PsiMethod ?: return null
        if (method.nameIdentifier !== element) return null

        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.Actions.Diff,
            { "Ripple: view callers / callees" },
            { event, elt ->
                val target = (elt.parent as? PsiMethod)?.takeIf { it.isValid }
                if (target != null) {
                    CallGraphPopupPanel.show(target, event.component, event)
                }
            },
            GutterIconRenderer.Alignment.LEFT,
            { "Ripple call graph" }
        )
    }
}
