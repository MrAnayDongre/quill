package dev.anay.quill.context

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.anay.quill.settings.QuillSettings

class RecentEditsTest : BasePlatformTestCase() {
    private fun edit(file: VirtualFile, insert: String) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, insert) }
    }

    private fun addAndEdit(name: String, text: String, insert: String): VirtualFile {
        val file = myFixture.addFileToProject(name, text).virtualFile
        edit(file, insert)
        return file
    }

    private val excluded get() = QuillSettings.getInstance().state.excludedPatterns

    // The service is shared by the whole IDE, so a test looks only for its own files in what it returns.
    private fun snippet(current: VirtualFile, line: Int = 0): String =
        RecentEdits.getInstance().snippets(project, current, line, excluded).singleOrNull()?.text.orEmpty()

    private fun somewhereElse(): VirtualFile = myFixture.addFileToProject("Current.java", "class Current {}\n").virtualFile

    fun testAnEditInAnotherFileBecomesContext() {
        addAndEdit("Notes.java", "class Notes {}\n", "// greetings are polite\n")
        val text = snippet(somewhereElse())
        assertTrue(text, text.contains("Notes.java:1: // greetings are polite"))
    }

    fun testTheEditInProgressIsLeftOut() {
        val mine = addAndEdit("Mine.java", "class Mine {}\n", "int count = 0;\n")
        assertFalse(snippet(mine, 0).contains("Mine.java"))
        assertTrue(snippet(somewhereElse(), 0).contains("Mine.java:1: int count = 0;"))
    }

    fun testExcludedFilesAreNeverRemembered() {
        addAndEdit(".env", "A=1\n", "SECRET=hunter2\n")
        assertFalse(snippet(somewhereElse()).contains("hunter2"))
    }

    fun testFilesOutsideTheProjectAreNeverSent() {
        val outside = LightVirtualFile("Outside.java", "class Outside {}\n")
        edit(outside, "int leaked = 42;\n")
        assertFalse(snippet(somewhereElse()).contains("leaked"))
    }

    fun testRepeatedEditsOfOneLineAreKeptOnce() {
        val file = myFixture.addFileToProject("Loop.java", "class Loop {}\n").virtualFile
        repeat(3) { edit(file, "x") }
        val lines = snippet(somewhereElse()).lines().filter { it.startsWith("Loop.java") }
        assertEquals(lines.toString(), 1, lines.size)
    }
}
