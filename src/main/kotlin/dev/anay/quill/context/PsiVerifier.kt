package dev.anay.quill.context

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import dev.anay.quill.completion.Problems
import dev.anay.quill.completion.Verifier

/**
 * Checks a candidate completion the way the editor would underline it: the text is parsed in place, in a copy of
 * the file, and every reference it adds must resolve. A name that does not exist anywhere in the project is the
 * most common way a model is wrong, and the IDE's index can tell for certain.
 */
internal class PsiVerifier(
    private val file: PsiFile,
    private val original: String,
    private val caret: Int,
    private val commentPrefix: String,
) : Verifier {

    override suspend fun problems(candidate: String): Problems? {
        val unknown = readAction { unknownNames(candidate) }
        if (unknown.isEmpty()) return null
        return Problems(unknown, "$commentPrefix Do not use these names, they do not exist here: ${unknown.joinToString(", ")}\n")
    }

    private fun unknownNames(candidate: String): List<String> {
        if (!file.isValid || DumbService.isDumb(file.project)) return emptyList()
        val text = StringBuilder(original).insert(caret, candidate)
        val copy = PsiFileFactory.getInstance(file.project).createFileFromText(text, file) ?: return emptyList()
        val all = unresolved(copy, maxOf(0, caret - BASELINE_CHARS), caret + candidate.length)
        val added = all.filter { it.end > caret }.map { it.name }.toSet()
        val before = all.filter { it.end <= caret }.map { it.name }.toSet()
        // Anything the setup could not resolve before the caret (no SDK, a dynamic language, code being written)
        // makes the file unfit to judge, because one unknown type makes every member access on it unknown too.
        return if (before.isNotEmpty()) emptyList() else added.toList()
    }

    private class Unknown(val name: String, val end: Int)

    private fun unresolved(file: PsiFile, from: Int, to: Int): List<Unknown> {
        val found = ArrayList<Unknown>()
        val visited = HashSet<Any>()
        var leaf = file.findElementAt(from)
        while (leaf != null && leaf.textRange.startOffset < to) {
            ProgressManager.checkCanceled()
            val parent = leaf.parent
            if (parent != null && visited.add(parent)) {
                for (ref in parent.references) {
                    if (ref.isSoft || !isUnresolved(ref)) continue
                    val name = ref.rangeInElement.substring(ref.element.text)
                    if (name.isNotBlank()) found += Unknown(name, ref.element.textRange.startOffset + ref.rangeInElement.endOffset)
                }
            }
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return found
    }

    private fun isUnresolved(ref: PsiReference): Boolean =
        if (ref is PsiPolyVariantReference) ref.multiResolve(false).isEmpty() else ref.resolve() == null

    private companion object {
        // ponytail: judged by name, in a window before the caret; a real type check per language would be stricter.
        const val BASELINE_CHARS = 6_000
    }
}
