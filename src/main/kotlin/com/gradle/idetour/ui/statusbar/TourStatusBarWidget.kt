package com.gradle.idetour.ui.statusbar

import com.gradle.idetour.tours.TourState
import com.gradle.idetour.tours.TourStateListener
import com.gradle.idetour.tours.ToursService
import com.gradle.idetour.tours.ToursServiceListener
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import java.awt.Component
import javax.swing.SwingUtilities

class TourStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    private val toursService = project.service<ToursService>()

    private val tourListener = object : TourStateListener {
        override fun onItemsChanged() = refresh()
        override fun onCurrentItemChanged() = refresh()
    }
    private val serviceListener = object : ToursServiceListener {
        override fun onTourAdded(tour: TourState) {
            tour.addListener(tourListener)
            refresh()
        }
        override fun onTourRemoved(tourId: String) = refresh()
    }

    init {
        toursService.addListener(serviceListener)
        for (tour in toursService.all()) tour.addListener(tourListener)
    }

    override fun ID(): String = TourStatusBarWidgetFactory.WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun getText(): String {
        val tour = toursService.all().lastOrNull() ?: return ""
        if (tour.items.isEmpty()) return "Tour: empty"
        val idx = tour.currentIndex.coerceAtLeast(0) + 1
        val total = tour.items.size
        val title = tour.title ?: tour.id.take(8)
        return "Tour: $idx/$total — $title"
    }

    override fun getAlignment(): Float = Component.LEFT_ALIGNMENT

    override fun getTooltipText(): String? = "Code Tour status"

    private fun refresh() {
        SwingUtilities.invokeLater {
            statusBar?.updateWidget(ID())
        }
    }

    override fun dispose() {
        toursService.removeListener(serviceListener)
        for (tour in toursService.all()) tour.removeListener(tourListener)
    }
}
