package dev.anay.quill.completion

/**
 * Finds the real name a model meant when it invented one. Names are compared as words of their camel case form:
 * `getTier` and `getLoyaltyTier` share every word of the shorter one, `customerTier` shares too little. Only a
 * single, clear winner is returned, so a wrong guess never replaces a right answer.
 */
internal object NameRepair {
    private val wordBoundary = Regex("(?<=[a-z0-9])(?=[A-Z])|_")
    private const val MIN_WORDS = 2

    fun nearest(unknown: String, known: Collection<String>): String? {
        val wanted = words(unknown)
        if (wanted.size < MIN_WORDS) return null
        val scored = known.asSequence().distinct().filter { it != unknown }.mapNotNull { candidate ->
            val other = words(candidate)
            val shared = wanted.intersect(other).size
            val contained = shared == minOf(wanted.size, other.size) && shared >= MIN_WORDS
            if (contained) candidate to shared else null
        }.toList()
        val best = scored.maxOfOrNull { it.second } ?: return null
        return scored.filter { it.second == best }.singleOrNull()?.first
    }

    private fun words(name: String): Set<String> = name.split(wordBoundary).filter { it.isNotEmpty() }.map { it.lowercase() }.toSet()

    /** Replaces each whole word key of [fixes] in [line]. */
    fun apply(line: String, fixes: Map<String, String>): String =
        fixes.entries.fold(line) { text, (from, to) -> text.replace(Regex("\\b${Regex.escape(from)}\\b"), to) }
}
