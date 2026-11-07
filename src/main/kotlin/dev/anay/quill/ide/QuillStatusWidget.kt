package dev.anay.quill.ide

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import dev.anay.quill.settings.QuillSettings
import java.awt.Component
import java.awt.event.MouseEvent

/** Shows the median time to the first suggestion character; clicking opens the settings. */
internal class QuillStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    override fun ID(): String = QuillStatusBarFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = statusLabel(QuillSettings.getInstance().state.enabled, QuillService.getInstance().stats)

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String = statusTooltip(QuillService.getInstance().stats)

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, QuillConfigurable::class.java)
    }
}
