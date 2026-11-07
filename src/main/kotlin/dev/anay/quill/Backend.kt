package dev.anay.quill

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.future.await
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.net.http.HttpResponse
import java.time.Duration

data class CompletionRequest(val prompt: FimPrompt, val maxTokens: Int, val stop: List<String>)

/** A failure whose message is safe to show: it never carries request or response bodies. */
class BackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface CompletionBackend {
    suspend fun complete(request: CompletionRequest): String
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
 * Talks to any server that implements the OpenAI API. An endpoint ending in `/chat/completions` gets a chat request
 * with a cursor marker; any other endpoint gets a completions request with the `suffix` field for fill in the middle.
 */
class OpenAiBackend(
    private val endpoint: suspend () -> Endpoint,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : CompletionBackend, AutoCloseable {

    override suspend fun complete(request: CompletionRequest): String {
        val target = endpoint()
        val httpRequest = HttpRequest.newBuilder(URI(target.url))
            .timeout(target.timeout)
            .header("Content-Type", "application/json")
            .apply { target.apiKey?.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(body(target, request)))
            .build()
        val response = try {
            client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream()).await()
        } catch (e: HttpTimeoutException) {
            throw BackendException("The completion server did not answer in time", e)
        } catch (e: IOException) {
            throw BackendException("Cannot reach the completion server: ${e.javaClass.simpleName}", e)
        }
        response.body().use { stream ->
            if (response.statusCode() !in 200..299) throw BackendException("The server answered HTTP ${response.statusCode()}")
            val bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1)
            if (bytes.size > MAX_RESPONSE_BYTES) throw BackendException("The server response is too large")
            return parse(bytes.toString(Charsets.UTF_8), target.isChat)
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
        addProperty("stream", false)
        // ponytail: OpenAI accepts at most four stop sequences; the rest are enforced locally by Cleanup.
        add("stop", JsonArray().also { array -> request.stop.take(MAX_STOPS).forEach(array::add) })
    }.toString()

    private fun chatMessages(prompt: FimPrompt) = JsonArray().apply {
        add(JsonObject().apply { addProperty("role", "system"); addProperty("content", CHAT_INSTRUCTION) })
        add(JsonObject().apply { addProperty("role", "user"); addProperty("content", prompt.prefix + CURSOR + prompt.suffix) })
    }

    private fun parse(json: String, chat: Boolean): String {
        val text = runCatching {
            val choice = JsonParser.parseString(json).asJsonObject.getAsJsonArray("choices").get(0).asJsonObject
            if (chat) choice.getAsJsonObject("message").get("content") else choice.get("text")
        }.getOrNull()
        if (text == null || !text.isJsonPrimitive) throw BackendException("The server response has no completion text")
        return text.asString
    }

    /** Drops in-flight requests at once instead of waiting for them. */
    override fun close() = client.shutdownNow()

    private companion object {
        const val CURSOR = "<CURSOR>"
        const val CHAT_INSTRUCTION = "You are a code completion engine. The user message holds a file with a $CURSOR marker. " +
            "Reply with only the exact text to insert at the marker: no explanation, no code fences, " +
            "and do not repeat the text around the marker."
        const val MAX_RESPONSE_BYTES = 1 shl 20
        const val MAX_STOPS = 4
    }
}
