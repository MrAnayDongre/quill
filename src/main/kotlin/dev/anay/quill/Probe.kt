package dev.anay.quill

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout

/** The "Test connection" check: one small real request, summarised in a line that is safe to show. */
object Probe {
    private val request = CompletionRequest(FimPrompt("def add(a, b):\n    return ", "\n"), 16, emptyList())

    suspend fun run(backend: CompletionBackend, timeoutMs: Long = 10_000): String {
        val started = System.nanoTime()
        return try {
            val text = withTimeout(timeoutMs) { backend.stream(request).firstOrNull() }
            val ms = (System.nanoTime() - started) / 1_000_000
            if (text == null) "Connected, but the model returned nothing" else "Connected · first token $ms ms · \"${text.trim().take(40)}\""
        } catch (e: TimeoutCancellationException) {
            "No answer within ${timeoutMs / 1000} seconds"
        } catch (e: NotConfiguredException) {
            e.message!!
        } catch (e: BackendException) {
            e.message ?: "The request failed"
        }
    }
}
