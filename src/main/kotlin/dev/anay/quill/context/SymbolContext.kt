package dev.anay.quill.context

import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.PsiTreeUtil
import dev.anay.quill.completion.ContextSnippet
import dev.anay.quill.settings.Exclusions

/**
 * Gives the model what a person reading the file would look up: the classes and functions that enclose the caret,
 * and the declarations of the symbols used just before it, wherever in the project they live. It works through
 * the reference and name APIs every language plugin implements, so there is no per language code. Call it inside
 * a read action.
 */
internal object SymbolContext {
    private const val MAX_SCOPES = 3
    private const val MAX_DECLARATIONS = 8
    private const val MAX_RECEIVER_TYPES = 2
    private const val MAX_MEMBERS = 14
    private const val LINES_BACK = 2
    private const val MAX_SIGNATURE_LINES = 4
    private const val MAX_LINE_CHARS = 200

    fun collect(file: PsiFile, caret: Int, windowStart: Int, excludedPatterns: String): List<ContextSnippet> {
        if (DumbService.isDumb(file.project)) return emptyList()
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return emptyList()
        return try {
            listOfNotNull(scopes(file, document, caret)) +
                receiverMembers(file, document, caret, excludedPatterns) +
                declarations(file, document, caret, windowStart, excludedPatterns)
        } catch (_: IndexNotReadyException) {
            emptyList()
        }
    }

    private fun scopes(file: PsiFile, document: Document, caret: Int): ContextSnippet? {
        val start = file.findElementAt((caret - 1).coerceAtLeast(0)) ?: return null
        val headers = generateSequence(start) { it.parent }
            .takeWhile { it !is PsiFile }
            .filterIsInstance<PsiNameIdentifierOwner>()
            .map { signature(document, nameOffset(it)).first }
            .distinct()
            .take(MAX_SCOPES)
            .toList()
            .asReversed()
        return headers.takeIf { it.isNotEmpty() }?.let { ContextSnippet("enclosing scopes", it.joinToString("\n")) }
    }

    /**
     * For `receiver.` before the caret, lists the members of the receiver's type: what a completion popup would show.
     * The type is found by following the references in the receiver's declaration, so it works for variables,
     * parameters, fields and calls alike, and for a class named directly.
     */
    private fun receiverMembers(file: PsiFile, document: Document, caret: Int, excluded: String): List<ContextSnippet> {
        val text = document.charsSequence
        var word = caret
        while (word > 0 && (text[word - 1].isLetterOrDigit() || text[word - 1] == '_')) word--
        if (word < 2 || text[word - 1] != '.') return emptyList()
        val target = lastReferenceEndingAt(file, word - 1)?.resolve() ?: return emptyList()
        val index = ProjectFileIndex.getInstance(file.project)
        val types = typesIn(target).ifEmpty { listOf(target) }
        return types.mapNotNull { members(it, index, excluded) }.take(MAX_RECEIVER_TYPES)
    }

    private fun lastReferenceEndingAt(file: PsiFile, end: Int): PsiReference? {
        var top: PsiElement = file.findElementAt(end - 1) ?: return null
        while (top.parent != null && top.parent !is PsiFile && top.parent.textRange.endOffset == end) top = top.parent
        fun PsiReference.end() = element.textRange.startOffset + rangeInElement.endOffset
        return SyntaxTraverser.psiTraverser(top).asSequence()
            .flatMap { it.references.asSequence() }
            .filter { it.end() <= end }
            .maxByOrNull { it.end() }
    }

    /** What the declaration of [target] refers to before its end of line: its type, for a variable or a function. */
    private fun typesIn(target: PsiElement): List<PsiElement> {
        val file = target.containingFile ?: return emptyList()
        val document = PsiDocumentManager.getInstance(target.project).getDocument(file) ?: return emptyList()
        val lineEnd = document.getLineEndOffset(document.getLineNumber(nameOffset(target).coerceIn(0, document.textLength)))
        val found = LinkedHashSet<PsiElement>()
        var leaf = file.findElementAt(target.textRange.startOffset)
        while (leaf != null && leaf.textRange.startOffset < lineEnd && found.size < MAX_RECEIVER_TYPES) {
            leaf.parent?.references?.forEach { ref -> ref.resolve()?.takeIf { it != target }?.let(found::add) }
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return found.toList()
    }

    private fun members(type: PsiElement, index: ProjectFileIndex, excluded: String): ContextSnippet? {
        val file = type.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (!index.isInContent(virtualFile) || Exclusions.isExcluded(virtualFile.name, excluded)) return null
        val document = PsiDocumentManager.getInstance(type.project).getDocument(file) ?: return null
        // ponytail: private members are skipped by looking for the word on the declaration line, not by asking the language.
        val lines = SyntaxTraverser.psiTraverser(type).asSequence()
            .filterIsInstance<PsiNameIdentifierOwner>()
            .filter { it != type && PsiTreeUtil.getParentOfType(it, PsiNameIdentifierOwner::class.java) == type }
            .map { signature(document, nameOffset(it)).first }
            .filter { !it.contains("private ") }
            .distinct()
            .take(MAX_MEMBERS)
            .toList()
        val name = (type as? PsiNameIdentifierOwner)?.name ?: virtualFile.nameWithoutExtension
        return lines.takeIf { it.isNotEmpty() }?.let { ContextSnippet("members of $name", it.joinToString("\n")) }
    }

    private fun declarations(file: PsiFile, document: Document, caret: Int, windowStart: Int, excluded: String): List<ContextSnippet> {
        val index = ProjectFileIndex.getInstance(file.project)
        val firstLine = (document.getLineNumber(caret) - LINES_BACK).coerceAtLeast(0)
        val seen = LinkedHashSet<PsiElement>()
        val visited = HashSet<PsiElement>()
        var leaf = file.findElementAt(document.getLineStartOffset(firstLine))
        while (leaf != null && leaf.textRange.startOffset < caret && seen.size < MAX_DECLARATIONS) {
            ProgressManager.checkCanceled()
            val parent = leaf.parent
            if (parent != null && visited.add(parent)) parent.references.forEach { ref -> ref.resolve()?.let(seen::add) }
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return seen.mapNotNull { describe(it, file, windowStart, index, excluded) }
    }

    private fun describe(target: PsiElement, current: PsiFile, windowStart: Int, index: ProjectFileIndex, excluded: String): ContextSnippet? {
        val targetFile = target.containingFile ?: return null
        val virtualFile = targetFile.virtualFile ?: return null
        if (!index.isInContent(virtualFile) || Exclusions.isExcluded(virtualFile.name, excluded)) return null
        // A declaration that is already inside the prompt window adds nothing.
        if (targetFile == current && target.textRange.startOffset >= windowStart) return null
        val document = PsiDocumentManager.getInstance(target.project).getDocument(targetFile) ?: return null
        return ContextSnippet(virtualFile.name, signature(document, nameOffset(target)).second)
    }

    private fun nameOffset(element: PsiElement): Int =
        (element as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.startOffset ?: element.textRange.startOffset

    /** The declaration line, extended over following lines while its parentheses are still open. Returns first line and full text. */
    private fun signature(document: Document, offset: Int): Pair<String, String> {
        val first = document.getLineNumber(offset.coerceIn(0, document.textLength))
        val lines = ArrayList<String>()
        var depth = 0
        for (line in first until minOf(document.lineCount, first + MAX_SIGNATURE_LINES)) {
            val text = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
            lines += text.take(MAX_LINE_CHARS)
            depth += text.count { it == '(' } - text.count { it == ')' }
            if (depth <= 0) break
        }
        return lines.first() to lines.joinToString("\n")
    }
}
