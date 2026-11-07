package dev.anay.quill

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.future.await
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.Flow as JdkFlow

data class CompletionRequest(val prompt: FimPrompt, val maxTokens: Int, val stop: List<String>)

/** A failure whose message is safe to show: it never carries request or response bodies. */
class BackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The endpoint needs an API key and none is stored; nothing has been sent. */
class NotConfiguredException : Exception("Add an API key in Settings | Tools | Quill")

interface CompletionBackend {
    /** Emits the model's text as it is generated. Cancelling the collector aborts the request. */
    fun stream(request: CompletionRequest): Flow<String>
}

data class Endpoint(val url: String, val model: String, val apiKey: String?, val timeout: Duration) {
    val isChat: Boolean get() = URI(url).path.orEmpty().trimEnd('/').endsWith("/chat/completions")

    init {
        EndpointPolicy.validate(url)?.let { throw BackendException(it) }
        if (!apiKey.isNullOrEmpty() && !EndpointPolicy.isLoopback(URI(url).host) && URI(url).scheme != "https") {
            throw BackendException("The API key is only sent over https or to a local server")
        }
    }
}

object EndpointPolicy {
    /** Returns a message describing why [url] cannot be used, or null if it is fine. */
    fun validate(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return "The endpoint is not a valid URL"
        if (uri.scheme != "http" && uri.scheme != "https") return "The endpoint must start with http:// or https://"
        if (uri.host.isNullOrEmpty()) return "The endpoint has no host"
        return null
    }

    fun isLoopback(host: String?): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
}

/**
 * Streams from any server that implements the OpenAI API. An endpoint ending in `/chat/completions` gets a chat
 * request with a cursor marker; any other endpoint gets a completions request with the `suffix` field for fill in
 * the middle.
 */
class OpenAiBackend(private val endpoint: suspend () -> Endpoint) : CompletionBackend, AutoCloseable {

    // Building a client loads the TLS stack, so it happens on first use and never on the UI thread.
    private val clientHolder = lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build() }
    private val client get() = clientHolder.value

    override fun stream(request: CompletionRequest): Flow<String> = callbackFlow {
        val target = endpoint()
        val httpRequest = HttpRequest.newBuilder(URI(target.url))
            .timeout(target.timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .apply { target.apiKey?.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(body(target, request)))
            .build()
        val lines = SseLines(target.isChat, { trySend(it) }, { close(it) })
        val future = client.sendAsync(httpRequest) { info ->
            if (info.statusCode() in 200..299) {
                HttpResponse.BodySubscribers.fromLineSubscriber(lines)
            } else {
                close(BackendException("The server answered HTTP ${info.statusCode()}"))
                HttpResponse.BodySubscribers.discarding()
            }
        }
        future.whenComplete { _, error -> if (error != null) close(describe(error)) }
        awaitClose { future.cancel(true) }
    }.buffer(Channel.UNLIMITED)

    /** Opens the connection to the endpoint's host ahead of the first request. Sends no credentials. */
    suspend fun warmUp() {
        try {
            val request = HttpRequest.newBuilder(URI(endpoint().url))
                .timeout(Duration.ofSeconds(5))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed warm-up only means the first real request pays for the connection; real requests report problems.
        }
    }

    private fun body(target: Endpoint, request: CompletionRequest): String = JsonObject().apply {
        addProperty("model", target.model)
        if (target.isChat) {
            add("messages", chatMessages(request.prompt))
        } else {
            addProperty("prompt", request.prompt.prefix)
            addProperty("suffix", request.prompt.suffix)
        }
        addProperty("max_tokens", request.maxTokens)
        addProperty("temperature", 0.0)
        addProperty("stream", true)
        // ponytail: OpenAI accepts at most four stop sequences; the rest are enforced locally by Cleanup.
        add("stop", JsonArray().also { array -> request.stop.take(MAX_STOPS).forEach(array::add) })
    }.toString()

    private fun chatMessages(prompt: FimPrompt) = JsonArray().apply {
        add(JsonObject().apply { addProperty("role", "system"); addProperty("content", CHAT_INSTRUCTION) })
        add(JsonObject().apply { addProperty("role", "user"); addProperty("content", prompt.prefix + CURSOR + prompt.suffix) })
    }

    private fun describe(error: Throwable): BackendException {
        val cause = if (error is CompletionException && error.cause != null) error.cause!! else error
        return when (cause) {
            is BackendException -> cause
            is HttpTimeoutException -> BackendException("The completion server did not answer in time", cause)
            is IOException -> BackendException("Cannot reach the completion server: ${cause.javaClass.simpleName}", cause)
            else -> BackendException("The completion request failed: ${cause.javaClass.simpleName}", cause)
        }
    }

    /** Drops in-flight requests at once instead of waiting for them. */
    override fun close() {
        if (clientHolder.isInitialized()) client.shutdownNow()
    }

    private companion object {
        const val CURSOR = "<CURSOR>"
        const val CHAT_INSTRUCTION = "You are a code completion engine. The user message holds a file with a $CURSOR marker. " +
            "Reply with only the exact text to insert at the marker: no explanation, no code fences, " +
            "and do not repeat the text around the marker."
        const val MAX_STOPS = 4
    }
}

/** Turns server sent event lines into text deltas. The JDK calls it one line at a time, never concurrently. */
internal class SseLines(
    private val chat: Boolean,
    private val onText: (String) -> Unit,
    private val onEnd: (Throwable?) -> Unit,
) : JdkFlow.Subscriber<String> {
    private lateinit var subscription: JdkFlow.Subscription
    private var received = 0
    private var finished = false

    override fun onSubscribe(subscription: JdkFlow.Subscription) {
        this.subscription = subscription
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(line: String) {
        if (finished) return
        received += line.length + 1
        if (received > MAX_RESPONSE_BYTES) return fail(BackendException("The server response is too large"))
        if (!line.startsWith("data:")) return
        val payload = line.substring(DATA_PREFIX.length).trim()
        if (payload == "[DONE]") return stop(null)
        try {
            text(payload)?.let(onText)
        } catch (e: JsonSyntaxException) {
            fail(BackendException("The server response is not valid JSON", e))
        } catch (e: IllegalStateException) {
            fail(BackendException("The server response has an unexpected shape", e))
        }
    }

    override fun onError(error: Throwable) = stop(error)

    override fun onComplete() = stop(null)

    private fun fail(error: BackendException) {
        subscription.cancel()
        stop(error)
    }

    private fun stop(error: Throwable?) {
        if (finished) return
        finished = true
        onEnd(error)
    }

    private fun text(payload: String): String? {
        val choices = JsonParser.parseString(payload).asJsonObject.getAsJsonArray("choices") ?: return null
        if (choices.isEmpty) return null
        val choice = choices[0].asJsonObject
        val node = if (chat) choice.getAsJsonObject("delta")?.get("content") else choice.get("text")
        return node?.takeIf { it.isJsonPrimitive }?.asString?.ifEmpty { null }
    }

    private companion object {
        const val DATA_PREFIX = "data:"
        const val MAX_RESPONSE_BYTES = 1 shl 20
    }
}
