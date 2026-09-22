package com.ripple.rewind

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints

/**
 * End-of-line value chip used by the Rewind scrubber.
 *
 * Two visual states, because that contrast is the whole point of scrubbing:
 *  - live  = this line actually executed on the pass the slider is sitting on,
 *  - stale = this is the value the line was last left at on an earlier pass.
 *
 * Purely a painter: no state, no PSI, no document access.
 */
class RewindChipRenderer(
    private val text: String,
    private val live: Boolean
) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val metrics = editor.contentComponent.getFontMetrics(chipFont(editor))
        return metrics.stringWidth(text) + JBUI.scale(18)
    }

    // Inline elements take the line height; 0 means "don't override".
    override fun calcHeightInPixels(inlay: Inlay<*>): Int = 0

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes
    ) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val pad = JBUI.scale(2)
            val arc = JBUI.scale(8)
            val x = targetRegion.x + pad
            val y = targetRegion.y
            val w = (targetRegion.width - pad * 2).coerceAtLeast(1)
            val h = targetRegion.height.coerceAtLeast(1)

            g2.color = if (live) LIVE_BACKGROUND else STALE_BACKGROUND
            g2.fillRoundRect(x, y, w, h, arc, arc)

            if (live) {
                g2.color = LIVE_BORDER
                g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc)
            }

            g2.color = if (live) LIVE_FOREGROUND else STALE_FOREGROUND
            g2.font = chipFont(inlay.editor)
            val baseline = y + h - JBUI.scale(4)
            g2.drawString(text, x + JBUI.scale(7), baseline)
        } finally {
            g2.dispose()
        }
    }

    private fun chipFont(editor: Editor): Font {
        val base = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val size = (base.size - 1).coerceAtLeast(10).toFloat()
        return base.deriveFont(Font.PLAIN, size)
    }

    companion object {
        private val LIVE_BACKGROUND = JBColor(0xDCEBFF, 0x2E436E)
        private val LIVE_BORDER = JBColor(0x7EA6E0, 0x466BA6)
        private val LIVE_FOREGROUND = JBColor(0x1B3D73, 0xC9DCFF)
        private val STALE_BACKGROUND = JBColor(0xF2F3F5, 0x2B2D30)
        private val STALE_FOREGROUND = JBColor(0x9AA0A6, 0x6F737A)
    }
}
