package dev.anay.quill.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProbeTest {
    @Test
    fun summarisesASuccessfulRequest() = runBlocking {
        val result = ConnectionProbe.run(FakeBackend(answer = { " a + b " })) as ProbeResult.Connected
        assertEquals("a + b", result.sample)
        assertTrue(result.firstTokenMs >= 0)
    }

    @Test
    fun keepsTheSafeFailureMessage() = runBlocking {
        val backend = FakeBackend().apply { failure = BackendException("The server answered HTTP 401") }
        assertEquals("The server answered HTTP 401", (ConnectionProbe.run(backend) as ProbeResult.Failed).message)
    }

    @Test
    fun missingKeyIsItsOwnResult() = runBlocking {
        val backend = FakeBackend().apply { failure = NotConfiguredException() }
        assertEquals(ProbeResult.NotConfigured, ConnectionProbe.run(backend))
    }

    @Test
    fun emptyAnswerIsReported() = runBlocking {
        assertEquals(ProbeResult.Empty, ConnectionProbe.run(FakeBackend(answer = { "" })))
    }

    @Test
    fun givesUpAfterTheTimeout() = runBlocking {
        val backend = FakeBackend().apply { gate = CompletableDeferred() }
        assertTrue(ConnectionProbe.run(backend, timeoutMs = 50) is ProbeResult.TimedOut)
    }
}
