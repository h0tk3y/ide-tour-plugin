package com.gradle.idetour.tours

import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Rectangle

class TourInlayRenderer(
    private val tour: TourState,
    private val itemId: String,
    private val text: String
) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val metrics = inlay.editor.contentComponent.getFontMetrics(font)
        return metrics.stringWidth(displayText()) + 8
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        g.font = font
        g.color = JBColor.GRAY
        val metrics = editor.contentComponent.getFontMetrics(font)
        g.drawString(displayText(), targetRegion.x + 4, targetRegion.y + metrics.ascent)
    }

    private fun displayText(): String {
        val itemIndex = tour.items.indexOfFirst { it.id == itemId }
        val prefix = when {
            itemIndex < 0 -> "?"
            itemIndex < tour.currentIndex -> "✓"
            itemIndex == tour.currentIndex -> "▶"
            else -> "○"
        }
        return "$prefix $text"
    }
}
