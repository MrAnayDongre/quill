package dev.anay.quill.backend

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.anay.quill.completion.FimPrompt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
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
    private val method = AtomicReference<String>()
    private val release = CountDownLatch(1)
    private val clientGone = CountDownLatch(1)
    private var handler: (HttpExchange) -> Unit = { sse(it, delta("hello")) }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/v1") { exchange ->
            method.set(exchange.requestMethod)
            body.set(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
            auth.set(exchange.requestHeaders.getFirst("Authorization"))
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    @After
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    private fun delta(text: String) = """{"choices":[{"delta":{"content":${JsonPrimitive(text)}},"text":${JsonPrimitive(text)}}]}"""

    /** Sends each payload as a server sent event, flushing after every one, then ends the stream. */
    private fun sse(exchange: HttpExchange, vararg payloads: String) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        payloads.forEach { exchange.responseBody.write("data: $it\n\n".toByteArray()).also { exchange.responseBody.flush() } }
        exchange.responseBody.write("data: [DONE]\n\n".toByteArray())
    }

    private fun plain(exchange: HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun backend(key: String? = null, timeoutMs: Long = 5_000, path: String = "completions") = OpenAiBackend({
        Endpoint("http://localhost:${server.address.port}/v1/$path", "m", key, Duration.ofMillis(timeoutMs))
    })

    private val request = CompletionRequest(FimPrompt("before", "after"), 64, listOf("\n\n\n", "a", "b", "c", "d"))

    private suspend fun OpenAiBackend.text(): String = stream(request).toList().joinToString("")

    @Test
    fun streamsDeltasInOrderAndSendsAFillInTheMiddleRequest() = runBlocking {
        handler = { sse(it, delta("hel"), delta("lo")) }
        val pieces = backend().use { it.stream(request).toList() }
        assertEquals(listOf("hel", "lo"), pieces)
        val json = JsonParser.parseString(body.get()).asJsonObject
        assertEquals("m", json["model"].asString)
        assertEquals("before", json["prompt"].asString)
        assertEquals("after", json["suffix"].asString)
        assertEquals(64, json["max_tokens"].asInt)
        assertTrue(json["stream"].asBoolean)
        assertEquals(4, json["stop"].asJsonArray.size())
        assertNull(auth.get())
    }

    @Test
    fun chatEndpointGetsAMessageWithACursorMarker() = runBlocking {
        assertEquals("hello", backend(path = "chat/completions").use { it.text() })
        val json = JsonParser.parseString(body.get()).asJsonObject
        assertFalse(json.has("prompt"))
        assertFalse(json.has("suffix"))
        val messages = json["messages"].asJsonArray
        assertEquals("before<CURSOR>after", messages[1].asJsonObject["content"].asString)
        assertEquals("system", messages[0].asJsonObject["role"].asString)
    }

    @Test
    fun roleOnlyAndEmptyFramesAreSkipped() = runBlocking {
        handler = { sse(it, """{"choices":[{"delta":{"role":"assistant","content":""}}]}""", """{"choices":[]}""", delta("x")) }
        assertEquals(listOf("x"), backend(path = "chat/completions").use { it.stream(request).toList() })
    }

    @Test
    fun sendsTheKeyAsABearerToken() = runBlocking {
        backend(key = "secret").use { it.text() }
        assertEquals("Bearer secret", auth.get())
    }

    @Test
    fun errorStatusDoesNotLeakTheBody() = runBlocking {
        handler = { plain(it, 401, "invalid key sk-123") }
        val failure = runCatching { backend().use { it.text() } }.exceptionOrNull()
        assertTrue(failure is BackendException)
        assertEquals("The server answered HTTP 401", failure!!.message)
    }

    @Test
    fun malformedFrameIsAFailure() = runBlocking {
        handler = { sse(it, "{not json") }
        assertTrue(runCatching { backend().use { it.text() } }.exceptionOrNull() is BackendException)
    }

    @Test
    fun oversizedResponseIsRejected() = runBlocking {
        handler = { sse(it, *Array(40) { delta("x".repeat(30_000)) }) }
        val failure = runCatching { backend().use { it.text() } }.exceptionOrNull()
        assertEquals("The server response is too large", failure?.message)
    }

    @Test
    fun slowServerHitsTheRequestTimeout() = runBlocking {
        handler = { release.await(10, TimeUnit.SECONDS) }
        val failure = runCatching { backend(timeoutMs = 200).use { it.text() } }.exceptionOrNull()
        assertEquals("The completion server did not answer in time", failure?.message)
    }

    @Test
    fun stoppingEarlyClosesTheConnection() = runBlocking {
        handler = { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try {
                while (true) {
                    exchange.responseBody.write("data: ${delta("tick")}\n\n".toByteArray())
                    exchange.responseBody.flush()
                    Thread.sleep(20)
                }
            } catch (_: IOException) {
                clientGone.countDown()
            }
        }
        val first = backend().use { it.stream(request).first() }
        assertEquals("tick", first)
        assertTrue("the server should see the client leave", clientGone.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun warmUpOpensAConnectionWithoutCredentials() = runBlocking {
        handler = { plain(it, 404, "") }
        backend(key = "secret").use { it.warmUp() }
        assertEquals("HEAD", method.get())
        assertNull(auth.get())
    }

    @Test
    fun warmUpOnAMisconfiguredEndpointDoesNotThrow() = runBlocking {
        OpenAiBackend({ Endpoint("http://example.com/v1/completions", "m", "secret", Duration.ofSeconds(1)) }).use { it.warmUp() }
    }

    @Test
    fun missingKeyStopsTheStreamBeforeAnythingIsSent() = runBlocking {
        val failure = runCatching { OpenAiBackend({ throw NotConfiguredException() }).use { it.stream(request).toList() } }.exceptionOrNull()
        assertTrue(failure is NotConfiguredException)
        assertNull(method.get())
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
