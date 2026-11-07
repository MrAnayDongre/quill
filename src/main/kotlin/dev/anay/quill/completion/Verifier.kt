package dev.anay.quill.completion

/** Why a candidate must not be shown: [names] do not exist in the project, [hint] is a comment to put in front of the retry. */
internal class Problems(val names: List<String>, val hint: String)

/** Checks the text a completion would insert against what the project really contains. */
internal fun interface Verifier {
    suspend fun problems(candidate: String): Problems?
}
