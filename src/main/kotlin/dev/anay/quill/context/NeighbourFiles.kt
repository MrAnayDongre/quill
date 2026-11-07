package dev.anay.quill.context

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import dev.anay.quill.completion.ContextSnippet
import dev.anay.quill.settings.Exclusions

/** Picks the first lines of a few other project files. Call it inside a read action. */
internal object NeighbourFiles {
    private const val MAX_FILES = 3
    private const val MAX_LINES = 15
    private const val MAX_FILE_BYTES = 256 * 1024L

    fun collect(project: Project, current: VirtualFile, excludedPatterns: String): List<ContextSnippet> {
        val candidates = FileEditorManager.getInstance(project).openFiles.asSequence() +
            EditorHistoryManager.getInstance(project).fileList.asSequence()
        val index = ProjectFileIndex.getInstance(project)
        return candidates.distinct()
            .filter { it != current && it.isValid && !it.isDirectory && it.length <= MAX_FILE_BYTES }
            .filter { !it.fileType.isBinary && index.isInContent(it) && !Exclusions.isExcluded(it.name, excludedPatterns) }
            .mapNotNull { snippet(it) }
            .take(MAX_FILES)
            .toList()
    }

    private fun snippet(file: VirtualFile): ContextSnippet? {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val head = document.charsSequence.lineSequence().take(MAX_LINES).joinToString("\n").trim()
        return head.takeIf { it.isNotEmpty() }?.let { ContextSnippet(file.name, it) }
    }
}
