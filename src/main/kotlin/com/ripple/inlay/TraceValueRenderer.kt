package com.ripple.inlay

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.ui.JBColor
import com.intellij.openapi.editor.colors.EditorFontType
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import com.intellij.openapi.editor.markup.TextAttributes

// Ghosted value chip (§3.5): light/dark-aware background, info foreground.
class TraceValueRenderer(private val text: String) : EditorCustomElementRenderer {
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        return editor.contentComponent.getFontMetrics(font(editor)).stringWidth(text) + 12
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = 0 // inline: use line height

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        g.color = JBColor(0xEDF3FF, 0x2B2D30)
        g.fillRoundRect(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height, 6, 6)
        g.color = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
        g.font = font(inlay.editor)
        g.drawString(text, targetRegion.x + 6, targetRegion.y + targetRegion.height - 4)
    }

    private fun font(editor: Editor): Font {
        val base = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        return base.deriveFont(Font.PLAIN, (base.size - 1).coerceAtLeast(10).toFloat())
    }
}
