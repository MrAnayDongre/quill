package dev.anay.quill.context

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RecentEditsTest : BasePlatformTestCase() {
    private fun edit(name: String, text: String, insert: String) {
        val file = myFixture.addFileToProject(name, text)
        val document = myFixture.getDocument(file)
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, insert) }
    }

    // The service is shared by the whole IDE, so a test looks only for its own files in what it returns.
    private fun snippet(currentFile: String = "Current.java", line: Int = 0): String =
        RecentEdits.getInstance().snippets(currentFile, line).singleOrNull()?.text.orEmpty()

    fun testAnEditInAnotherFileBecomesContext() {
        edit("Notes.java", "class Notes {}\n", "// greetings are polite\n")
        assertTrue(snippet(), snippet().contains("Notes.java:1: // greetings are polite"))
    }

    fun testTheEditInProgressIsLeftOut() {
        edit("Mine.java", "class Mine {}\n", "int count = 0;\n")
        assertFalse(snippet(currentFile = "Mine.java", line = 0).contains("Mine.java"))
        assertTrue(snippet(currentFile = "Other.java", line = 0).contains("Mine.java:1: int count = 0;"))
    }

    fun testExcludedFilesAreNeverRemembered() {
        edit(".env", "A=1\n", "SECRET=hunter2\n")
        assertFalse(snippet().contains("hunter2"))
    }

    fun testRepeatedEditsOfOneLineAreKeptOnce() {
        val file = myFixture.addFileToProject("Loop.java", "class Loop {}\n")
        val document = myFixture.getDocument(file)
        repeat(3) { WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "x") } }
        val lines = snippet().lines().filter { it.startsWith("Loop.java") }
        assertEquals(lines.toString(), 1, lines.size)
    }
}
