package com.gradle.idetour.ui.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class TourStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID
    override fun getDisplayName(): String = "Code Tour"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = TourStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) { Disposer.dispose(widget) }
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val WIDGET_ID = "com.gradle.idetour.tourStatus"
    }
}
