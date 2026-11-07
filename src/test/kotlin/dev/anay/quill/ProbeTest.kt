package dev.anay.quill

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTest {
    @Test
    fun summarisesASuccessfulRequest() = runBlocking {
        val message = Probe.run(FakeBackend(answer = { "a + b" }))
        assertTrue(message, Regex("""Connected · first token \d+ ms · "a \+ b"""").matches(message))
    }

    @Test
    fun showsTheSafeFailureMessage() = runBlocking {
        val backend = FakeBackend().apply { failure = BackendException("The server answered HTTP 401") }
        assertEquals("The server answered HTTP 401", Probe.run(backend))
    }

    @Test
    fun missingKeyIsExplained() = runBlocking {
        val backend = FakeBackend().apply { failure = NotConfiguredException() }
        assertTrue(Probe.run(backend).startsWith("Add an API key"))
    }

    @Test
    fun givesUpAfterTheTimeout() = runBlocking {
        val backend = FakeBackend().apply { gate = CompletableDeferred() }
        assertEquals("No answer within 0 seconds", Probe.run(backend, timeoutMs = 50))
    }
}
