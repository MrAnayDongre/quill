package dev.anay.quill.context

import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import dev.anay.quill.completion.NameRepair
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
    private val knownNames: Set<String> = emptySet(),
) : Verifier {

    override suspend fun problems(candidate: String): Problems? {
        val found = readAction { unknownNames(candidate) }
        if (found.isEmpty()) return null
        val unknown = found.map { it.name }.distinct()
        val fixes = unknown.mapNotNull { name -> NameRepair.nearest(name, knownNames)?.let { name to it } }.toMap()
        val sound = found.minOf { it.start }.coerceAtLeast(0)
        return Problems(unknown, "$commentPrefix Do not use these names, they do not exist here: ${unknown.joinToString(", ")}\n", fixes, sound)
    }

    // Whether the lines before the caret already hold names the IDE can not resolve. It does not depend on the
    // candidate, so it is worked out once, and only when a candidate has a suspicious name.
    private var fileUnfitToJudge: Boolean? = null

    private fun unknownNames(candidate: String): List<Unknown> {
        if (!file.isValid || DumbService.isDumb(file.project)) return emptyList()
        val text = StringBuilder(original).insert(caret, candidate)
        val copy = PsiFileFactory.getInstance(file.project).createFileFromText(text, file) ?: return emptyList()
        val added = unresolved(copy, caret, caret + candidate.length).filter { it.end > caret }
        if (added.isEmpty()) return emptyList()
        // One unknown type (no SDK, a dynamic language, code being written) makes every member access on it unknown
        // too, so a file with unresolved names before the caret can not be judged.
        val unfit = fileUnfitToJudge ?: unresolved(copy, maxOf(0, caret - BASELINE_CHARS), caret).any { it.end <= caret }.also { fileUnfitToJudge = it }
        return if (unfit) emptyList() else added
    }

    /** An unresolved reference: its name, where it ends, and where it starts in the candidate (at its qualifier, if it has one). */
    private class Unknown(val name: String, val end: Int, val start: Int)

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
                    if (name.isNotBlank()) found += Unknown(name, ref.element.textRange.startOffset + ref.rangeInElement.endOffset, ref.element.textRange.startOffset - caret)
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
