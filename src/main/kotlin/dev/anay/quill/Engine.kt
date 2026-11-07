package dev.anay.quill

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

data class EngineConfig(
    val maxTokens: Int,
    val debounceMs: Long,
    val requestTimeoutMs: Long,
)

/** Reports a failure through [sink] once, and stays quiet until a request succeeds again. */
class FailureReporter(private val sink: (String) -> Unit) {
    private var reported = false

    @Synchronized
    fun failure(message: String) {
        if (reported) return
        reported = true
        sink(message)
    }

    @Synchronized
    fun success() {
        reported = false
    }
}

/**
 * Shrinks the token allowance when the server is slower than the latency budget, so suggestions
 * keep arriving while the user is still looking at the line.
 */
class LatencyBudget {
    private var averageMs = 0.0

    @Synchronized
    fun record(ms: Long) {
        averageMs = if (averageMs == 0.0) ms.toDouble() else averageMs + SMOOTHING * (ms - averageMs)
    }

    @Synchronized
    fun cap(maxTokens: Int, budgetMs: Long): Int {
        if (averageMs <= budgetMs) return maxTokens
        return (maxTokens * budgetMs / averageMs).toInt().coerceIn(minOf(MIN_TOKENS, maxTokens), maxTokens)
    }

    private companion object {
        const val SMOOTHING = 0.3
        const val MIN_TOKENS = 16
    }
}

/**
 * Turns a prompt into cleaned completion text: cache lookup, debounce, one request in flight at a time,
 * a timeout, and output cleanup. Returns null for "nothing to show"; cancellation always propagates.
 */
class CompletionEngine(
    private val backend: CompletionBackend,
    private val config: () -> EngineConfig,
    private val reporter: FailureReporter,
    private val cacheSize: Int = 64,
) {
    private data class Key(val prompt: FimPrompt, val shape: Shape)

    // ponytail: one process wide LRU keyed by the whole prompt; add a time to live if servers change answers over time.
    private val cache = object : LinkedHashMap<Key, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>) = size > cacheSize
    }
    private val inFlight = AtomicReference<Job?>()
    private val latency = LatencyBudget()

    suspend fun complete(prompt: FimPrompt, shape: Shape, debounce: Boolean = true): String? {
        val key = Key(prompt, shape)
        synchronized(cache) { cache[key] }?.let { return it }
        val settings = config()
        return coroutineScope {
            val job = async { fetch(prompt, shape, settings, debounce) }
            inFlight.getAndSet(job)?.cancel()
            try {
                job.await()
            } finally {
                inFlight.compareAndSet(job, null)
            }
        }?.also { synchronized(cache) { cache[key] = it } }
    }

    private suspend fun fetch(prompt: FimPrompt, shape: Shape, settings: EngineConfig, debounce: Boolean): String? {
        if (debounce) delay(settings.debounceMs)
        val wanted = if (shape.singleLine) minOf(settings.maxTokens, SINGLE_LINE_TOKENS) else settings.maxTokens
        val tokens = latency.cap(wanted, LATENCY_BUDGET_MS)
        val started = System.nanoTime()
        return try {
            val raw = withTimeout(settings.requestTimeoutMs) { backend.complete(CompletionRequest(prompt, tokens, shape.stops)) }
            latency.record((System.nanoTime() - started) / 1_000_000)
            reporter.success()
            Cleanup.clean(raw, prompt.suffix, shape).ifEmpty { null }
        } catch (e: CancellationException) {
            // withTimeout throws a CancellationException subclass; only a timeout of our own is a failure.
            if (e !is TimeoutCancellationException) throw e
            latency.record(settings.requestTimeoutMs)
            fail("The completion server did not answer in time")
        } catch (e: BackendException) {
            fail(e.message ?: "The completion request failed")
        } catch (e: RuntimeException) {
            fail("Unexpected error: ${e.javaClass.simpleName}")
        }
    }

    private fun fail(message: String): String? {
        reporter.failure(message)
        return null
    }

    private companion object {
        const val SINGLE_LINE_TOKENS = 48
        // ponytail: fixed budget; promote to a setting if users need to tune it.
        const val LATENCY_BUDGET_MS = 1_500L
    }
}
