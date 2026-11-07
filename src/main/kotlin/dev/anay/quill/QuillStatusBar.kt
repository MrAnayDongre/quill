package dev.anay.quill

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent

class QuillStatusBarFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID

    override fun getDisplayName(): String = "Quill"

    override fun createWidget(project: Project): StatusBarWidget = QuillStatusWidget(project)

    companion object {
        const val ID = "dev.anay.quill.status"
    }
}

/** Shows the median time to the first suggestion character; clicking opens the settings. */
private class QuillStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    override fun ID(): String = QuillStatusBarFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = QuillService.getInstance().stats.label(QuillSettings.getInstance().state.enabled)

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String {
        val stats = QuillService.getInstance().stats
        val speed = stats.median()?.let { "Median ${it} ms, 95th percentile ${stats.p95()} ms over the last ${stats.count()} suggestions. " } ?: ""
        return "${speed}Click to open Quill settings"
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, QuillConfigurable::class.java)
    }
}
