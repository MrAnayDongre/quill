package dev.anay.quill.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import dev.anay.quill.QuillBundle

class QuillStatusBarFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID

    override fun getDisplayName(): String = QuillBundle.message("status.widget.name")

    override fun createWidget(project: Project): StatusBarWidget = QuillStatusWidget(project)

    companion object {
        const val ID = "dev.anay.quill.status"
    }
}
