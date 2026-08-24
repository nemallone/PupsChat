package nemallone.bworld.chat.filter

import java.io.InputStream
import java.text.Normalizer
import java.util.Locale

internal class ToxicityLexicon private constructor(
    val exactWords: Set<String>,
    val stems: List<String>,
    val phrasesByFirstToken: Map<String, List<List<String>>>,
    val allowedWords: Set<String>
) {
    companion object {
        fun empty() = ToxicityLexicon(emptySet(), emptyList(), emptyMap(), emptySet())

        fun load(
            rules: InputStream,
            allowedWords: List<String> = emptyList(),
            warning: (String) -> Unit = {}
        ): ToxicityLexicon {
            val exact = LinkedHashSet<String>()
            val stems = LinkedHashSet<String>()
            val phrases = LinkedHashSet<List<String>>()

            rules.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { index, rawLine ->
                    addRule(rawLine, index + 1, exact, stems, phrases, warning)
                }
            }

            val allowed = LinkedHashSet<String>()
            allowedWords.forEach { addSingleToken(it, "allowed-words", allowed, warning) }

            return ToxicityLexicon(
                exactWords = exact.toSet(),
                stems = stems.sortedByDescending(String::length),
                phrasesByFirstToken = phrases.groupBy { it.first() },
                allowedWords = allowed.toSet()
            )
        }

        private fun addRule(
            rawLine: String,
            lineNumber: Int,
            exact: MutableSet<String>,
            stems: MutableSet<String>,
            phrases: MutableSet<List<String>>,
            warning: (String) -> Unit
        ) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return

            val separator = line.indexOf(':')
            if (separator <= 0) {
                warning("Неизвестная запись toxicity.txt:$lineNumber")
                return
            }

            val type = line.substring(0, separator)
            val value = line.substring(separator + 1)
            val source = "toxicity.txt:$lineNumber"
            when (type) {
                "exact" -> addSingleToken(value, source, exact, warning)
                "stem" -> addSingleToken(value, source, stems, warning)
                "phrase" -> addPhrase(value, source, phrases, warning)
                else -> warning("Неизвестная запись toxicity.txt:$lineNumber")
            }
        }

        private fun addSingleToken(
            raw: String,
            source: String,
            target: MutableSet<String>,
            warning: (String) -> Unit
        ) {
            val tokens = ToxicityMatcher.tokenize(raw)
            if (tokens.size != 1) {
                warning("Запись $source должна содержать ровно один токен")
                return
            }
            target.add(tokens[0])
        }

        private fun addPhrase(
            raw: String,
            source: String,
            target: MutableSet<List<String>>,
            warning: (String) -> Unit
        ) {
            val tokens = ToxicityMatcher.tokenize(raw)
            if (tokens.isEmpty()) {
                warning("Пустая фраза в $source")
                return
            }
            target.add(tokens.toList())
        }
    }
}

internal object ToxicityMatcher {
    fun containsToxicity(message: String, lexicon: ToxicityLexicon): Boolean {
        val tokenization = tokenizeDetailed(message)
        val tokens = tokenization.tokens
        if (tokens.isEmpty()) return false

        if (matchesPhrase(tokens, lexicon.phrasesByFirstToken)) return true

        for (token in tokens) {
            if (matchesWord(token, lexicon)) return true
        }

        return matchesSeparatedFragments(tokenization, lexicon)
    }

    internal fun tokenize(input: String): List<String> {
        return tokenizeDetailed(input).tokens
    }

    private data class Tokenization(
        val tokens: List<String>,
        val punctuationBetween: List<Boolean>
    )

    private fun tokenizeDetailed(input: String): Tokenization {
        if (input.isEmpty()) return Tokenization(emptyList(), emptyList())

        val normalized = Normalizer.normalize(input, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        val tokens = ArrayList<String>(8)
        val punctuationBetween = ArrayList<Boolean>(8)
        val rawToken = StringBuilder()
        var hasSeparator = false
        var separatorHasPunctuation = false

        var offset = 0
        while (offset < normalized.length) {
            val codePoint = normalized.codePointAt(offset)
            offset += Character.charCount(codePoint)

            when {
                Character.getType(codePoint) == Character.FORMAT.toInt() -> Unit
                Character.isLetterOrDigit(codePoint) -> {
                    if (rawToken.isEmpty() && tokens.isNotEmpty() && hasSeparator) {
                        punctuationBetween.add(separatorHasPunctuation)
                        hasSeparator = false
                        separatorHasPunctuation = false
                    }
                    rawToken.appendCodePoint(codePoint)
                }
                else -> {
                    if (rawToken.isNotEmpty()) flushToken(rawToken, tokens)
                    if (tokens.isNotEmpty()) {
                        hasSeparator = true
                        if (!Character.isWhitespace(codePoint)) separatorHasPunctuation = true
                    }
                }
            }
        }
        if (rawToken.isNotEmpty()) flushToken(rawToken, tokens)
        return Tokenization(tokens, punctuationBetween)
    }

    private fun flushToken(rawToken: StringBuilder, tokens: MutableList<String>) {
        val token = normalizeToken(rawToken)
        if (token.isNotEmpty()) tokens.add(token)
        rawToken.setLength(0)
    }

    private fun normalizeToken(input: CharSequence): String {
        val normalizedToken = StringBuilder(input.length)
        var previous = -1
        var offset = 0
        while (offset < input.length) {
            var codePoint = Character.codePointAt(input, offset)
            offset += Character.charCount(codePoint)

            if (codePoint == 'ё'.code) codePoint = 'е'.code
            codePoint = normalizeHomoglyph(codePoint)

            if (codePoint != previous) normalizedToken.appendCodePoint(codePoint)
            previous = codePoint
        }
        return normalizedToken.toString()
    }

    private fun matchesWord(token: String, lexicon: ToxicityLexicon): Boolean {
        if (token in lexicon.allowedWords) return false
        if (token in lexicon.exactWords) return true
        for (stem in lexicon.stems) {
            if (token.contains(stem)) return true
        }
        return false
    }

    private fun matchesPhrase(
        tokens: List<String>,
        phrasesByFirstToken: Map<String, List<List<String>>>
    ): Boolean {
        for (start in tokens.indices) {
            val phrases = phrasesByFirstToken[tokens[start]] ?: continue
            for (phrase in phrases) {
                if (start + phrase.size > tokens.size) continue
                var matches = true
                for (phraseIndex in 1 until phrase.size) {
                    if (tokens[start + phraseIndex] != phrase[phraseIndex]) {
                        matches = false
                        break
                    }
                }
                if (matches) return true
            }
        }
        return false
    }

    private fun matchesSeparatedFragments(
        tokenization: Tokenization,
        lexicon: ToxicityLexicon
    ): Boolean {
        val tokens = tokenization.tokens
        for (start in tokens.indices) {
            val candidate = StringBuilder()
            var punctuationSeen = false
            var allSingleCharacters = true

            val endExclusive = minOf(tokens.size, start + MAX_JOINED_FRAGMENTS)
            for (end in start until endExclusive) {
                val token = tokens[end]
                val codePoints = token.codePointCount(0, token.length)
                if (codePoints > MAX_FRAGMENT_LENGTH) break
                if (candidate.length + token.length > MAX_JOINED_LENGTH) break

                if (end > start) {
                    punctuationSeen = punctuationSeen ||
                        tokenization.punctuationBetween.getOrElse(end - 1) { false }
                }
                candidate.append(token)
                allSingleCharacters = allSingleCharacters && codePoints == 1

                val fragmentCount = end - start + 1
                if (fragmentCount < 2) continue

                val normalized = normalizeToken(candidate)
                if (punctuationSeen && matchesWord(normalized, lexicon)) return true

                if (fragmentCount >= MIN_WHITESPACE_FRAGMENTS) {
                    if (allSingleCharacters && matchesWord(normalized, lexicon)) return true
                    if (matchesWholeRule(normalized, lexicon)) return true
                }
            }
        }
        return false
    }

    private fun matchesWholeRule(token: String, lexicon: ToxicityLexicon): Boolean {
        return token !in lexicon.allowedWords &&
            (token in lexicon.exactWords || token in lexicon.stems)
    }

    private fun normalizeHomoglyph(codePoint: Int): Int = when (codePoint) {
        'a'.code -> 'а'.code
        'b'.code -> 'в'.code
        'c'.code -> 'с'.code
        'e'.code -> 'е'.code
        'h'.code -> 'н'.code
        'k'.code -> 'к'.code
        'm'.code -> 'м'.code
        'o'.code, '0'.code -> 'о'.code
        'p'.code -> 'р'.code
        't'.code -> 'т'.code
        'x'.code -> 'х'.code
        'y'.code -> 'у'.code
        'i'.code, 'і'.code, 'ї'.code -> 'и'.code
        'є'.code -> 'е'.code
        'ґ'.code -> 'г'.code
        '3'.code -> 'з'.code
        '4'.code -> 'ч'.code
        '6'.code -> 'б'.code
        else -> codePoint
    }

    private const val MIN_WHITESPACE_FRAGMENTS = 3
    private const val MAX_FRAGMENT_LENGTH = 4
    private const val MAX_JOINED_FRAGMENTS = 12
    private const val MAX_JOINED_LENGTH = 32
}
