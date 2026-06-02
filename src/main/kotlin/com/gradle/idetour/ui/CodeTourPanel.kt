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
import java.awt.Dimension
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

/**
 * NOTE: this class deliberately does **not** extend `JPanel` and the inner `JBList`
 * deliberately is not subclassed. Both choices are required for plugin unload-safety
 * on macOS: the JBR's `sun.lwawt.macosx.CAccessible` registers each on-screen
 * `JComponent` against a static `javax.swing.Timer` and never unregisters them, so any
 * plugin-classloader-loaded `JComponent` subclass stays alive forever and pins the
 * plugin's classloader. We keep all our state as fields on this `Disposable` wrapper
 * and use plain (IDE/JDK) Swing classes for everything that ends up in the AWT tree.
 */
class CodeTourPanel(private val project: Project) : Disposable {
    /** The actual Swing component handed to the tool window's `ContentFactory`. */
    val component: JPanel = JPanel(BorderLayout())

    private val toursService = project.service<ToursService>()

    private val titleLabel = JBLabel("No active tour").apply {
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
    }
    private val itemListModel = DefaultListModel<TourItemEntry>()
    /**
     * Selection model wrapped around the default one to swallow the *next* selection
     * change when [rejectNextSelectionChange] is set. We use this to suppress the
     * spurious selection the L&F's `BasicListUI` mouse handler would otherwise apply
     * when the user clicks in the empty area below the last cell (it would otherwise
     * select the last item, briefly flashing focus before our `ListSelectionListener`
     * could revert it).
     */
    private var rejectNextSelectionChange = false
    private val gatedSelectionModel = object : javax.swing.DefaultListSelectionModel() {
        override fun setSelectionInterval(index0: Int, index1: Int) {
            if (rejectNextSelectionChange) {
                rejectNextSelectionChange = false
                return
            }
            super.setSelectionInterval(index0, index1)
        }
    }
    private val itemList: JBList<TourItemEntry> = JBList(itemListModel).apply {
        selectionModel = gatedSelectionModel
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = -1
    }
    private val itemRenderer = ItemRenderer()

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
    // All listeners attached to itemList must be saved & removed in dispose(): the
    // JBList outlives plugin unload via macOS CAccessible, and the listeners'
    // anonymous classes hold `this$0 -> CodeTourPanel`.
    private val itemResizeListener = object : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            itemList.fixedCellHeight = 0   // toggle to fire propertyChange
            itemList.fixedCellHeight = -1
        }
    }
    /**
     * Runs *before* the L&F's `BasicListUI` mouse handler (we re-add the L&F's
     * mouse listeners after ours in `init`, so ours fires first via Swing's
     * `AWTEventMulticaster` order). If the press is outside any cell, we arm
     * [rejectNextSelectionChange] so [gatedSelectionModel] drops the imminent
     * `setSelectionInterval` call — no selection ever changes, no flash.
     */
    private val mousePressTracker = object : MouseInputAdapter() {
        override fun mousePressed(e: MouseEvent) {
            val idx = itemList.locationToIndex(e.point)
            if (idx < 0) {
                rejectNextSelectionChange = true
                return
            }
            val bounds = itemList.getCellBounds(idx, idx)
            if (bounds == null || !bounds.contains(e.point)) {
                rejectNextSelectionChange = true
            }
        }
    }
    /**
     * "Preview" navigation: when the selection changes (mouse click on a cell,
     * arrow keys, Prev/Next buttons), scroll the editor to the item but keep
     * focus on the list. Out-of-cell clicks never reach here — they're filtered
     * upstream by [gatedSelectionModel] + [mousePressTracker].
     */
    private val itemSelectionListener = javax.swing.event.ListSelectionListener { e ->
        if (e.valueIsAdjusting) return@ListSelectionListener
        val idx = itemList.selectedIndex
        if (idx < 0) return@ListSelectionListener
        val tour = currentTour ?: return@ListSelectionListener
        if (idx !in tour.items.indices) return@ListSelectionListener
        if (idx == tour.currentIndex) return@ListSelectionListener
        tour.gotoIndex(idx)
        openCurrentItem(project, tour, focusEditor = false)
    }
    /** Enter on a selected item focuses the editor (IntelliJ "open" convention). */
    private val openInEditorAction = object : javax.swing.AbstractAction() {
        override fun actionPerformed(e: java.awt.event.ActionEvent) {
            val tour = currentTour ?: return
            if (tour.currentIndex !in tour.items.indices) return
            openCurrentItem(project, tour, focusEditor = true)
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
        .also { it.targetComponent = component }
        .also { (it as? Disposable)?.let { d -> Disposer.register(this@CodeTourPanel, d) } }

    init {
        val header = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.NORTH)
            add(titleLabel, BorderLayout.CENTER)
        }
        component.add(header, BorderLayout.NORTH)

        itemRenderer.tourProvider = { currentTour }
        itemList.cellRenderer = itemRenderer
        itemList.addComponentListener(itemResizeListener)
        itemList.addListSelectionListener(itemSelectionListener)
        // Swing's AWTEventMulticaster invokes listeners in addition order, so the
        // L&F's BasicListUI mouse handler (installed during UI init) runs first by
        // default. We need ours to run *before* it so [rejectNextSelectionChange]
        // is set in time for the L&F's selection update to be gated by
        // [gatedSelectionModel]. Re-add the existing listeners after ours to flip
        // the order.
        val existingMouseListeners = itemList.mouseListeners.toList()
        existingMouseListeners.forEach { itemList.removeMouseListener(it) }
        itemList.addMouseListener(mousePressTracker)
        existingMouseListeners.forEach { itemList.addMouseListener(it) }
        val enterKey = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)
        itemList.inputMap.put(enterKey, ENTER_ACTION_KEY)
        itemList.actionMap.put(ENTER_ACTION_KEY, openInEditorAction)
        component.add(JBScrollPane(itemList), BorderLayout.CENTER)

        toursService.addListener(serviceListener)
        switchTour(toursService.all().lastOrNull())
    }

    private fun switchTour(tour: TourState?) {
        val previous = currentTour
        previous?.removeListener(tourListener)
        if (previous != null && previous !== tour) {
            previous.hide()
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
        itemList.selectedIndex = tour.currentIndex - 1
    }

    private fun onNext() {
        val tour = currentTour ?: return
        if (tour.currentIndex >= tour.items.size - 1) return
        itemList.selectedIndex = tour.currentIndex + 1
    }

    private fun onStop() {
        val tour = currentTour ?: return
        toursService.end(tour.id)
    }

    override fun dispose() {
        toursService.removeListener(serviceListener)
        currentTour?.removeListener(tourListener)
        itemList.removeListSelectionListener(itemSelectionListener)
        itemList.removeMouseListener(mousePressTracker)
        itemList.removeComponentListener(itemResizeListener)
        // Sever the tourProvider lambda's capture of `this`. macOS CAccessible keeps a
        // cached reference to our ItemRenderer inside AccessibleJBListChild.cellRenderer
        // that we can't reliably reach to null. Severing tourProvider at least lets
        // CodeTourPanel + its listeners + the tour state graph become GC-able.
        itemRenderer.tourProvider = null
        // Empty the model so no TourItemEntry instances are reachable via the list.
        itemListModel.clear()
        // setCellRenderer(null) is a no-op on JBList; install a JDK renderer so any
        // future accessibility queries don't reach ItemRenderer.
        itemList.cellRenderer = javax.swing.DefaultListCellRenderer()
        val enterKey = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)
        itemList.inputMap.remove(enterKey)
        itemList.actionMap.remove(ENTER_ACTION_KEY)
        component.removeAll()
        tryClearMacAccessibility(itemList)
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

    private companion object {
        const val ENTER_ACTION_KEY = "ide-tour-open-in-editor"
    }
}

/** Top + bottom border insets on the renderer's JLabel (must match its EmptyBorder). */
private const val LABEL_VERTICAL_INSETS = 10

/**
 * macOS-only: try to null out `sun.lwawt.macosx.CAccessible.activeDescendant` for the
 * given component. The JBR's `CAccessible` caches the currently focused accessibility
 * child (an `AccessibleJBListChild`) and keeps it alive via a static `javax.swing.Timer`
 * queue. As long as that cache points at our list's child, it transitively pins the
 * plugin's classloader through the child's snapshot of `cellRenderer`. Reflectively
 * clearing the field is best-effort: silent no-op on non-macOS JBR builds or if the
 * internal API changes.
 */
private fun tryClearMacAccessibility(component: javax.swing.JComponent) {
    if (!System.getProperty("os.name").orEmpty().lowercase().contains("mac")) return
    try {
        val cAccessibleClass = Class.forName("sun.lwawt.macosx.CAccessible")
        val getCAccessible = cAccessibleClass.getDeclaredMethod(
            "getCAccessible", javax.accessibility.Accessible::class.java
        )
        getCAccessible.isAccessible = true
        val cAccessible = getCAccessible.invoke(null, component) ?: return
        val activeDescendantField = cAccessibleClass.getDeclaredField("activeDescendant")
        activeDescendantField.isAccessible = true
        activeDescendantField.set(cAccessible, null)
    } catch (_: Throwable) {
        // Best-effort; JBR-ˆinternal API may change between releases.
    }
}


/**
 * `tourProvider` is mutable+nullable so [CodeTourPanel.dispose] can null it. On macOS
 * the JBR's `CAccessible` caches our renderer inside `AccessibleJBListChild.cellRenderer`
 * and never releases it; nulling the provider here breaks the back-reference into
 * `CodeTourPanel` so the panel + its listeners can be GC'd even if `ItemRenderer`
 * itself lingers in the accessibility tree.
 */
private class ItemRenderer : ListCellRenderer<TourItemEntry> {
    @Volatile var tourProvider: (() -> TourState?)? = null
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
        val tour = tourProvider?.invoke()
        val prefix = when {
            tour == null || index < 0 -> "?"
            index < tour.currentIndex -> "✓"
            index == tour.currentIndex -> "▶"
            else -> "○"
        }
        val title = StringUtil.escapeXmlEntities(value.spec.title)
        val locationLabel = value.spec.location ?: defaultLocationOf(value.spec)
        val locationHtml = "<div style='margin-top:2px; font-size:90%; font-family:JetBrains Mono, monospace'>${StringUtil.escapeXmlEntities(locationLabel)}</div>"
        val notesHtml = value.spec.inlays.joinToString("") { inlay ->
            "<div style='margin-top:6px; color:#888'>${StringUtil.escapeXmlEntities(inlay.text)}</div>"
        }
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
        // Compute the wrapped HTML height at the target width. Reading label.preferredSize
        // alone gives the *unwrapped* natural size because Swing hasn't laid the View out
        // at the constrained width yet, leading to uniform too-small/too-large cell heights.
        // Force the View.setSize and then ask for its Y-span explicitly.
        val view = label.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey)
            as? javax.swing.text.View
        val height = if (view != null) {
            view.setSize(availableWidth.toFloat(), 0f)
            view.getPreferredSpan(javax.swing.text.View.Y_AXIS).toInt() + LABEL_VERTICAL_INSETS
        } else {
            label.preferredSize.height
        }
        // Reporting preferredSize.width = 0 makes JList's default
        // getScrollableTracksViewportWidth() return true (since the viewport is wider than 0),
        // so the list reflows when the tool window is resized.
        label.preferredSize = Dimension(0, height)
        return label
    }
}

/** Fallback "location" label when an item didn't supply one: file basename + first inlay's line. */
private fun defaultLocationOf(spec: com.gradle.idetour.tours.model.TourItem): String {
    val basename = spec.file.substringAfterLast('/').substringAfterLast('\\')
    val line = spec.inlays.firstOrNull()?.line
    return if (line != null) "$basename:$line" else basename
}
