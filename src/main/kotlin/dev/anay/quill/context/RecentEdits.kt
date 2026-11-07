package dev.anay.quill.context

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import dev.anay.quill.completion.ContextSnippet
import dev.anay.quill.settings.Exclusions
import dev.anay.quill.settings.QuillSettings

/**
 * Remembers the lines the user changed most recently, in any file. What someone just did is the best hint of what
 * they do next: the second of two similar edits, a rename that is still spreading, a new function about to be called.
 * Text from excluded files is never kept.
 */
@Service(Service.Level.APP)
internal class RecentEdits {
    private class Edit(val file: VirtualFile, val line: Int, val text: String)

    // Line numbers are those at the time of the edit and are not updated when lines move. That is good enough for a hint.
    private val edits = ArrayDeque<Edit>()

    fun record(document: Document, offset: Int) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (file.fileType.isBinary || Exclusions.isExcluded(file.name, QuillSettings.getInstance().state.excludedPatterns)) return
        val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
        val text = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
        if (text.isEmpty()) return
        synchronized(edits) {
            edits.removeAll { it.file == file && it.line == line }
            edits.addLast(Edit(file, line, text.take(MAX_LINE_CHARS)))
            while (edits.size > MAX_EDITS) edits.removeFirst()
        }
    }

    /**
     * The recent edits other than the one in progress, oldest first. Only files of [project] qualify, so code from
     * another window or from outside any project never reaches this project's requests, and the exclusion patterns
     * are applied as they are now, not as they were when the edit was made.
     */
    fun snippets(project: Project, currentFile: VirtualFile, currentLine: Int, excludedPatterns: String): List<ContextSnippet> {
        val index = ProjectFileIndex.getInstance(project)
        val lines = synchronized(edits) { edits.toList() }
            .filterNot { it.file == currentFile && it.line == currentLine }
            .filter { it.file.isValid && index.isInContent(it.file) && !Exclusions.isExcluded(it.file.name, excludedPatterns) }
        return if (lines.isEmpty()) emptyList() else listOf(ContextSnippet("recent edits", lines.joinToString("\n") { "${it.file.name}:${it.line + 1}: ${it.text}" }))
    }

    companion object {
        private const val MAX_EDITS = 6
        private const val MAX_LINE_CHARS = 200

        fun getInstance(): RecentEdits = service()
    }
}

/** Registered as an `editorFactoryDocumentListener`, so it sees every edit without starting anything at startup. */
internal class RecentEditsListener : DocumentListener {
    override fun documentChanged(event: DocumentEvent) = RecentEdits.getInstance().record(event.document, if (event.newLength > 0) event.offset + event.newLength - 1 else event.offset)
}
