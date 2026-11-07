package dev.anay.quill.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class QuillStatusBarFactory : StatusBarWidgetFactory {
    override fun getId(): String = ID

    override fun getDisplayName(): String = "Quill"

    override fun createWidget(project: Project): StatusBarWidget = QuillStatusWidget(project)

    companion object {
        const val ID = "dev.anay.quill.status"
    }
}
