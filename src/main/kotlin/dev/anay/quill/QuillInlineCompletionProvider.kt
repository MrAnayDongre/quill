package dev.anay.quill

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.invoke
import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange

/** Stateless: everything that varies lives in [QuillService], so the provider can be created and dropped freely. */
class QuillInlineCompletionProvider : InlineCompletionProvider {
    override val id = InlineCompletionProviderID("dev.anay.quill")

    // Runs on the EDT for every keystroke, so it only reads settings and a file name.
    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val state = QuillSettings.getInstance().state
        if (!state.enabled) return false
        val editor = editorOf(event) ?: return false
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
        return !Exclusions.isExcluded(file.name, state.excludedPatterns)
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val state = QuillSettings.getInstance().state
        val input = readAction { buildInput(request, state.excludedPatterns) } ?: return InlineCompletionSuggestion.Empty
        val text = QuillService.getInstance().engine.complete(input.prompt, input.shape, debounce = input.debounce)
            ?: return InlineCompletionSuggestion.Empty
        return InlineCompletionSuggestion { variant { emit(InlineCompletionGrayTextElement(text)) } }
    }

    private class Input(val prompt: FimPrompt, val shape: Shape, val debounce: Boolean)

    private fun buildInput(request: InlineCompletionRequest, excludedPatterns: String): Input? {
        val file = request.file.virtualFile ?: return null
        val document = request.document
        val caret = request.endOffset
        val windowChars = WINDOW_TOKENS * PromptBuilder.CHARS_PER_TOKEN
        val before = document.getText(TextRange(maxOf(0, caret - windowChars), caret))
        val after = document.getText(TextRange(caret, minOf(document.textLength, caret + windowChars)))
        val language = request.file.language
        val commentPrefix = LanguageCommenters.INSTANCE.forLanguage(language)?.lineCommentPrefix ?: "//"
        val context = ContextCollector.collect(request.file.project, file, excludedPatterns)
        val prompt = PromptBuilder.build(before, after, context, commentPrefix, WINDOW_TOKENS)
        val shape = Shape(singleLine = after.substringBefore('\n').isNotBlank(), stops = Cleanup.stopsFor(language.id))
        // A manual request is an explicit ask, so it skips the typing debounce.
        return Input(prompt, shape, debounce = request.event !is InlineCompletionEvent.DirectCall)
    }

    private fun editorOf(event: InlineCompletionEvent): Editor? = when (event) {
        is InlineCompletionEvent.DocumentChange -> event.editor
        is InlineCompletionEvent.DirectCall -> event.editor
        else -> null
    }

    private companion object {
        // ponytail: fixed prompt window; promote to a setting for models with small contexts.
        const val WINDOW_TOKENS = 1500
    }
}
