package com.gradle.idetour.tours

import com.gradle.idetour.tours.model.HighlightSpec
import com.gradle.idetour.tours.model.InlayPosition
import com.gradle.idetour.tours.model.InlaySpec
import com.gradle.idetour.tours.model.TourItem
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.pointers.VirtualFilePointer
import com.intellij.openapi.vfs.pointers.VirtualFilePointerManager
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import java.util.UUID

data class TourItemEntry(
    val id: String,
    val spec: TourItem,
    val pointer: VirtualFilePointer
)

private data class MaterializedItem(
    val itemId: String,
    val inlays: List<Inlay<*>>,
    val highlighters: List<RangeHighlighter>
)

interface TourStateListener {
    fun onItemsChanged() {}
    fun onCurrentItemChanged() {}
}

class TourState(
    val id: String,
    val project: Project,
    val title: String?
) : Disposable {
    private val _items: MutableList<TourItemEntry> = mutableListOf()
    val items: List<TourItemEntry> get() = _items

    var currentIndex: Int = -1
        private set

    /** When true, materializeForEditor is a no-op. Set by [hide]; cleared by [show]. Panel-managed only. */
    var hidden: Boolean = false
        private set

    private val materialized: MutableMap<String, MaterializedItem> = mutableMapOf()
    private val listeners: MutableList<TourStateListener> = mutableListOf()

    fun addListener(listener: TourStateListener) { listeners.add(listener) }
    fun removeListener(listener: TourStateListener) { listeners.remove(listener) }
    private fun fireItemsChanged() { listeners.toList().forEach { it.onItemsChanged() } }
    private fun fireCurrentChanged() { listeners.toList().forEach { it.onCurrentItemChanged() } }

    /** Append items, resolving their files. Returns the assigned ids in order. */
    fun addItems(specs: List<TourItem>): List<String> {
        val added = mutableListOf<String>()
        for (spec in specs) {
            val id = spec.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            val absolutePath = if (spec.file.startsWith("/")) spec.file else "${project.basePath}/${spec.file}"
            val vf = LocalFileSystem.getInstance().findFileByPath(absolutePath)
            if (vf == null) {
                thisLogger().warn("[ide-tour-plugin] add_items: file not found: $absolutePath (item $id skipped)")
                continue
            }
            val pointer = VirtualFilePointerManager.getInstance().create(vf, this, null)
            _items.add(TourItemEntry(id, spec, pointer))
            added.add(id)
        }
        val justActivated = currentIndex < 0 && _items.isNotEmpty()
        if (justActivated) currentIndex = 0
        if (added.isNotEmpty()) fireItemsChanged()
        if (justActivated) fireCurrentChanged()
        return added
    }

    /** Materialize any items whose file matches the given editor's file. Idempotent per item; no-op when hidden. */
    fun materializeForEditor(editor: Editor) {
        if (hidden) return
        val vf = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        for (entry in _items) {
            if (entry.id in materialized) continue
            if (entry.pointer.file != vf) continue
            materialize(entry, editor)?.let { materialized[entry.id] = it }
        }
    }

    /** Dispose materialized annotations and mark hidden. Tour items + currentIndex are preserved. EDT required. */
    fun hide() {
        if (hidden) return
        disposeAllMaterialized()
        hidden = true
    }

    /** Clear the hidden flag. Caller is responsible for re-materializing into open editors. */
    fun show() {
        hidden = false
    }

    /** Remove an item by id. Disposes its materialized annotations and adjusts currentIndex. EDT required. */
    fun removeItem(itemId: String): Boolean {
        val idx = _items.indexOfFirst { it.id == itemId }
        if (idx < 0) return false
        disposeMaterializedFor(itemId)
        _items.removeAt(idx)
        val currentMoved = when {
            _items.isEmpty() -> { currentIndex = -1; true }
            idx < currentIndex -> { currentIndex--; true }
            idx == currentIndex -> { currentIndex = currentIndex.coerceAtMost(_items.size - 1); true }
            else -> false
        }
        repaintAll()
        fireItemsChanged()
        if (currentMoved) fireCurrentChanged()
        return true
    }

    /**
     * Replace an item's spec in place (same id, same position). Disposes the item's materialized annotations.
     * Caller is responsible for re-materializing via ToursService.materializeForOpenEditors(this) afterwards.
     * EDT required.
     */
    fun updateItem(itemId: String, newSpec: TourItem): Boolean {
        val idx = _items.indexOfFirst { it.id == itemId }
        if (idx < 0) return false
        val absolutePath = if (newSpec.file.startsWith("/")) newSpec.file else "${project.basePath}/${newSpec.file}"
        val vf = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return false
        disposeMaterializedFor(itemId)
        val pointer = VirtualFilePointerManager.getInstance().create(vf, this, null)
        _items[idx] = TourItemEntry(itemId, newSpec, pointer)
        fireItemsChanged()
        return true
    }

    /**
     * Replace the entire item list. Disposes all materialized annotations and resets currentIndex.
     * Caller is responsible for materializeForOpenEditors afterwards. EDT required.
     */
    fun setItems(specs: List<TourItem>): List<String> {
        disposeAllMaterialized()
        _items.clear()
        currentIndex = -1
        val added = addItems(specs)
        // addItems already fires itemsChanged + currentChanged on auto-activate
        return added
    }

    private fun disposeMaterializedFor(itemId: String) {
        materialized.remove(itemId)?.let { m ->
            m.inlays.forEach { it.dispose() }
            m.highlighters.forEach { it.dispose() }
        }
    }

    private fun disposeAllMaterialized() {
        for (m in materialized.values) {
            m.inlays.forEach { it.dispose() }
            m.highlighters.forEach { it.dispose() }
        }
        materialized.clear()
    }

    private fun materialize(entry: TourItemEntry, editor: Editor): MaterializedItem? {
        val document = editor.document
        val inlays = entry.spec.inlays.mapNotNull { spec ->
            val lineIdx = (spec.line - 1).coerceIn(0, document.lineCount - 1)
            val renderer = com.gradle.idetour.tours.TourInlayRenderer(this, entry.id, spec.text)
            when (spec.position) {
                InlayPosition.AboveLine -> editor.inlayModel.addBlockElement(
                    document.getLineStartOffset(lineIdx),
                    /* relatesToPrecedingText = */ false,
                    /* showAbove = */ true,
                    /* priority = */ 0,
                    renderer
                )
                InlayPosition.BelowLine -> editor.inlayModel.addBlockElement(
                    document.getLineStartOffset(lineIdx),
                    /* relatesToPrecedingText = */ true,
                    /* showAbove = */ false,
                    /* priority = */ 0,
                    renderer
                )
                InlayPosition.EndOfLine -> editor.inlayModel.addAfterLineEndElement(
                    document.getLineEndOffset(lineIdx),
                    /* relatesToPrecedingText = */ true,
                    renderer
                )
            }
        }
        val highlighters = entry.spec.highlights.mapNotNull { hSpec ->
            createHighlighter(editor, hSpec)
        }
        return MaterializedItem(entry.id, inlays, highlighters)
    }

    private fun createHighlighter(editor: Editor, spec: HighlightSpec): RangeHighlighter? {
        val document = editor.document
        val startLine = (spec.startLine - 1).coerceIn(0, document.lineCount - 1)
        val endLine = (spec.endLine - 1).coerceIn(0, document.lineCount - 1)
        val startOffset = document.getLineStartOffset(startLine) + (spec.startCol - 1).coerceAtLeast(0)
        val endOffset = (document.getLineStartOffset(endLine) + (spec.endCol - 1).coerceAtLeast(0))
            .coerceAtMost(document.textLength)
        if (endOffset <= startOffset) return null
        val attrs = TextAttributes(
            null,
            JBColor(Color(0xFFF8C5), Color(0x3C3F2D)),
            null,
            null,
            Font.PLAIN
        )
        return editor.markupModel.addRangeHighlighter(
            startOffset, endOffset,
            HighlighterLayer.SELECTION - 1,
            attrs,
            HighlighterTargetArea.EXACT_RANGE
        )
    }

    /** Move the current pointer to an item id; returns false if not found. Does not open files. */
    fun gotoItem(itemId: String): Int {
        val idx = _items.indexOfFirst { it.id == itemId }
        if (idx < 0) return -1
        currentIndex = idx
        repaintAll()
        fireCurrentChanged()
        return idx
    }

    fun gotoIndex(index: Int): Boolean {
        if (index !in _items.indices) return false
        currentIndex = index
        repaintAll()
        fireCurrentChanged()
        return true
    }

    private fun repaintAll() {
        for (m in materialized.values) {
            for (inlay in m.inlays) inlay.update()
        }
    }

    override fun dispose() {
        disposeAllMaterialized()
        _items.clear()
    }

    fun currentEntry(): TourItemEntry? = _items.getOrNull(currentIndex)
}
