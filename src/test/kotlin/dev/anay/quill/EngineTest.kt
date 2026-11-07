package dev.anay.quill

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class EngineTest {
    private val backend = FakeBackend()
    private val failures = CopyOnWriteArrayList<String>()
    private var config = EngineConfig(maxTokens = 100, debounceMs = 0, requestTimeoutMs = 2_000)
    private val engine = CompletionEngine(backend, { config }, FailureReporter { failures += it }, cacheSize = 2)
    private val shape = Shape(singleLine = false, stops = emptyList())

    private fun prompt(prefix: String) = FimPrompt(prefix, "")

    @Test
    fun returnsCleanedBackendText() = runBlocking {
        backend.answer = { "```\nreturn 1\n```" }
        assertEquals("return 1\n", engine.complete(prompt("a"), shape))
    }

    @Test
    fun repeatedPromptIsServedFromTheCache() = runBlocking {
        engine.complete(prompt("a"), shape)
        engine.complete(prompt("a"), shape)
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun cacheEvictsTheLeastRecentlyUsedEntry() = runBlocking {
        listOf("a", "b", "a", "c").forEach { engine.complete(prompt(it), shape) }
        engine.complete(prompt("a"), shape)
        engine.complete(prompt("b"), shape)
        assertEquals(listOf("a", "b", "c", "b"), backend.requests.map { it.prompt.prefix })
    }

    @Test
    fun cacheHitsSkipTheDebounceDelay() = runBlocking {
        engine.complete(prompt("a"), shape)
        config = config.copy(debounceMs = 5_000)
        val started = System.nanoTime()
        engine.complete(prompt("a"), shape)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test
    fun rapidRequestsCollapseIntoTheLastOne() = runBlocking {
        config = config.copy(debounceMs = 300)
        val first = async { runCatching { engine.complete(prompt("a"), shape) } }
        delay(50)
        val second = async { engine.complete(prompt("ab"), shape) }
        assertTrue(first.await().exceptionOrNull() is CancellationException)
        assertEquals("fake", second.await())
        assertEquals(listOf("ab"), backend.requests.map { it.prompt.prefix })
    }

    @Test
    fun newRequestCancelsTheStaleOneInFlight() = runBlocking {
        backend.gate = CompletableDeferred()
        val stale = async { runCatching { engine.complete(prompt("a"), shape) } }
        while (backend.requests.isEmpty()) delay(5)
        val fresh = async { engine.complete(prompt("ab"), shape) }
        assertTrue(stale.await().exceptionOrNull() is CancellationException)
        assertEquals(1, backend.cancelled.get())
        backend.gate!!.complete(Unit)
        assertEquals("fake", fresh.await())
    }

    @Test
    fun manualRequestSkipsTheDebounce() = runBlocking {
        config = config.copy(debounceMs = 5_000)
        val started = System.nanoTime()
        engine.complete(prompt("a"), shape, debounce = false)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test
    fun timeoutReturnsNullAndReportsOnce() = runBlocking {
        backend.gate = CompletableDeferred()
        config = config.copy(requestTimeoutMs = 100)
        assertNull(engine.complete(prompt("a"), shape))
        assertNull(engine.complete(prompt("b"), shape))
        assertEquals(1, failures.size)
        assertTrue(failures.single().contains("in time"))
    }

    @Test
    fun failuresAreReportedOnceUntilARequestSucceeds() = runBlocking {
        backend.failure = BackendException("The server answered HTTP 500")
        assertNull(engine.complete(prompt("a"), shape))
        assertNull(engine.complete(prompt("b"), shape))
        assertEquals(listOf("The server answered HTTP 500"), failures)
        backend.failure = null
        engine.complete(prompt("c"), shape)
        backend.failure = BackendException("again")
        engine.complete(prompt("d"), shape)
        assertEquals(2, failures.size)
    }

    @Test
    fun singleLineShapeLimitsTokens() = runBlocking {
        engine.complete(prompt("a"), Shape(singleLine = true, stops = emptyList()))
        engine.complete(prompt("b"), shape)
        assertEquals(listOf(48, 100), backend.requests.map { it.maxTokens })
    }

    @Test
    fun aFailureDoesNotShrinkTheNextRequest() = runBlocking {
        backend.failure = BackendException("down")
        engine.complete(prompt("a"), shape)
        backend.failure = null
        engine.complete(prompt("b"), shape)
        assertEquals(100, backend.requests.last().maxTokens)
    }

    @Test
    fun aTimeoutShrinksTheNextRequest() = runBlocking {
        backend.gate = CompletableDeferred()
        config = config.copy(requestTimeoutMs = 1_600)
        engine.complete(prompt("a"), shape)
        backend.gate = null
        engine.complete(prompt("b"), shape)
        assertTrue(backend.requests.last().maxTokens < 100)
    }

    @Test
    fun slowResponsesShrinkTheTokenAllowance() {
        val budget = LatencyBudget()
        assertEquals(100, budget.cap(100, 1_000))
        budget.record(4_000)
        assertEquals(25, budget.cap(100, 1_000))
        assertEquals(16, budget.cap(20, 100))
        assertEquals(8, budget.cap(8, 100))
    }
}
