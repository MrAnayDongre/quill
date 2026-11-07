package dev.anay.quill.ide

import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.session.InlineCompletionContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import dev.anay.quill.settings.QuillSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Types into a real editor and waits for the platform to show ghost text produced by Quill. */
class EditorTest : BasePlatformTestCase() {
    private lateinit var server: HttpServer
    private var scope: CoroutineScope? = null
    private var firstAnswer = listOf("reeter.", "invented(1)")
    private var repairedAnswer = listOf("reeter.", "greet(\\\"x\\\", 1)")
    private val bodies = CopyOnWriteArrayList<String>()

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/v1") { exchange ->
            val request = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            bodies += request
            exchange.sendResponseHeaders(200, 0)
            val answer = if (request.contains("Do not use these names")) repairedAnswer else firstAnswer
            answer.forEach {
                exchange.responseBody.write("data: {\"choices\":[{\"text\":\"$it\"}]}\n\n".toByteArray())
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
            scope?.cancel()
            QuillSettings.getInstance().loadState(QuillSettings.State())
        } finally {
            super.tearDown()
        }
    }

    private fun ghostText(): String? = InlineCompletionContext.getOrNull(myFixture.editor)?.textToInsert()

    fun testTypingShowsGhostTextAndSendsProjectSymbols() {
        myFixture.addFileToProject("Greeter.java", "public class Greeter {\n    public String greet(String name, int times) { return name; }\n}\n")
        myFixture.configureByText("Main.java", "class Main {\n    void run() {\n        Greeter greeter = new Greeter();\n        greeter.greet(\"a\", 1);\n        <caret>\n    }\n}\n")
        // Fixture editors are created without the platform's install listener, so install the handler the way the IDE does.
        if (InlineCompletion.getHandlerOrNull(myFixture.editor) == null) {
            InlineCompletion.install(myFixture.editor as EditorEx, CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it })
        }
        myFixture.type("g")
        PlatformTestUtil.waitWithEventsDispatching("no ghost text appeared", { !ghostText().isNullOrEmpty() }, 10)
        assertEquals("reeter.greet(\"x\", 1)", ghostText())
        assertEquals("the invented name should cost exactly one retry", 2, bodies.size)
        assertTrue(bodies.last(), bodies.last().contains("Do not use these names"))
        assertTrue(bodies.last(), bodies.last().contains("invented"))
        val request = bodies.last()
        assertTrue(request, request.contains("public String greet(String name, int times)"))
        assertTrue(request, request.contains("void run()"))
        assertTrue(request, request.contains("public class Greeter"))
    }

    fun testMemberAccessSendsTheMembersOfTheReceiversType() {
        myFixture.addFileToProject("Greeter.java", "public class Greeter {\n    public String greet(String name, int times) { return name; }\n    private int secret() { return 1; }\n}\n")
        firstAnswer = listOf("reet(\\\"x\\\", 1)")
        repairedAnswer = firstAnswer
        myFixture.configureByText("Main.java", "class Main {\n    void run(Greeter greeter) {\n        greeter.<caret>\n    }\n}\n")
        if (InlineCompletion.getHandlerOrNull(myFixture.editor) == null) {
            InlineCompletion.install(myFixture.editor as EditorEx, CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it })
        }
        myFixture.type("g")
        PlatformTestUtil.waitWithEventsDispatching("no ghost text appeared", { !ghostText().isNullOrEmpty() }, 10)
        val request = bodies.last()
        assertTrue(request, request.contains("members of Greeter"))
        assertTrue(request, request.contains("public String greet(String name, int times)"))
        assertFalse(request, request.contains("secret"))
    }

    fun testARecentEditInAnotherFileIsSentAsContext() {
        val notes = myFixture.addFileToProject("Notes.java", "class Notes {}\n")
        val notesEditor = EditorFactory.getInstance().createEditor(myFixture.getDocument(notes), project)
        WriteCommandAction.runWriteCommandAction(project) { myFixture.getDocument(notes).insertString(0, "// greetings are polite\n") }
        EditorFactory.getInstance().releaseEditor(notesEditor)
        firstAnswer = listOf("reet(\\\"x\\\", 1)")
        repairedAnswer = firstAnswer
        myFixture.addFileToProject("Greeter.java", "public class Greeter {\n    public String greet(String name, int times) { return name; }\n}\n")
        myFixture.configureByText("Main.java", "class Main {\n    void run(Greeter greeter) {\n        greeter.<caret>\n    }\n}\n")
        if (InlineCompletion.getHandlerOrNull(myFixture.editor) == null) {
            InlineCompletion.install(myFixture.editor as EditorEx, CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it })
        }
        myFixture.type("g")
        PlatformTestUtil.waitWithEventsDispatching("no ghost text appeared", { !ghostText().isNullOrEmpty() }, 10)
        assertTrue(bodies.last(), bodies.last().contains("Notes.java:1: // greetings are polite"))
    }
}
