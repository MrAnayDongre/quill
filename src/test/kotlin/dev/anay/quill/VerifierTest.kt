package dev.anay.quill

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class VerifierTest : BasePlatformTestCase() {
    private fun verifier(source: String): PsiVerifier {
        myFixture.addFileToProject("Greeter.java", "public class Greeter {\n    public String greet(String name, int times) { return name; }\n}\n")
        myFixture.configureByText("Main.java", source)
        val caret = myFixture.editor.caretModel.offset
        return PsiVerifier(myFixture.file, myFixture.editor.document.text, caret, "//")
    }

    private fun problems(v: PsiVerifier, candidate: String) = runBlocking(Dispatchers.Default) { v.problems(candidate) }

    private val source = "class Main {\n    void run(Greeter greeter) {\n        <caret>\n    }\n}\n"

    fun testRealMembersPass() {
        assertNull(problems(verifier(source), "greeter.greet(\"x\", 1);\n"))
    }

    fun testInventedMemberIsCaughtAndNamed() {
        val found = problems(verifier(source), "greeter.shout(\"x\");\n")!!
        assertEquals(listOf("shout"), found.names)
        assertTrue(found.hint.startsWith("// Do not use these names"))
    }

    fun testNamesTheCandidateDeclaresItselfPass() {
        assertNull(problems(verifier(source), "int count = 3;\n        count = count + 1;\n"))
    }

    fun testCompletingAPartlyTypedIdentifierIsChecked() {
        val v = verifier("class Main {\n    void run(Greeter greeter) {\n        gre<caret>\n    }\n}\n")
        assertNull(problems(v, "eter.greet(\"x\", 1);\n"))
        assertNotNull(problems(v, "eter.nothing();\n"))
    }

    fun testNamesUnresolvedBeforeTheCaretAreNotBlamedOnTheCandidate() {
        val v = verifier("class Main {\n    void run(Greeter greeter) {\n        Unknown u = null;\n        <caret>\n    }\n}\n")
        assertNull(problems(v, "u.toString();\n        Unknown w = null;\n"))
    }
}
