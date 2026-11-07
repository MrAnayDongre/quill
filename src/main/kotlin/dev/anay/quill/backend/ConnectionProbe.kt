package dev.anay.quill.backend

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout

/** What the "Test connection" request found. Wording is left to the user interface. */
internal sealed interface ProbeResult {
    class Connected(val firstTokenMs: Long, val sample: String) : ProbeResult
    data object Empty : ProbeResult
    class TimedOut(val seconds: Long) : ProbeResult
    data object NotConfigured : ProbeResult
    class Failed(val message: String) : ProbeResult
}

/** One small real request, so that a wrong URL, model or key shows up before the user starts typing. */
internal object ConnectionProbe {
    private val request = CompletionRequest(FimPrompt("def add(a, b):\n    return ", "\n"), 16, emptyList())

    suspend fun run(backend: CompletionBackend, timeoutMs: Long = 10_000): ProbeResult {
        val started = System.nanoTime()
        return try {
            val text = withTimeout(timeoutMs) { backend.stream(request).firstOrNull() }
            val ms = (System.nanoTime() - started) / 1_000_000
            if (text == null) ProbeResult.Empty else ProbeResult.Connected(ms, text.trim().take(MAX_SAMPLE_CHARS))
        } catch (_: TimeoutCancellationException) {
            ProbeResult.TimedOut(timeoutMs / 1000)
        } catch (_: NotConfiguredException) {
            ProbeResult.NotConfigured
        } catch (e: BackendException) {
            ProbeResult.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            ProbeResult.Failed(e.javaClass.simpleName)
        }
    }

    private const val MAX_SAMPLE_CHARS = 40
}
