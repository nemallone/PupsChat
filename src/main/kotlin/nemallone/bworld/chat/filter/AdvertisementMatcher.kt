package nemallone.bworld.chat.filter

import java.util.Locale
import java.util.regex.Pattern

internal class AdvertisementMatcher(
    blockedDomains: List<String>,
    allowedLinks: List<String>
) {
    private val blockedDomainNames = blockedDomains.asSequence()
        .map(String::trim)
        .map { it.removePrefix(".").lowercase(Locale.ROOT) }
        .filter(String::isNotEmpty)
        .toList()

    private val blockedDomainSet = blockedDomainNames.toHashSet()
    private val maxBlockedDomainLength = blockedDomainNames.maxOfOrNull(String::length) ?: 0
    private val domainPatterns = blockedDomainNames.map(::createDomainPattern)

    private val allowedLinkPatterns = allowedLinks.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(::normalizeForMatching)
        .map { link ->
            Pattern.compile(
                "(?<![\\p{L}\\p{N}-])${Pattern.quote(link)}(?![\\p{L}\\p{N}-])",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            )
        }
        .toList()

    fun contains(message: String): Boolean {
        val candidate = normalizeForMatching(message)
        val allowedRanges = findAllowedRanges(candidate)

        if (containsIpAddress(candidate, allowedRanges)) return true
        if (containsBlockedDomain(candidate, allowedRanges)) return true
        return containsUnderscoredDomain(candidate, allowedRanges)
    }

    private fun findAllowedRanges(input: String): List<IntRange> = buildList {
        for (pattern in allowedLinkPatterns) {
            val matcher = pattern.matcher(input)
            while (matcher.find()) add(matcher.start() until matcher.end())
        }
    }

    private fun createDomainPattern(domain: String): Pattern {
        val separatedDomain = separateCharacters(domain)
        return Pattern.compile(
            "(?<![\\p{L}\\p{N}-])" +
                "(?:[a-z0-9-][\\s_-]*+){2,63}" +
                "[.,_][\\s_-]*+" +
                separatedDomain +
                "(?![\\p{L}\\p{N}-])",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    }

    private fun separateCharacters(value: String): String {
        return value.codePoints()
            .toArray()
            .joinToString("[\\s._,-]*+") { codePoint ->
                Pattern.quote(String(Character.toChars(codePoint)))
            }
    }

    private fun normalizeHomoglyphs(input: String): String {
        val normalized = StringBuilder(input.length)
        for (character in input) normalized.append(HOMOGLYPHS[character] ?: character)
        return normalized.toString()
    }

    private fun normalizeForMatching(input: String): String {
        val lowercase = input.lowercase(Locale.ROOT)
        val compactWhitespace = WHITESPACE_PATTERN.matcher(lowercase).replaceAll(" ").trim()
        val normalized = normalizeHomoglyphs(compactWhitespace)
        val normalizedSlashes = SLASH_SEPARATOR.matcher(normalized).replaceAll(".")
        val normalizedDots = PARENTHESIZED_SEPARATOR.matcher(normalizedSlashes).replaceAll(".")
        return UNDERSCORE_SEPARATOR.matcher(normalizedDots).replaceAll("_")
    }

    private fun containsBlockedDomain(input: String, allowedRanges: List<IntRange>): Boolean {
        for (pattern in domainPatterns) {
            val matcher = pattern.matcher(input)
            while (matcher.find()) {
                if (!isAllowed(matcher.start(), matcher.end(), allowedRanges)) return true
            }
        }
        return false
    }

    private fun containsUnderscoredDomain(input: String, allowedRanges: List<IntRange>): Boolean {
        var index = 0
        while (index < input.length) {
            while (index < input.length && !isDomainCharacter(input[index])) index++
            val start = index
            while (index < input.length && isDomainCharacter(input[index])) index++
            if (start == index) continue

            val candidate = input.substring(start, index)
            if ('_' !in candidate || !hasServerAddressShape(candidate)) continue
            if (!isAllowed(start, index, allowedRanges)) return true
        }
        return false
    }

    private fun hasServerAddressShape(candidate: String): Boolean {
        val labels = DOMAIN_SEPARATOR.split(candidate).filter(String::isNotEmpty)
        for (domainEnd in 2..labels.lastIndex) {
            val (hostLabels, domain) = findBlockedDomain(labels, domainEnd) ?: continue
            if (hasServerAddressShape(hostLabels, domain)) return true
        }
        return false
    }

    private fun hasServerAddressShape(hostLabels: List<String>, domain: String): Boolean {
        val serverIndex = hostLabels.indexOfFirst(SERVER_LABELS::contains)
        if (serverIndex < 0) return false
        val hasAddressLabel = hostLabels.any(ADDRESS_LABELS::contains)
        if (domain in WORDLIKE_DOMAINS) return hasAddressLabel
        if (serverIndex > 0) return true
        return hasAddressLabel
    }

    private fun findBlockedDomain(
        labels: List<String>,
        domainEnd: Int
    ): Pair<List<String>, String>? {
        var suffix = ""
        for (index in domainEnd downTo 0) {
            suffix = labels[index] + suffix
            if (suffix.length > maxBlockedDomainLength) return null
            if (index >= 2 && suffix in blockedDomainSet) {
                return labels.subList(0, index) to suffix
            }
        }
        return null
    }

    private fun isDomainCharacter(character: Char): Boolean {
        return character in 'a'..'z' || character in '0'..'9' ||
            character == '-' || character == '.' || character == '_' || character == ','
    }

    private fun containsIpAddress(input: String, allowedRanges: List<IntRange>): Boolean {
        val matcher = IP_PATTERN.matcher(input)
        while (matcher.find()) {
            if (isAllowed(matcher.start(), matcher.end(), allowedRanges)) continue

            val explicitSeparators = IP_SEPARATOR_GROUPS.all { group ->
                matcher.group(group).any { character -> character == '.' || character == ',' }
            }
            if (!explicitSeparators) continue

            val validOctets = IP_OCTET_GROUPS.all { group ->
                matcher.group(group).toInt() in 0..255
            }
            if (validOctets) return true
        }
        return false
    }

    private fun isAllowed(start: Int, end: Int, allowedRanges: List<IntRange>): Boolean {
        return allowedRanges.any { range -> start >= range.first && end - 1 <= range.last }
    }

    private companion object {
        val IP_PATTERN: Pattern = Pattern.compile(
            "(?<!\\d)(\\d{1,3})([.,\\s]+)(\\d{1,3})([.,\\s]+)" +
                "(\\d{1,3})([.,\\s]+)(\\d{1,3})(?!\\d)"
        )
        val IP_OCTET_GROUPS = intArrayOf(1, 3, 5, 7)
        val IP_SEPARATOR_GROUPS = intArrayOf(2, 4, 6)
        val WHITESPACE_PATTERN: Pattern = Pattern.compile("\\s+")
        val SLASH_SEPARATOR: Pattern = Pattern.compile("[/\\\\]+")
        val PARENTHESIZED_SEPARATOR: Pattern = Pattern.compile("\\(\\s*[.,]\\s*\\)")
        val UNDERSCORE_SEPARATOR: Pattern = Pattern.compile("\\s*_+\\s*")
        val DOMAIN_SEPARATOR: Pattern = Pattern.compile("[._,]+")
        val SERVER_LABELS = setOf("server", "srv")
        val ADDRESS_LABELS = setOf("mc", "pvp", "hub", "play", "www")
        val WORDLIKE_DOMAINS = setOf(
            "me", "fun", "online", "space", "shop", "site", "lol", "pro", "gg"
        )

        val HOMOGLYPHS = mapOf(
            'а' to 'a', 'в' to 'b', 'с' to 'c', 'е' to 'e', 'ё' to 'e',
            'н' to 'h', 'і' to 'i', 'к' to 'k', 'м' to 'm', 'о' to 'o',
            'р' to 'p', 'х' to 'x', 'у' to 'y', 'т' to 't'
        )
    }
}
