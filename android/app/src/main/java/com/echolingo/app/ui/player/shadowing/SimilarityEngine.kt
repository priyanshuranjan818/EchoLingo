package com.echolingo.app.ui.player.shadowing

/**
 * On-device fuzzy similarity between two strings.
 *
 * Algorithm: Levenshtein distance normalized by max length.
 * Score = (1 - editDistance / maxLen) * 100, clamped 0-100.
 *
 * Pre-processing: lower-case, strip punctuation, collapse whitespace.
 * Threshold used by caller: ≥70 = pass.
 */
object SimilarityEngine {

    fun score(userText: String, referenceText: String): Int {
        val a = normalize(userText)
        val b = normalize(referenceText)
        if (a.isEmpty() && b.isEmpty()) return 100
        if (a.isEmpty() || b.isEmpty()) return 0
        val maxLen = maxOf(a.length, b.length).toFloat()
        val dist   = levenshtein(a, b)
        return ((1f - dist / maxLen) * 100).toInt().coerceIn(0, 100)
    }

    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")   // strip punctuation
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun levenshtein(a: String, b: String): Float {
        val m = a.length
        val n = b.length
        // Use two rows to save memory
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                curr[j] = if (a[i - 1] == b[j - 1]) {
                    prev[j - 1]
                } else {
                    1 + minOf(prev[j], curr[j - 1], prev[j - 1])
                }
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[n].toFloat()
    }
}
