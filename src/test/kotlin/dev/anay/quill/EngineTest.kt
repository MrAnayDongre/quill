package dev.anay.quill

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class EngineTest {
    private val backend = FakeBackend()
    private val failures = CopyOnWriteArrayList<String>()
    private val stats = Stats()
    private var config = EngineConfig(maxTokens = 100, debounceMs = 0, requestTimeoutMs = 2_000)
    private val engine = CompletionEngine(backend, { config }, FailureReporter { failures += it }, stats, cacheSize = 2)
    private val shape = Shape(singleLine = false, stops = emptyList())

    private fun prompt(prefix: String) = FimPrompt(prefix, "")

    private suspend fun CompletionEngine.pieces(prompt: FimPrompt, shape: Shape, debounce: Boolean = true) =
        stream(prompt, shape, debounce).toList()

    private suspend fun CompletionEngine.text(prompt: FimPrompt, shape: Shape, debounce: Boolean = true): String? =
        pieces(prompt, shape, debounce).joinToString("").ifEmpty { null }

    @Test
    fun returnsCleanedBackendText() = runBlocking {
        backend.answer = { "```\nreturn 1\n```" }
        assertEquals("return 1\n", engine.text(prompt("a"), shape))
    }

    @Test
    fun emitsTextProgressivelyWhileTheModelIsStillAnswering() = runBlocking {
        backend.chunkSize = 4
        backend.answer = { "return a * b + c" }
        val pieces = engine.pieces(prompt("a"), shape)
        assertTrue(pieces.size > 1)
        assertEquals("return a * b + c", pieces.joinToString(""))
    }

    @Test
    fun readingStopsAtTheEndOfTheLineForASingleLineShape() = runBlocking {
        backend.chunkSize = 3
        backend.answer = { "a + b\nsecond line that should never be read" }
        assertEquals("a + b", engine.text(prompt("a"), Shape(true, emptyList())))
        assertTrue(backend.delivered.get() < backend.answer(CompletionRequest(prompt("a"), 1, emptyList())).length / 3)
    }

    @Test
    fun repeatedPromptIsServedFromTheCache() = runBlocking {
        engine.text(prompt("a"), shape)
        assertEquals("fake", engine.text(prompt("a"), shape))
        assertEquals(1, backend.requests.size)
    }

    @Test
    fun cacheEvictsTheLeastRecentlyUsedEntry() = runBlocking {
        listOf("a", "b", "a", "c").forEach { engine.text(prompt(it), shape) }
        engine.text(prompt("a"), shape)
        engine.text(prompt("b"), shape)
        assertEquals(listOf("a", "b", "c", "b"), backend.requests.map { it.prompt.prefix })
    }

    @Test
    fun cancelledRequestsAreNotCached() = runBlocking {
        backend.gate = CompletableDeferred()
        val call = async { engine.text(prompt("a"), shape) }
        while (backend.requests.isEmpty()) delay(5)
        call.cancel()
        backend.gate = null
        engine.text(prompt("a"), shape)
        assertEquals(2, backend.requests.size)
    }

    @Test
    fun cacheHitsSkipTheDebounceDelay() = runBlocking {
        engine.text(prompt("a"), shape)
        config = config.copy(debounceMs = 5_000)
        val started = System.nanoTime()
        engine.text(prompt("a"), shape)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test
    fun rapidRequestsCollapseIntoTheLastOne() = runBlocking {
        config = config.copy(debounceMs = 300)
        val first = async { engine.text(prompt("a"), shape) }
        delay(50)
        val second = async { engine.text(prompt("ab"), shape) }
        assertNull(first.await())
        assertEquals("fake", second.await())
        assertEquals(listOf("ab"), backend.requests.map { it.prompt.prefix })
    }

    @Test
    fun newRequestCancelsTheStaleOneInFlight() = runBlocking {
        backend.gate = CompletableDeferred()
        val stale = async { engine.text(prompt("a"), shape) }
        while (backend.requests.isEmpty()) delay(5)
        val fresh = async { engine.text(prompt("ab"), shape) }
        assertNull(stale.await())
        assertEquals(1, backend.cancelled.get())
        backend.gate!!.complete(Unit)
        assertEquals("fake", fresh.await())
    }

    @Test
    fun manualRequestSkipsTheDebounce() = runBlocking {
        config = config.copy(debounceMs = 5_000)
        val started = System.nanoTime()
        engine.text(prompt("a"), shape, debounce = false)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }

    @Test
    fun debounceIsShortAfterStructuralCharacters() {
        assertEquals(20L, CompletionEngine.debounceFor("foo.", 150))
        assertEquals(20L, CompletionEngine.debounceFor("foo(", 150))
        assertEquals(20L, CompletionEngine.debounceFor("a\n", 150))
        assertEquals(150L, CompletionEngine.debounceFor("foo", 150))
        assertEquals(10L, CompletionEngine.debounceFor("foo.", 10))
        assertEquals(150L, CompletionEngine.debounceFor("", 150))
    }

    @Test
    fun timeoutEndsTheStreamAndReportsOnce() = runBlocking {
        backend.gate = CompletableDeferred()
        config = config.copy(requestTimeoutMs = 100)
        assertNull(engine.text(prompt("a"), shape))
        assertNull(engine.text(prompt("b"), shape))
        assertEquals(1, failures.size)
        assertTrue(failures.single().contains("in time"))
        assertEquals(Stats.State.FAILING, stats.state)
    }

    @Test
    fun failuresAreReportedOnceUntilARequestSucceeds() = runBlocking {
        backend.failure = BackendException("The server answered HTTP 500")
        assertNull(engine.text(prompt("a"), shape))
        assertNull(engine.text(prompt("b"), shape))
        assertEquals(listOf("The server answered HTTP 500"), failures)
        backend.failure = null
        engine.text(prompt("c"), shape)
        assertEquals(Stats.State.OK, stats.state)
        backend.failure = BackendException("again")
        engine.text(prompt("d"), shape)
        assertEquals(2, failures.size)
    }

    @Test
    fun missingKeyIsAStateNotAFailure() = runBlocking {
        backend.failure = NotConfiguredException()
        assertNull(engine.text(prompt("a"), shape))
        assertTrue(failures.isEmpty())
        assertEquals(Stats.State.NOT_CONFIGURED, stats.state)
    }

    @Test
    fun timeToFirstTextIsRecorded() = runBlocking {
        engine.text(prompt("a"), shape)
        assertEquals(1, stats.count())
    }

    @Test
    fun singleLineShapeLimitsTokens() = runBlocking {
        engine.text(prompt("a"), Shape(singleLine = true, stops = emptyList()))
        engine.text(prompt("b"), shape)
        assertEquals(listOf(48, 100), backend.requests.map { it.maxTokens })
    }

    @Test
    fun aFailureDoesNotShrinkTheNextRequest() = runBlocking {
        backend.failure = BackendException("down")
        engine.text(prompt("a"), shape)
        backend.failure = null
        engine.text(prompt("b"), shape)
        assertEquals(100, backend.requests.last().maxTokens)
    }

    @Test
    fun aTimeoutShrinksTheNextRequest() = runBlocking {
        backend.gate = CompletableDeferred()
        config = config.copy(requestTimeoutMs = 1_600)
        engine.text(prompt("a"), shape)
        backend.gate = null
        engine.text(prompt("b"), shape)
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
