package dev.anay.quill.completion

import dev.anay.quill.backend.BackendException
import dev.anay.quill.backend.CompletionBackend
import dev.anay.quill.backend.CompletionRequest
import dev.anay.quill.backend.NotConfiguredException
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

internal data class EngineConfig(val maxTokens: Int, val debounceMs: Long, val requestTimeoutMs: Long)

/**
 * Turns a prompt into a stream of cleaned completion text: cache lookup, debounce, one request in flight at a
 * time, a timeout, and output cleanup. The first characters are emitted as soon as they are safe to show, and
 * reading stops the moment the answer is complete. Cancellation always propagates to the HTTP request.
 */
internal class CompletionEngine(
    private val backend: CompletionBackend,
    private val config: () -> EngineConfig,
    private val reporter: FailureReporter,
    private val stats: CompletionStats = CompletionStats(),
    private val cacheSize: Int = 64,
) {
    // An answer that was never checked must not be served to a request that asks for checking.
    private data class Key(val prompt: FimPrompt, val shape: Shape, val verified: Boolean)

    // ponytail: one process wide LRU keyed by the whole prompt; add a time to live if servers change answers over time.
    private val cache = object : LinkedHashMap<Key, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>) = size > cacheSize
    }
    private val inFlight = AtomicReference<Job?>()
    private val latency = LatencyBudget()

    fun stream(prompt: FimPrompt, shape: Shape, debounce: Boolean = true, verifier: Verifier? = null): Flow<String> = channelFlow {
        val key = Key(prompt, shape, verifier != null)
        val hit = synchronized(cache) { cache[key] }
        if (hit != null) {
            send(hit)
            return@channelFlow
        }
        val settings = config()
        val job = launch { run(key, settings, if (debounce) debounceFor(prompt.prefix, settings.debounceMs) else 0, verifier) }
        inFlight.getAndSet(job)?.cancel()
        job.invokeOnCompletion { inFlight.compareAndSet(job, null) }
    }

    private suspend fun ProducerScope<String>.run(key: Key, settings: EngineConfig, debounceMs: Long, verifier: Verifier?) {
        delay(debounceMs)
        val wanted = if (key.shape.singleLine) minOf(settings.maxTokens, SINGLE_LINE_TOKENS) else settings.maxTokens
        val tokens = latency.cap(wanted, LATENCY_BUDGET_MS)
        val whole = StringBuilder()
        val started = System.nanoTime()
        var firstMs = -1L
        suspend fun show(piece: String) {
            if (piece.isEmpty()) return
            if (firstMs < 0) firstMs = (System.nanoTime() - started) / 1_000_000
            whole.append(piece)
            send(piece)
        }

        // One pass over the model's answer. With a verifier, a line is shown only once it checks out; the first
        // line that does not stops the pass and its problems come back so the caller can ask again.
        suspend fun pass(prompt: FimPrompt): Problems? {
            val cleaner = StreamCleaner(prompt.suffix, key.shape)
            val pending = StringBuilder()
            var failure: Problems? = null

            suspend fun release(final: Boolean) {
                while (failure == null) {
                    val newline = pending.indexOf("\n")
                    val line = when {
                        newline >= 0 -> pending.substring(0, newline + 1)
                        final && pending.isNotEmpty() -> pending.toString()
                        else -> return
                    }
                    failure = verifier?.problems(whole.toString() + line)
                    if (failure == null) { pending.delete(0, line.length); show(line) }
                }
            }

            suspend fun accept(piece: String) {
                if (piece.isEmpty()) return
                if (verifier == null) return show(piece)
                pending.append(piece)
                release(final = false)
            }
            backend.stream(CompletionRequest(prompt, tokens, key.shape.stops))
                .transformWhile { delta -> emit(cleaner.feed(delta)); !cleaner.done && failure == null }
                .collect { accept(it) }
            accept(cleaner.finish())
            release(final = true)
            return failure
        }

        try {
            withTimeout(settings.requestTimeoutMs) {
                var problems = pass(key.prompt)
                var repairs = 0
                while (problems != null) {
                    stats.caught(problems.names.size)
                    if (repairs++ == MAX_REPAIRS) break
                    problems = pass(FimPrompt(problems.hint + key.prompt.prefix + whole, key.prompt.suffix))
                    if (problems == null) stats.repaired()
                }
            }
            latency.record((System.nanoTime() - started) / 1_000_000)
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

    private fun fail(message: String) {
        stats.markFailing()
        reporter.failure(message)
    }

    companion object {
        private const val SINGLE_LINE_TOKENS = 48
        private const val MAX_REPAIRS = 1
        // ponytail: fixed budget; promote to a setting if users need to tune it.
        private const val LATENCY_BUDGET_MS = 1_500L
        private const val FAST_DEBOUNCE_MS = 20L
        private const val TRIGGERS = ".(,{[=:>\n"

        /** Right after a structural character the next word is the interesting one, so there is no point waiting. */
        internal fun debounceFor(prefix: String, baseMs: Long): Long =
            if (prefix.lastOrNull()?.let { it in TRIGGERS } == true) minOf(baseMs, FAST_DEBOUNCE_MS) else baseMs
    }
}
