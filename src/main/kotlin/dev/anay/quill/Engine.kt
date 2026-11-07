package dev.anay.quill

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference

data class EngineConfig(val maxTokens: Int, val debounceMs: Long, val requestTimeoutMs: Long)

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
 * Turns a prompt into a stream of cleaned completion text: cache lookup, debounce, one request in flight at a
 * time, a timeout, and output cleanup. The first characters are emitted as soon as they are safe to show, and
 * reading stops the moment the answer is complete. Cancellation always propagates to the HTTP request.
 */
class CompletionEngine(
    private val backend: CompletionBackend,
    private val config: () -> EngineConfig,
    private val reporter: FailureReporter,
    private val stats: Stats = Stats(),
    private val cacheSize: Int = 64,
) {
    private data class Key(val prompt: FimPrompt, val shape: Shape)

    // ponytail: one process wide LRU keyed by the whole prompt; add a time to live if servers change answers over time.
    private val cache = object : LinkedHashMap<Key, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>) = size > cacheSize
    }
    private val inFlight = AtomicReference<Job?>()
    private val latency = LatencyBudget()

    fun stream(prompt: FimPrompt, shape: Shape, debounce: Boolean = true): Flow<String> = channelFlow {
        val key = Key(prompt, shape)
        val hit = synchronized(cache) { cache[key] }
        if (hit != null) {
            send(hit)
            return@channelFlow
        }
        val settings = config()
        val job = launch { run(key, settings, if (debounce) debounceFor(prompt.prefix, settings.debounceMs) else 0) }
        inFlight.getAndSet(job)?.cancel()
        job.invokeOnCompletion { inFlight.compareAndSet(job, null) }
    }

    private suspend fun ProducerScope<String>.run(key: Key, settings: EngineConfig, debounceMs: Long) {
        delay(debounceMs)
        val wanted = if (key.shape.singleLine) minOf(settings.maxTokens, SINGLE_LINE_TOKENS) else settings.maxTokens
        val request = CompletionRequest(key.prompt, latency.cap(wanted, LATENCY_BUDGET_MS), key.shape.stops)
        val cleaner = StreamCleaner(key.prompt.suffix, key.shape)
        val whole = StringBuilder()
        val started = System.nanoTime()
        var firstMs = -1L
        try {
            withTimeout(settings.requestTimeoutMs) {
                backend.stream(request)
                    .transformWhile { delta -> emit(cleaner.feed(delta)); !cleaner.done }
                    .collect { piece -> show(piece, whole, started)?.let { if (firstMs < 0) firstMs = it } }
            }
            show(cleaner.finish(), whole, started)?.let { if (firstMs < 0) firstMs = it }
            val totalMs = (System.nanoTime() - started) / 1_000_000
            latency.record(totalMs)
            if (firstMs >= 0) stats.recordFirstText(firstMs) else stats.markOk()
            reporter.success()
            if (whole.isNotEmpty()) synchronized(cache) { cache[key] = whole.toString() }
        } catch (e: CancellationException) {
            // withTimeout throws a CancellationException subclass; only a timeout of our own is a failure.
            if (e !is TimeoutCancellationException) throw e
            latency.record(settings.requestTimeoutMs)
            fail("The completion server did not answer in time")
        } catch (_: NotConfiguredException) {
            stats.markNotConfigured()
        } catch (e: BackendException) {
            fail(e.message ?: "The completion request failed")
        } catch (e: RuntimeException) {
            fail("Unexpected error: ${e.javaClass.simpleName}")
        }
    }

    /** Sends [piece] to the caller; returns the milliseconds since [started] if it was text. */
    private suspend fun ProducerScope<String>.show(piece: String, whole: StringBuilder, started: Long): Long? {
        if (piece.isEmpty()) return null
        whole.append(piece)
        send(piece)
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun fail(message: String) {
        stats.markFailing()
        reporter.failure(message)
    }

    companion object {
        private const val SINGLE_LINE_TOKENS = 48
        // ponytail: fixed budget; promote to a setting if users need to tune it.
        private const val LATENCY_BUDGET_MS = 1_500L
        private const val FAST_DEBOUNCE_MS = 20L
        private const val TRIGGERS = ".(,{[=:>\n"

        /** Right after a structural character the next word is the interesting one, so there is no point waiting. */
        internal fun debounceFor(prefix: String, baseMs: Long): Long =
            if (prefix.lastOrNull()?.let { it in TRIGGERS } == true) minOf(baseMs, FAST_DEBOUNCE_MS) else baseMs
    }
}
