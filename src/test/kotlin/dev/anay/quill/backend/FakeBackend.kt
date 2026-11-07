package dev.anay.quill.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic backend: records requests, answers in chunks of [chunkSize] characters, can hold an answer back
 * behind [gate], and can fail on demand.
 */
class FakeBackend(var chunkSize: Int = Int.MAX_VALUE, var answer: (CompletionRequest) -> String = { "fake" }) : CompletionBackend {
    val requests = CopyOnWriteArrayList<CompletionRequest>()
    val cancelled = AtomicInteger()
    val delivered = AtomicInteger()

    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    @Volatile
    var failure: Exception? = null

    override fun stream(request: CompletionRequest): Flow<String> = flow {
        requests += request
        failure?.let { throw it }
        try {
            gate?.await()
            for (piece in answer(request).chunked(chunkSize)) {
                emit(piece)
                delivered.incrementAndGet()
                delay(1)
            }
        } catch (e: CancellationException) {
            cancelled.incrementAndGet()
            throw e
        }
    }
}
