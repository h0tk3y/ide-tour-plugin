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
        return (displayLines().maxOfOrNull { metrics.stringWidth(it) } ?: 0) + 8
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        displayLines().size * inlay.editor.lineHeight

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val editor = inlay.editor
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        g.font = font
        g.color = JBColor.GRAY
        val metrics = editor.contentComponent.getFontMetrics(font)
        val lineHeight = editor.lineHeight
        displayLines().forEachIndexed { i, line ->
            g.drawString(line, targetRegion.x + 4, targetRegion.y + i * lineHeight + metrics.ascent)
        }
    }

    /**
     * The text split into rendered lines. The state prefix (`✓`/`▶`/`○`) is attached to
     * the first line only; continuation lines are indented to align under it.
     */
    private fun displayLines(): List<String> {
        val prefix = statePrefix()
        return text.split('\n').mapIndexed { i, line ->
            if (i == 0) "$prefix $line" else "  $line"
        }
    }

    private fun statePrefix(): String {
        val itemIndex = tour.items.indexOfFirst { it.id == itemId }
        return when {
            itemIndex < 0 -> "?"
            itemIndex < tour.currentIndex -> "✓"
            itemIndex == tour.currentIndex -> "▶"
            else -> "○"
        }
    }
}
