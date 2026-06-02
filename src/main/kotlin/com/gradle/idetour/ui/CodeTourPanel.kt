package com.gradle.idetour.ui

import com.gradle.idetour.tours.TourItemEntry
import com.gradle.idetour.tours.TourState
import com.gradle.idetour.tours.TourStateListener
import com.gradle.idetour.tours.ToursService
import com.gradle.idetour.tours.ToursServiceListener
import com.gradle.idetour.tours.openCurrentItem
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.MouseInputAdapter

class CodeTourPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val toursService = project.service<ToursService>()

    private val titleLabel = JBLabel("No active tour").apply {
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
    }
    private val itemListModel = DefaultListModel<TourItemEntry>()
    // Top-level class (not an anonymous inner) so the JBList doesn't carry a
    // `this$0 -> CodeTourPanel` reference that survives via macOS CAccessible.
    private val itemList: JBList<TourItemEntry> = TourItemList(itemListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = -1
    }

    private var currentTour: TourState? = null
    private val tourListener = object : TourStateListener {
        override fun onItemsChanged() = refresh()
        override fun onCurrentItemChanged() = refresh()
    }
    private val serviceListener = object : ToursServiceListener {
        override fun onTourAdded(tour: TourState) { switchTour(tour) }
        override fun onTourRemoved(tourId: String) {
            if (currentTour?.id == tourId) switchTour(toursService.all().lastOrNull())
        }
    }

    // Saved so we can removeMouseListener / removeComponentListener on dispose; the
    // JBList stays alive past unload via macOS CAccessible, and the listeners' anonymous
    // classes would otherwise hold an implicit reference back to CodeTourPanel.
    private val itemResizeListener = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            itemList.fixedCellHeight = 0   // toggle to fire propertyChange
            itemList.fixedCellHeight = -1
        }
    }
    private val itemMouseListener = object : MouseInputAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            val idx = itemList.locationToIndex(e.point)
            if (idx < 0) return
            val bounds = itemList.getCellBounds(idx, idx) ?: return
            if (!bounds.contains(e.point)) {
                itemList.clearSelection()
                return
            }
            val tour = currentTour ?: return
            if (idx !in tour.items.indices) return
            tour.gotoIndex(idx)
            openCurrentItem(project, tour)
        }
    }

    private val actionGroup = DefaultActionGroup().apply {
        add(PrevAction())
        add(NextAction())
        addSeparator()
        add(StopAction())
    }
    private val toolbar: ActionToolbar = ActionManager.getInstance()
        .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actionGroup, true)
        .also { it.targetComponent = this }
        // Tie the toolbar's lifetime to this panel so it (and its inner-class
        // AnActions, which hold an implicit reference to CodeTourPanel) doesn't
        // outlive the plugin classloader on unload.
        .also { (it as? Disposable)?.let { d -> Disposer.register(this@CodeTourPanel, d) } }

    init {
        // Header: icon toolbar on top, then a thin title row.
        val header = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(titleLabel, BorderLayout.CENTER)
        }
        add(header, BorderLayout.NORTH)

        itemList.cellRenderer = ItemRenderer { currentTour }
        itemList.addComponentListener(itemResizeListener)
        itemList.addMouseListener(itemMouseListener)
        add(JBScrollPane(itemList), BorderLayout.CENTER)

        toursService.addListener(serviceListener)
        switchTour(toursService.all().lastOrNull())
    }

    private fun switchTour(tour: TourState?) {
        val previous = currentTour
        previous?.removeListener(tourListener)
        if (previous != null && previous !== tour) {
            previous.hide()  // dispose its inlays+highlights from all editors
        }
        currentTour = tour
        if (tour != null) {
            tour.addListener(tourListener)
            tour.show()
            toursService.materializeForOpenEditors(tour)
        }
        refresh()
    }

    private fun refresh() {
        SwingUtilities.invokeLater {
            val tour = currentTour
            if (tour == null) {
                titleLabel.text = "No active tour"
                itemListModel.clear()
                toolbar.updateActionsImmediately()
                return@invokeLater
            }
            val total = tour.items.size
            val pos = if (total == 0) 0 else tour.currentIndex.coerceAtLeast(0) + 1
            titleLabel.text = "${tour.title ?: tour.id.take(8)} — $pos/$total"
            itemListModel.clear()
            for (entry in tour.items) itemListModel.addElement(entry)
            val sel = tour.currentIndex
            if (sel in 0 until itemListModel.size()) {
                itemList.selectedIndex = sel
                itemList.ensureIndexIsVisible(sel)
            }
            itemList.repaint()
            toolbar.updateActionsImmediately()
        }
    }

    private fun onPrev() {
        val tour = currentTour ?: return
        if (tour.currentIndex <= 0) return
        tour.gotoIndex(tour.currentIndex - 1)
        openCurrentItem(project, tour)
    }

    private fun onNext() {
        val tour = currentTour ?: return
        if (tour.currentIndex >= tour.items.size - 1) return
        tour.gotoIndex(tour.currentIndex + 1)
        openCurrentItem(project, tour)
    }

    private fun onStop() {
        val tour = currentTour ?: return
        toursService.end(tour.id)
    }

    override fun dispose() {
        toursService.removeListener(serviceListener)
        currentTour?.removeListener(tourListener)
        // Explicitly drop listeners and cell renderer from the JBList. The JBList itself
        // survives plugin unload via macOS CAccessible; if any of these still pointed back
        // to CodeTourPanel, the entire plugin classloader would stay pinned.
        itemList.removeMouseListener(itemMouseListener)
        itemList.removeComponentListener(itemResizeListener)
        itemList.cellRenderer = null
        // Detach our Swing tree (cascades removeNotify down children).
        removeAll()
    }

    private inner class PrevAction : AnAction(
        "Previous Stop", "Go to the previous tour stop", AllIcons.Actions.Back
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = onPrev()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = (currentTour?.currentIndex ?: -1) > 0
        }
    }

    private inner class NextAction : AnAction(
        "Next Stop", "Go to the next tour stop", AllIcons.Actions.Forward
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = onNext()
        override fun update(e: AnActionEvent) {
            val t = currentTour
            e.presentation.isEnabled = t != null && t.currentIndex >= 0 && t.currentIndex < t.items.size - 1
        }
    }

    private inner class StopAction : AnAction(
        "Stop Tour", "End the tour and dispose its annotations", AllIcons.Actions.Suspend
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = onStop()
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentTour != null
        }
    }
}

/** Top-level subclass (not an anonymous inner class of CodeTourPanel) so that the JBList,
 *  which can survive plugin unload via macOS CAccessible, doesn't carry an implicit
 *  reference to CodeTourPanel and pin the plugin classloader. */
private class TourItemList(model: javax.swing.ListModel<TourItemEntry>) : JBList<TourItemEntry>(model) {
    override fun locationToIndex(location: java.awt.Point): Int {
        val idx = super.locationToIndex(location)
        if (idx < 0) return -1
        val bounds = getCellBounds(idx, idx) ?: return -1
        return if (bounds.contains(location)) idx else -1
    }
    override fun getScrollableTracksViewportWidth(): Boolean = true
}

private class ItemRenderer(private val tourProvider: () -> TourState?) : ListCellRenderer<TourItemEntry> {
    private val label = JBLabel().apply {
        border = BorderFactory.createEmptyBorder(4, 8, 6, 8)
        isOpaque = true
        verticalAlignment = SwingConstants.TOP
    }

    override fun getListCellRendererComponent(
        list: JList<out TourItemEntry>,
        value: TourItemEntry,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val tour = tourProvider()
        val prefix = when {
            tour == null || index < 0 -> "?"
            index < tour.currentIndex -> "✓"
            index == tour.currentIndex -> "▶"
            else -> "○"
        }
        val title = StringUtil.escapeXmlEntities(value.spec.title)
        val locationLabel = value.spec.location ?: defaultLocationOf(value.spec)
        // Render in a monospace font (JetBrains Mono if present, else generic monospace)
        // so it reads as a code / path identifier — visually distinct from the title and
        // notes without needing a separate color.
        val locationHtml = "<div style='margin-top:2px; font-size:90%; font-family:JetBrains Mono, monospace'>${StringUtil.escapeXmlEntities(locationLabel)}</div>"
        // Each note is rendered as its own <div> with a top margin, so multiple notes look
        // like discrete paragraphs rather than a blob of run-together lines.
        val notesHtml = value.spec.inlays.joinToString("") { inlay ->
            "<div style='margin-top:6px; color:#888'>${StringUtil.escapeXmlEntities(inlay.text)}</div>"
        }
        // Swing's HTMLEditorKit doesn't reliably honor `width: Npx` on <body> for
        // layout — long words still lay out at their natural width and overflow.
        // `<table width="N">` IS honored consistently in HTML 3.2 dialect, so we
        // wrap the content in a fixed-width table cell to force wrapping.
        val availableWidth = (list.width - 16).coerceAtLeast(120)
        label.text = "<html><table width=\"$availableWidth\" cellspacing=\"0\" cellpadding=\"0\">" +
            "<tr><td><b>$prefix&nbsp;&nbsp;$title</b>$locationHtml$notesHtml</td></tr></table></html>"

        if (isSelected) {
            label.background = list.selectionBackground
            label.foreground = list.selectionForeground
        } else {
            label.background = list.background
            label.foreground = list.foreground
        }
        return label
    }
}

/** Fallback "location" label when an item didn't supply one: file basename + first inlay's line. */
private fun defaultLocationOf(spec: com.gradle.idetour.tours.model.TourItem): String {
    val basename = spec.file.substringAfterLast('/').substringAfterLast('\\')
    val line = spec.inlays.firstOrNull()?.line
    return if (line != null) "$basename:$line" else basename
}
