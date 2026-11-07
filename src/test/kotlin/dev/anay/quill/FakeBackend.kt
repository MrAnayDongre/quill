package dev.anay.quill

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic backend: records requests, can hold an answer back behind [gate], and can fail on demand. */
class FakeBackend(var answer: (CompletionRequest) -> String = { "fake" }) : CompletionBackend {
    val requests = CopyOnWriteArrayList<CompletionRequest>()
    val cancelled = AtomicInteger()

    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    @Volatile
    var failure: Exception? = null

    override suspend fun complete(request: CompletionRequest): String {
        requests += request
        failure?.let { throw it }
        try {
            gate?.await()
        } catch (e: CancellationException) {
            cancelled.incrementAndGet()
            throw e
        }
        return answer(request)
    }
}
