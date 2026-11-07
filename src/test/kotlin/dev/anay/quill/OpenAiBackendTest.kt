package dev.anay.quill

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OpenAiBackendTest {
    private lateinit var server: HttpServer
    private val body = AtomicReference<String>()
    private val auth = AtomicReference<String?>()
    private val release = CountDownLatch(1)
    private var handler: (HttpExchange) -> Unit = { reply(it, 200, """{"choices":[{"text":"hello"}]}""") }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/v1") { exchange ->
            body.set(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
            auth.set(exchange.requestHeaders.getFirst("Authorization"))
            handler(exchange)
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    private fun reply(exchange: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun backend(key: String? = null, timeoutMs: Long = 5_000, path: String = "completions") = OpenAiBackend({
        Endpoint("http://localhost:${server.address.port}/v1/$path", "m", key, Duration.ofMillis(timeoutMs))
    })

    private val request = CompletionRequest(FimPrompt("before", "after"), 64, listOf("\n\n\n", "a", "b", "c", "d"))

    @Test
    fun sendsAFillInTheMiddleRequestAndParsesTheText() = runBlocking {
        assertEquals("hello", backend().use { it.complete(request) })
        val json = JsonParser.parseString(body.get()).asJsonObject
        assertEquals("m", json["model"].asString)
        assertEquals("before", json["prompt"].asString)
        assertEquals("after", json["suffix"].asString)
        assertEquals(64, json["max_tokens"].asInt)
        assertEquals(4, json["stop"].asJsonArray.size())
        assertNull(auth.get())
    }

    @Test
    fun chatEndpointGetsAMessageWithACursorMarker() = runBlocking {
        handler = { reply(it, 200, """{"choices":[{"message":{"role":"assistant","content":"a + b"}}]}""") }
        assertEquals("a + b", backend(path = "chat/completions").use { it.complete(request) })
        val json = JsonParser.parseString(body.get()).asJsonObject
        assertFalse(json.has("prompt"))
        assertFalse(json.has("suffix"))
        val messages = json["messages"].asJsonArray
        assertEquals("before<CURSOR>after", messages[1].asJsonObject["content"].asString)
        assertEquals("system", messages[0].asJsonObject["role"].asString)
    }

    @Test
    fun sendsTheKeyAsABearerToken() = runBlocking {
        backend(key = "secret").use { it.complete(request) }
        assertEquals("Bearer secret", auth.get())
    }

    @Test
    fun errorStatusDoesNotLeakTheBody() = runBlocking {
        handler = { reply(it, 401, "invalid key sk-123") }
        val failure = runCatching { backend().use { it.complete(request) } }.exceptionOrNull()
        assertTrue(failure is BackendException)
        assertEquals("The server answered HTTP 401", failure!!.message)
    }

    @Test
    fun malformedResponseIsAFailure() = runBlocking {
        handler = { reply(it, 200, """{"choices":[]}""") }
        assertTrue(runCatching { backend().use { it.complete(request) } }.exceptionOrNull() is BackendException)
    }

    @Test
    fun oversizedResponseIsRejected() = runBlocking {
        handler = { reply(it, 200, "x".repeat((1 shl 20) + 10)) }
        val failure = runCatching { backend().use { it.complete(request) } }.exceptionOrNull()
        assertEquals("The server response is too large", failure?.message)
    }

    @Test
    fun slowServerHitsTheRequestTimeout() = runBlocking {
        handler = { release.await(10, TimeUnit.SECONDS) }
        val failure = runCatching { backend(timeoutMs = 200).use { it.complete(request) } }.exceptionOrNull()
        assertEquals("The completion server did not answer in time", failure?.message)
    }

    @Test
    fun cancellingTheCallerAbortsTheExchange() = runBlocking {
        handler = { release.await(10, TimeUnit.SECONDS) }
        backend().use { backend ->
            val call = async { backend.complete(request) }
            while (body.get() == null) delay(5)
            val started = System.nanoTime()
            call.cancel()
            assertTrue(runCatching { call.await() }.exceptionOrNull() is CancellationException)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        }
    }

    @Test
    fun keyIsNeverSentOverPlainHttpToARemoteHost() {
        try {
            Endpoint("http://example.com/v1/completions", "m", "secret", Duration.ofSeconds(1))
            fail("expected a refusal")
        } catch (e: BackendException) {
            assertFalse(e.message!!.contains("secret"))
        }
        Endpoint("https://example.com/v1/completions", "m", "secret", Duration.ofSeconds(1))
        Endpoint("http://localhost:1/v1/completions", "m", "secret", Duration.ofSeconds(1))
    }

    @Test
    fun endpointValidation() {
        assertNull(EndpointPolicy.validate("https://api.example.com/v1/completions"))
        assertEquals("The endpoint must start with http:// or https://", EndpointPolicy.validate("file:///etc/passwd"))
        assertEquals("The endpoint has no host", EndpointPolicy.validate("http:///x"))
        assertEquals("The endpoint is not a valid URL", EndpointPolicy.validate("not a url"))
    }
}
