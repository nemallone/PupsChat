package nemallone.bworld.chat.filter

internal object SelfDirectedContext {
    private fun words(value: String) = ToxicityMatcher.tokenize(value).toSet()

    private val modifiers = words("такой такая такое такие таким такою настолько очень слишком совсем совсем-то же то сам сама само самый самая самое просто опять снова снова-то реально действительно вообще теперь сегодня тоже еще полный полная полное полным полнейший настоящий настоящая настоящее жалкий жалкая жалкое жалким конченый конченая конченое тупой тупая тупое тупым безмозглый безмозглая никчемный никчемная никчемное бездарный бездарная чертов ебаный ебаная ебаное не дважды трижды тот наверное наверно конечно похоже кажется видимо")
    private val selfVerbs = words("считаю называю обзываю ощущаю чувствую признаю назову назвал назвала обозвал обозвала считал считала почувствовал почувствовала считаюсь чувствуюсь")
    private val presentSelfVerbs = words("считаю называю обзываю ощущаю чувствую признаю назову")
    private val actionVerbs = words("сказал сказала говорю говорил говорила пишу написал написала ненавижу вижу люблю знаю думаю думал думала считаю считаешь сказал-то убил ударил унизил унизила ебал ебу ебала трахал трахнул бью избил уничтожил разнес обидел оскорбил оскорбляю обзываю гноблю травлю ненавидел ненавидела")
    private val otherSubjects = words("ты тебя тебе тобой твой твоя твое твои твою твоей вы вас вам вами ваш ваша ваше ваши вашу вашей он она оно они его ее их ему ей им мы нас нам нами наш наша наше наши нашу моей мой моя мое мои мою своего свой своя свое свои эту этот эта эти тот та те человек игрок команда мама маму мать мамка мамку папа отец родители you your yours he him his she her hers they them their we us our yourself")
    private val joins = words("и а но или")
    private val comparisons = words("как словно будто подобно чем хуже лучше нежели")
    private val neutralComparisons = words("обычно всегда раньше прежде никогда вчера сегодня")
    private val neutralPrefix = words("ну блин бля какой какая какое какие же вот это почему насколько что опять снова сегодня теперь сам сама-то")
    private val reportedVerbs = words("сказал сказала сказал-то говорит говорил говорила говорят написал написала пишет писал писала думал думала думает считал считала считает считай")
    private val reportingModifiers = words("честно прямо вслух тут сразу уже сам сама")
    private val adjectiveEnd = Regex("(?:ый|ий|ой|ая|яя|ое|ее|ые|ие|ым|им|ую|юю|ого|его|ому|ему|ою|ей)$")
    private val nominalHeads = words("кусок куча мешок")
    private val selfNominals = words("человек игрок")
    private val samePredicates = words("такой такая такое такие")

    fun ignoredTokens(tokenization: ToxicityMatcher.Tokenization): BooleanArray {
        val tokens = tokenization.tokens
        val ignored = BooleanArray(tokens.size)
        var index = 0
        while (index < tokens.size) {
            if (tokenization.quotedTokens.getOrElse(index) { false } || isReportedSpeech(tokens, index)) { index++; continue }
            if (tokens[index] == "я") {
                var head = index - 1
                while (head >= 0 && tokens[head] in modifiers) head--
                if (head >= 0 && tokens[head] !in otherSubjects && tokens[head] !in actionVerbs &&
                    tokens[head] !in joins && !tokenization.quotedTokens[head] &&
                    (head until index).none { tokenization.punctuationBetween.getOrElse(it) { false } } &&
                    (head == 0 || tokens[head - 1] in modifiers || tokens[head - 1] in neutralPrefix || tokens[head - 1] == "и")) {
                    var reverseStart = head
                    while (reverseStart > 0 && tokens[reverseStart - 1] in modifiers) reverseStart--
                    while (reverseStart > 0 && (tokens[reverseStart - 1] in neutralPrefix || tokens[reverseStart - 1] == "и")) reverseStart--
                    ignored.fill(true, reverseStart, index + 1)
                }
            }
            val prefixEnd = selfPrefixEnd(tokens, index)
            if (prefixEnd == null) { index++; continue }
            if (hasOtherTargetOrAction(tokenization, prefixEnd, allowSelfNominal = true)) { index++; continue }
            val firstEnd = predicateEnd(tokenization, prefixEnd)
            if (firstEnd == null) { index++; continue }
            var end: Int = firstEnd
            while (end < tokens.size) {
                val join = tokens[end] in joins
                val comma = end > 0 && separator(tokenization, end - 1, end).contains(',')
                if (!join && !comma) break
                val nextStart = if (join) end + 1 else end
                if (hasOtherTargetOrAction(tokenization, nextStart)) break
                val next = predicateEnd(tokenization, nextStart, continued = true) ?: break
                end = next
            }
            val comparedWithAnother = end < tokens.size && tokens[end] in comparisons &&
                tokens.getOrNull(end + 1) !in neutralComparisons
            val otherAlso = (end until minOf(tokens.size, end + 5)).any { offset ->
                val other = tokens[offset] in otherSubjects || (tokens[offset] !in modifiers && tokens[offset] !in joins &&
                    tokens[offset] !in neutralPrefix && tokens[offset] !in selfVerbs && tokens[offset] != "я" && tokens[offset] != "себя")
                val shared = tokens.subList(offset + 1, minOf(tokens.size, offset + 4))
                other && ("тоже" in shared || shared.zipWithNext().any { (word, next) ->
                    (word in samePredicates && next == "же") || (word == "не" && next == "лучше") ||
                        (word == "еще" && next == "хуже")
                })
            }
            if (!comparedWithAnother && !otherAlso) {
                var start = index
                while (start > 0 && tokens[start - 1] in neutralPrefix && !tokenization.quotedTokens[start - 1]) start--
                ignored.fill(true, start, end)
            }
            index = end
        }
        return ignored
    }

    private fun selfPrefixEnd(tokens: List<String>, start: Int): Int? {
        if (tokens[start] == "я") {
            var next = start + 1
            while (tokens.getOrNull(next) in modifiers) next++
            if (tokens.getOrNull(next) == "себя") {
                next++
                if (tokens.getOrNull(next) in selfVerbs) next++
            } else if (tokens.getOrNull(next) in selfVerbs && tokens.getOrNull(next + 1) == "себя") {
                next += 2
            }
            return if (next == start + 1 || tokens.getOrNull(next - 1) in modifiers) start + 1 else next
        }
        if (tokens[start] in selfVerbs && tokens.getOrNull(start + 1) == "себя") {
            // Прошедшее время без явного «я» не задает говорящего
            if (tokens[start] !in presentSelfVerbs) return null
            return start + 2
        }
        return null
    }

    private fun predicateEnd(tokenization: ToxicityMatcher.Tokenization, start: Int, continued: Boolean = false): Int? {
        val tokens = tokenization.tokens
        var end = start
        while (end < tokens.size && isModifier(tokenization, end, continued) && !tokenization.quotedTokens[end]) end++
        if (end == tokens.size) return if (end > start) end else null
        val selfNominal = !continued && isSelfNominal(tokenization, start, end)
        if (tokenization.quotedTokens[end] || (tokens[end] in otherSubjects && !selfNominal) || tokens[end] in actionVerbs ||
            tokens[end] in joins || tokens[end] in comparisons || tokens[end] == "я" || tokens[end] == "себя") return if (end > start) end else null
        if (continued && tokenization.properNames[end]) return null
        val head = tokens[end++]
        if (head in nominalHeads) {
            while (end < tokens.size && isModifier(tokenization, end, continued) && !tokenization.quotedTokens[end]) end++
            if (end < tokens.size && !tokenization.quotedTokens[end] && tokens[end] !in otherSubjects &&
                tokens[end] !in joins && tokens[end] !in comparisons) end++
        }
        // Продолжение с двумя содержательными словами может задавать чужой субъект
        if (continued && end < tokens.size && tokens[end] !in joins &&
            !separator(tokenization, end - 1, end).any { it in ",.!?;\n" }) return null
        while (end < tokens.size && isModifier(tokenization, end, continued) && !tokenization.quotedTokens[end] &&
            !separator(tokenization, end - 1, end).any { it in ",.!?;\n" }) end++
        return end
    }

    private fun isModifier(tokenization: ToxicityMatcher.Tokenization, index: Int, continued: Boolean = false): Boolean {
        val token = tokenization.tokens[index]
        // В продолжении окончание чужого имени не задаёт self определение
        return token in modifiers || (!continued && !tokenization.properNames[index] && adjectiveEnd.containsMatchIn(token))
    }

    private fun separator(tokenization: ToxicityMatcher.Tokenization, left: Int, right: Int): String =
        tokenization.source.substring(tokenization.spans[left].last + 1, tokenization.spans[right].first)

    private fun isSelfNominal(tokenization: ToxicityMatcher.Tokenization, start: Int, index: Int): Boolean =
        index > start && tokenization.tokens[index] in selfNominals &&
            (start until index).all { isModifier(tokenization, it) } &&
            (start until index).any { adjectiveEnd.containsMatchIn(tokenization.tokens[it]) }

    private fun hasOtherTargetOrAction(tokenization: ToxicityMatcher.Tokenization, start: Int, allowSelfNominal: Boolean = false): Boolean {
        val tokens = tokenization.tokens
        var prefixEnd = start
        while (tokens.getOrNull(prefixEnd) in modifiers) prefixEnd++
        for (index in start until tokens.size) {
            if (index > start && (tokens[index] in joins || separator(tokenization, index - 1, index).any { it in ",.!?;\n" })) break
            if (index >= prefixEnd && (tokens[index] in otherSubjects || tokens[index] in actionVerbs) &&
                !(allowSelfNominal && isSelfNominal(tokenization, start, index))) return true
        }
        return false
    }

    private fun isReportedSpeech(tokens: List<String>, index: Int): Boolean {
        for (verb in index - 1 downTo maxOf(0, index - 8)) {
            if (tokens[verb] !in reportedVerbs) continue
            var subject = verb - 1
            while (subject >= 0 && tokens[subject] in reportingModifiers) subject--
            return tokens.getOrNull(subject) != "я"
        }
        return false
    }

    fun remainingText(message: String): String? {
        val tokenization = ToxicityMatcher.tokenizeDetailed(message)
        val ignored = ignoredTokens(tokenization)
        if (ignored.none { it }) return null
        // Для модели сохраняем исходные символы оставшегося текста, без lexical folding
        val result = StringBuilder(tokenization.source.length)
        var previous = 0
        for (index in ignored.indices) {
            if (!ignored[index]) continue
            val span = tokenization.spans[index]
            result.append(tokenization.source, previous, span.first).append(' ')
            previous = span.last + 1
        }
        result.append(tokenization.source, previous, tokenization.source.length)
        return result.toString()
    }

    fun isSelfOnly(message: String): Boolean {
        val tokenization = ToxicityMatcher.tokenizeDetailed(message)
        val ignored = ignoredTokens(tokenization)
        return ignored.isNotEmpty() && ignored.all { it }
    }
}
