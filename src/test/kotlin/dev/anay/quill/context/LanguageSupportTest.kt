package dev.anay.quill.context

import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.anay.quill.completion.ContextSnippet
import dev.anay.quill.completion.Problems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** The context and the verifier use only reference and name APIs, so other languages should behave like Java. */
class LanguageSupportTest : BasePlatformTestCase() {
    private fun snippets(): List<ContextSnippet> {
        val caret = myFixture.editor.caretModel.offset
        return ReadAction.compute<List<ContextSnippet>, RuntimeException> { SymbolContext.collect(myFixture.file, caret, 0, "") }
    }

    private fun problems(candidate: String): Problems? {
        val verifier = PsiVerifier(myFixture.file, myFixture.editor.document.text, myFixture.editor.caretModel.offset, "//")
        return runBlocking(Dispatchers.Default) { verifier.problems(candidate) }
    }

    fun testKotlinReceiverMembersAndVerification() {
        myFixture.addFileToProject("Order.kt", "class Order {\n    fun addLine(product: Product, quantity: Int) {}\n    fun subtotal(): Int = 1\n    private fun secret(): Int = 2\n}\nclass Product\n")
        myFixture.configureByText("Checkout.kt", "fun checkout(order: Order, product: Product) {\n    order.<caret>\n}\n")
        val text = snippets().joinToString("\n") { "${it.path}\n${it.text}" }
        assertTrue(text, text.contains("members of Order"))
        assertTrue(text, text.contains("fun addLine(product: Product, quantity: Int)"))
        assertFalse(text, text.contains("secret"))
        assertNull(problems("addLine(product, 1)\n"))
        assertEquals(listOf("shout"), problems("shout(1)\n")?.names)
    }

    fun testTypeScriptReceiverMembersWorkAndVerificationDoesNotGetInTheWay() {
        myFixture.addFileToProject("order.ts", "export class Order {\n    addLine(product: string, quantity: number): void {}\n    subtotal(): number { return 1; }\n}\n")
        myFixture.configureByText("checkout.ts", "import { Order } from './order';\nfunction checkout(order: Order) {\n    order.<caret>\n}\n")
        val text = snippets().joinToString("\n") { "${it.path}\n${it.text}" }
        assertTrue(text, text.contains("addLine(product: string, quantity: number)"))
        assertNull(problems("addLine('x', 1);\n"))
        // Unknown properties are not reported as unresolved references in TypeScript, so checking never blocks here.
        assertNull(problems("shout(1);\n"))
    }
}
