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
    private val itemList = object : JBList<TourItemEntry>(itemListModel) {
        // Default JBList.locationToIndex clamps to a valid index even when the point is
        // below the last cell; that causes BasicListUI to flash a selection on press.
        // Returning -1 for such points skips the selection entirely.
        override fun locationToIndex(location: java.awt.Point): Int {
            val idx = super.locationToIndex(location)
            if (idx < 0) return -1
            val bounds = getCellBounds(idx, idx) ?: return -1
            return if (bounds.contains(location)) idx else -1
        }
    }.apply {
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

    private val actionGroup = DefaultActionGroup().apply {
        add(PrevAction())
        add(NextAction())
        addSeparator()
        add(StopAction())
    }
    private val toolbar: ActionToolbar = ActionManager.getInstance()
        .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actionGroup, true)
        .also { it.targetComponent = this }

    init {
        // Header: icon toolbar on top, then a thin title row.
        val header = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(titleLabel, BorderLayout.CENTER)
        }
        add(header, BorderLayout.NORTH)

        itemList.cellRenderer = ItemRenderer { currentTour }
        // When the list is resized (tool window resize), force JList to recompute
        // per-cell heights so HTML reflows at the new width.
        itemList.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                itemList.fixedCellHeight = 0   // toggle to fire propertyChange
                itemList.fixedCellHeight = -1
            }
        })
        itemList.addMouseListener(object : MouseInputAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = itemList.locationToIndex(e.point)
                if (idx < 0) return
                val bounds = itemList.getCellBounds(idx, idx) ?: return
                // locationToIndex clamps to a valid index when clicking past the last row;
                // reject clicks outside the actual cell rectangle.
                if (!bounds.contains(e.point)) {
                    itemList.clearSelection()
                    return
                }
                val tour = currentTour ?: return
                if (idx !in tour.items.indices) return
                tour.gotoIndex(idx)
                openCurrentItem(project, tour)
            }
        })
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
        // Each note is rendered as its own <div> with a top margin, so multiple notes look
        // like discrete paragraphs rather than a blob of run-together lines.
        val notesHtml = value.spec.inlays.joinToString("") { inlay ->
            "<div style='margin-top:6px; color:#888'>${StringUtil.escapeXmlEntities(inlay.text)}</div>"
        }
        // Adapt to the list's current width so HTML wraps at the tool window's bounds.
        val availableWidth = (list.width - 24).coerceAtLeast(120)
        label.text = "<html><body style='width: ${availableWidth}px'><b>$prefix&nbsp;&nbsp;$title</b>$notesHtml</body></html>"

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
