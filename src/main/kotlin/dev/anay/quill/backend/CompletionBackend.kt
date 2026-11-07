package dev.anay.quill.backend

import dev.anay.quill.completion.FimPrompt
import kotlinx.coroutines.flow.Flow

data class CompletionRequest(val prompt: FimPrompt, val maxTokens: Int, val stop: List<String>)

/** A failure whose message is safe to show: it never carries request or response bodies. */
class BackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The endpoint needs an API key and none is stored; nothing has been sent. */
class NotConfiguredException : Exception("Add an API key in Settings | Tools | Quill")

interface CompletionBackend {
    /** Emits the model's text as it is generated. Cancelling the collector aborts the request. */
    fun stream(request: CompletionRequest): Flow<String>
}
