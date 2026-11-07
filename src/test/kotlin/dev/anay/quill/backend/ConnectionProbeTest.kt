package dev.anay.quill.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProbeTest {
    @Test
    fun summarisesASuccessfulRequest() = runBlocking {
        val message = ConnectionProbe.run(FakeBackend(answer = { "a + b" }))
        assertTrue(message, Regex("""Connected · first token \d+ ms · "a \+ b"""").matches(message))
    }

    @Test
    fun showsTheSafeFailureMessage() = runBlocking {
        val backend = FakeBackend().apply { failure = BackendException("The server answered HTTP 401") }
        assertEquals("The server answered HTTP 401", ConnectionProbe.run(backend))
    }

    @Test
    fun missingKeyIsExplained() = runBlocking {
        val backend = FakeBackend().apply { failure = NotConfiguredException() }
        assertTrue(ConnectionProbe.run(backend).startsWith("Add an API key"))
    }

    @Test
    fun givesUpAfterTheTimeout() = runBlocking {
        val backend = FakeBackend().apply { gate = CompletableDeferred() }
        assertEquals("No answer within 0 seconds", ConnectionProbe.run(backend, timeoutMs = 50))
    }
}
