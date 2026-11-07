package dev.anay.quill

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** End to end: provider, service, engine and HTTP backend against a local server. */
class ProviderTest : BasePlatformTestCase() {
    private lateinit var server: HttpServer
    private val bodies = CopyOnWriteArrayList<String>()

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/v1/completions") { exchange ->
            bodies += exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, 0)
            listOf("```\n", "4", "2\n", "```").forEach {
                exchange.responseBody.write("data: {\"choices\":[{\"text\":\"${it.replace("\n", "\\n")}\"}]}\n\n".toByteArray())
                exchange.responseBody.flush()
            }
            exchange.responseBody.write("data: [DONE]\n\n".toByteArray())
            exchange.close()
        }
        server.start()
        QuillSettings.getInstance().state.apply {
            endpoint = "http://localhost:${server.address.port}/v1/completions"
            debounceMs = 0
        }
    }

    override fun tearDown() {
        try {
            server.stop(0)
            QuillSettings.getInstance().loadState(QuillSettings.State())
        } finally {
            super.tearDown()
        }
    }

    // The platform creates this event itself in the editor; a test has to build it by hand.
    @Suppress("DEPRECATION")
    private fun directCall() = InlineCompletionEvent.DirectCall(myFixture.editor, myFixture.editor.caretModel.currentCaret, DataContext.EMPTY_CONTEXT)

    private var pieceCount = 0

    private fun suggest(): String? {
        val editor = myFixture.editor
        val offset = editor.caretModel.offset
        val request = InlineCompletionRequest(directCall(), myFixture.file, editor, editor.document, offset, offset, null)
        val suggestion = runBlocking(Dispatchers.Default) { QuillInlineCompletionProvider().getSuggestion(request) }
        if (suggestion === InlineCompletionSuggestion.Empty) return null
        val pieces = runBlocking { suggestion.getVariants().single().elements.toList().map { it.text } }
        pieceCount = pieces.size
        return pieces.joinToString("")
    }

    fun testShowsCleanedGhostText() {
        QuillSettings.getInstance().state.verify = false
        myFixture.configureByText("A.kt", "fun main() {\n    val x = <caret>\n}")
        assertEquals("42\n", suggest())
        assertTrue("ghost text should arrive in pieces", pieceCount > 1)
        assertTrue(bodies.single().contains("val x = "))
        assertTrue(bodies.single().contains("\\n}"))
    }

    fun testExcludedFilesAreNeverSent() {
        myFixture.configureByText(".env", "TOKEN=<caret>")
        assertFalse(QuillInlineCompletionProvider().isEnabled(directCall()))
        assertTrue(bodies.isEmpty())
    }

    fun testDisabledSettingTurnsTheProviderOff() {
        myFixture.configureByText("A.kt", "val x = <caret>")
        QuillSettings.getInstance().state.enabled = false
        assertFalse(QuillInlineCompletionProvider().isEnabled(directCall()))
    }
}
