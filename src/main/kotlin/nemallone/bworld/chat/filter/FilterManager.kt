package nemallone.bworld.chat.filter

import nemallone.bworld.chat.PupsChat
import nemallone.bworld.chat.deserializeChecked
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

internal class FilterManager(
    private val plugin: PupsChat,
    private val violationManager: ViolationManager
) {

    private val miniMessage = MiniMessage.miniMessage()

    private val lastMessages = ConcurrentHashMap<UUID, ArrayDeque<CachedMessage>>()
    private val adMessageHistory = ConcurrentHashMap<UUID, ArrayDeque<CachedMessage>>()

    private data class CachedMessage(val text: String, val time: Long)

    private enum class Check(val configKey: String, val defaultName: String) {
        ADS("ads", "Реклама"),
        WORDS("words", "Запрещённые слова"),
        SYMBOLS("symbols", "Символы"),
        CHARACTERS("characters", "Недопустимые символы"),
        RANDOM_CHARS("random-chars", "Случайные символы"),
        SPAM("spam", "Спам")
    }

    private class Settings(
        val capsEnabled: Boolean,
        val capsMax: Int,
        val charsEnabled: Boolean,
        val allowedEmojis: List<String>,
        val randomCharsEnabled: Boolean,
        val repeatedCharsPattern: Pattern,
        val randomNumbersPattern: Pattern,
        val spamEnabled: Boolean,
        val spamSimilarity: Double,
        val spamHistorySeconds: Long,
        val spamHistoryMessages: Int,
        val adsEnabled: Boolean,
        val adsHistoryMessages: Int,
        val adsHistorySeconds: Long,
        val advertisementMatcher: AdvertisementMatcher,
        val wordsEnabled: Boolean,
        val symbolsEnabled: Boolean,
        val symbolsMax: Int,
        val symbolsCharSet: Set<Char>,
        val playtimeEnabled: Boolean,
        val playtimeMinMinutes: Int,
        val playtimeTemplate: String,
        val alertsTemplate: String,
        val replacementsEnabled: Boolean,
        val replacementMapLower: Map<String, String>,
        val charsComponent: Component,
        val spamComponent: Component,
        val adsComponent: Component,
        val wordsComponent: Component,
        val symbolsComponent: Component,
        val randomCharsComponent: Component,
        val blockedWordsLower: List<String>,
        val checkNames: Map<Check, String>
    )

    private companion object {
        private const val DEFAULT_CHARACTERS_MESSAGE =
            "<color:#FF638F>Найдены недопустимые символы"
        private const val DEFAULT_RANDOM_CHARS_MESSAGE =
            "<color:#FF638F>Обнаружен бессмысленный набор символов"
        private const val DEFAULT_SPAM_MESSAGE =
            "<color:#FF638F>Не повторяйте сообщения"
        private const val DEFAULT_ADS_MESSAGE =
            "<color:#FF638F>Не отправляйте рекламу"
        private const val DEFAULT_WORDS_MESSAGE =
            "<color:#FF638F>Обнаружено запрещённое слово"
        private const val DEFAULT_SYMBOLS_MESSAGE =
            "<color:#FF638F>Слишком много спецсимволов в сообщении"
        private const val DEFAULT_PLAYTIME_MESSAGE =
            "<color:#FF638F>Наиграйте минимум %minutes% минут для использования чата"
        private const val DEFAULT_ALERT_FORMAT =
            "<color:#FCD05C>[ғɪʟᴛᴇʀ] <gray>{player} <white>{check} " +
                "<color:#FF638F>✘ <white>x{violations} <color:#FCD05C>{message}"
        private val NAME_CANDIDATE_PATTERN = Pattern.compile("(?<![\\p{L}\\p{N}_])@?[A-Za-z0-9_]{3,16}(?![\\p{L}\\p{N}_])")
        private const val PUNCT = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

        private fun isAllowedChar(c: Char): Boolean {
            if (c in 'a'..'z' || c in 'A'..'Z') return true
            if (c in 'а'..'я' || c in 'А'..'Я') return true
            if (c in '0'..'9') return true
            if (c.isWhitespace()) return true
            if (c == 'ё' || c == 'Ё') return true
            if (c == 'і' || c == 'І' || c == 'ї' || c == 'Ї') return true
            if (c == 'є' || c == 'Є' || c == 'ґ' || c == 'Ґ') return true
            return PUNCT.indexOf(c) >= 0
        }
    }

    private val onlinePlayerNamesLower = ConcurrentHashMap<String, String>()
    private val playerNamePatterns = ConcurrentHashMap<String, Pattern>()

    private data class PlaytimeSession(val joinPlayTicks: Long, val joinTimeMs: Long)

    private val playtimeCache = ConcurrentHashMap<UUID, PlaytimeSession>()

    fun registerPlayerPlaytime(uuid: UUID, initialTicks: Long) {
        playtimeCache[uuid] = PlaytimeSession(initialTicks, System.currentTimeMillis())
    }

    fun addPlayerName(name: String) {
        onlinePlayerNamesLower[name.lowercase(Locale.ROOT)] = name
        playerNamePatterns.computeIfAbsent(name) { createPlayerNamePattern(it) }
    }

    fun findExactPlayerName(name: String): String? = onlinePlayerNamesLower[name.lowercase(Locale.ROOT)]

    @Volatile
    private var settings = readSettings()

    init {
        refreshPlayerNames()
    }

    fun loadConfig() {
        settings = readSettings()
        refreshPlayerNames()
    }

    private fun readSettings(): Settings {
        val config = plugin.config

        val capsEnabled = config.getBoolean("caps.enabled", true)
        val capsMax = config.getInt("caps.max-caps", 6).coerceAtLeast(1)

        val charsEnabled = config.getBoolean("characters.enabled", true)
        val allowedEmojis = config.getStringList("characters.allowed-emojis").toList()
        val charsMessage = config.getString("characters.message")
            ?: DEFAULT_CHARACTERS_MESSAGE

        val randomCharsEnabled = config.getBoolean("random-chars.enabled", true)
        val repeatedThreshold = config.getInt("random-chars.repeated-character-threshold", 6)
            .coerceIn(2, 100)
        val integerThreshold = config.getInt("random-chars.integer-digits-threshold", 6)
            .coerceIn(2, 100)
        val decimalThreshold = config.getInt("random-chars.decimal-digits-threshold", 5)
            .coerceIn(2, 100)
        val repeatedCharsPattern = Pattern.compile(
            "(?<ch>.)\\k<ch>{${repeatedThreshold - 1},}"
        )
        val randomNumbersPattern = Pattern.compile(
            "\\b0\\.\\d{$decimalThreshold,}\\b|\\b\\d{$integerThreshold,}\\b"
        )
        val randomCharsMessage = config.getString("random-chars.message")
            ?: DEFAULT_RANDOM_CHARS_MESSAGE

        val spamEnabled = config.getBoolean("spam.enabled", true)
        val spamSimilarity = config.getDouble("spam.similarity-percent", 80.0)
            .coerceIn(0.0, 100.0)
        val spamHistorySeconds = config.getLong("spam.history-seconds", 60L).coerceAtLeast(1L)
        val spamHistoryMessages = config.getInt("spam.history-messages", 2).coerceIn(1, 20)
        val spamMessage = config.getString("spam.message") ?: DEFAULT_SPAM_MESSAGE

        val adsEnabled = config.getBoolean("ads.enabled", true)
        val blockedDomains = config.getStringList("ads.blocked-domains")
        val allowedLinks = config.getStringList("ads.allowed-links")
        val adsMessage = config.getString("ads.message") ?: DEFAULT_ADS_MESSAGE
        val adsHistoryMessages = config.getInt("ads.history-messages", 3).coerceIn(1, 10)
        val adsHistorySeconds = config.getLong("ads.history-seconds", 60L).coerceAtLeast(1L)
        val advertisementMatcher = AdvertisementMatcher(blockedDomains, allowedLinks)

        val wordsEnabled = config.getBoolean("words.enabled", true)
        val blockedWords = config.getStringList("words.blocked-words").filter { it.isNotEmpty() }
        val wordsMessage = config.getString("words.message") ?: DEFAULT_WORDS_MESSAGE

        val symbolsEnabled = config.getBoolean("symbols.enabled", true)
        val symbolsMax = config.getInt("symbols.max-symbols", 7).coerceAtLeast(0)
        val configuredSymbols = config.getString("symbols.chars")
            ?: ".,!@#$%^&*(){}[]<>|~;:+-/\\=_"
        val symbolsCharSet = configuredSymbols.toSet()
        val symbolsMessage = config.getString("symbols.message") ?: DEFAULT_SYMBOLS_MESSAGE

        val playtimeEnabled = config.getBoolean("playtime.enabled", true)
        val playtimeMinMinutes = config.getInt("playtime.min-minutes", 5).coerceAtLeast(0)
        val playtimeTemplate = preparePlaytimeTemplate(
            config.getString("playtime.message") ?: DEFAULT_PLAYTIME_MESSAGE
        )

        val alertsTemplate = prepareAlertTemplate(
            config.getString("alerts.format") ?: DEFAULT_ALERT_FORMAT
        )

        val replacementsEnabled = config.getBoolean("replacements.enabled", true)
        val replacements = config.getConfigurationSection("replacements.words")
        val replacementMapLower = replacements?.let { section ->
            section.getKeys(false).associate { key ->
                key.lowercase(Locale.ROOT) to (section.getString(key) ?: key)
            }
        } ?: emptyMap()

        val charsComponent = parseFilterComponent(charsMessage, "characters.message")
        val spamComponent = parseFilterComponent(spamMessage, "spam.message")
        val adsComponent = parseFilterComponent(adsMessage, "ads.message")
        val wordsComponent = parseFilterComponent(wordsMessage, "words.message")
        val symbolsComponent = parseFilterComponent(symbolsMessage, "symbols.message")
        val randomCharsComponent = parseFilterComponent(randomCharsMessage, "random-chars.message")
        val blockedWordsLower = blockedWords.map { it.lowercase(Locale.ROOT) }
        val checkNames = Check.entries.associateWith { check ->
            config.getString("alerts.check-names.${check.configKey}", check.defaultName)
                ?: check.defaultName
        }

        return Settings(
            capsEnabled = capsEnabled,
            capsMax = capsMax,
            charsEnabled = charsEnabled,
            allowedEmojis = allowedEmojis,
            randomCharsEnabled = randomCharsEnabled,
            repeatedCharsPattern = repeatedCharsPattern,
            randomNumbersPattern = randomNumbersPattern,
            spamEnabled = spamEnabled,
            spamSimilarity = spamSimilarity,
            spamHistorySeconds = spamHistorySeconds,
            spamHistoryMessages = spamHistoryMessages,
            adsEnabled = adsEnabled,
            adsHistoryMessages = adsHistoryMessages,
            adsHistorySeconds = adsHistorySeconds,
            advertisementMatcher = advertisementMatcher,
            wordsEnabled = wordsEnabled,
            symbolsEnabled = symbolsEnabled,
            symbolsMax = symbolsMax,
            symbolsCharSet = symbolsCharSet,
            playtimeEnabled = playtimeEnabled,
            playtimeMinMinutes = playtimeMinMinutes,
            playtimeTemplate = playtimeTemplate,
            alertsTemplate = alertsTemplate,
            replacementsEnabled = replacementsEnabled,
            replacementMapLower = replacementMapLower,
            charsComponent = charsComponent,
            spamComponent = spamComponent,
            adsComponent = adsComponent,
            wordsComponent = wordsComponent,
            symbolsComponent = symbolsComponent,
            randomCharsComponent = randomCharsComponent,
            blockedWordsLower = blockedWordsLower,
            checkNames = checkNames
        )
    }

    private fun refreshPlayerNames() {
        onlinePlayerNamesLower.clear()
        playerNamePatterns.clear()
        for (player in Bukkit.getOnlinePlayers()) {
            addPlayerName(player.name)
        }
    }

    fun clearPlayer(uuid: UUID, name: String) {
        lastMessages.remove(uuid)
        adMessageHistory.remove(uuid)
        onlinePlayerNamesLower.remove(name.lowercase(Locale.ROOT))
        playerNamePatterns.remove(name)
        playtimeCache.remove(uuid)
    }

    fun applyReplacements(message: String): String {
        val current = settings
        if (!current.replacementsEnabled || current.replacementMapLower.isEmpty()) return message
        val words = message.split(" ")
        val replacedWords = words.map { word ->
            current.replacementMapLower[word.lowercase(Locale.ROOT)] ?: word
        }
        return replacedWords.joinToString(" ")
    }

    fun filterMessage(player: Player, message: String, privateContext: String?): FilterResult {
        val current = settings
        if (checkPlaytime(player, current)) return FilterResult.Blocked

        val failedCheck = when {
            checkAds(player, message, current) -> {
                player.sendMessage(current.adsComponent)
                Check.ADS
            }

            checkWords(player, message, current) -> {
                player.sendMessage(current.wordsComponent)
                Check.WORDS
            }

            checkExcessiveSymbols(player, message, current) -> {
                player.sendMessage(current.symbolsComponent)
                Check.SYMBOLS
            }

            checkChars(player, message, current) -> {
                player.sendMessage(current.charsComponent)
                Check.CHARACTERS
            }

            checkRandomChars(player, message, current) -> {
                player.sendMessage(current.randomCharsComponent)
                Check.RANDOM_CHARS
            }

            checkSpam(player, message, current) -> {
                player.sendMessage(current.spamComponent)
                Check.SPAM
            }

            else -> null
        }

        if (failedCheck != null) {
            val violations = violationManager.addViolation(player)
            sendAlert(player, failedCheck, violations, message, privateContext, current)
            return FilterResult.Blocked
        }

        val filteredMessage = if (checkCaps(player, message, current)) {
            message.lowercase(Locale.ROOT)
        } else {
            message
        }

        val history = lastMessages.computeIfAbsent(player.uniqueId) { ArrayDeque() }

        val similarityCheckMessage = current.randomNumbersPattern.matcher(filteredMessage).replaceAll("")

        synchronized(history) {
            val now = System.currentTimeMillis()
            history.addLast(CachedMessage(similarityCheckMessage.lowercase(Locale.ROOT), now))
            discardExpired(history, now, current.spamHistorySeconds)
            while (history.size > current.spamHistoryMessages) history.removeFirst()
        }

        return if (filteredMessage != message) {
            FilterResult.Modified(filteredMessage)
        } else {
            FilterResult.Allowed
        }
    }

    private fun checkPlaytime(player: Player, current: Settings): Boolean {
        if (!current.playtimeEnabled || player.hasPermission("pupschat.bypass.playtime")) return false

        val session = playtimeCache[player.uniqueId]
        val ticks = if (session != null) {
            session.joinPlayTicks + (System.currentTimeMillis() - session.joinTimeMs) / 50L
        } else {
            0L
        }
        val required = current.playtimeMinMinutes * 60L * 20L
        if (ticks < required) {
            player.sendMessage(
                miniMessage.deserialize(
                    current.playtimeTemplate,
                    Placeholder.unparsed(
                        "pupschat_minutes",
                        current.playtimeMinMinutes.toString()
                    )
                )
            )
            return true
        }
        return false
    }

    private fun checkCaps(player: Player, message: String, current: Settings): Boolean {
        if (!current.capsEnabled || player.hasPermission("pupschat.bypass.caps")) return false
        val cleaned = stripPlayerNames(message)
        return cleaned.count(Char::isUpperCase) >= current.capsMax
    }

    private fun checkChars(player: Player, message: String, current: Settings): Boolean {
        if (!current.charsEnabled || player.hasPermission("pupschat.bypass.characters")) return false

        var withoutAllowedEmojis = message
        for (emoji in current.allowedEmojis) {
            withoutAllowedEmojis = withoutAllowedEmojis.replace(emoji, "")
        }
        withoutAllowedEmojis = withoutAllowedEmojis
            .replace("\uFE0F", "")
            .replace("\uFE0E", "")

        for (character in withoutAllowedEmojis) {
            if (!isAllowedChar(character)) return true
        }
        return false
    }

    private fun checkRandomChars(player: Player, message: String, current: Settings): Boolean {
        return current.randomCharsEnabled &&
            !player.hasPermission("pupschat.bypass.randomchars") &&
            (current.repeatedCharsPattern.matcher(message).find() ||
                current.randomNumbersPattern.matcher(message).find())
    }

    private fun checkSpam(player: Player, message: String, current: Settings): Boolean {
        if (!current.spamEnabled || player.hasPermission("pupschat.bypass.spam")) return false
        val history = lastMessages[player.uniqueId] ?: return false

        val similarityCheckMessage = current.randomNumbersPattern.matcher(
            message.lowercase(Locale.ROOT)
        ).replaceAll("")

        val now = System.currentTimeMillis()
        synchronized(history) {
            for (last in history) {
                if (now - last.time > current.spamHistorySeconds * 1_000L) continue
                if (
                    calculateSimilarity(similarityCheckMessage, last.text) * 100 >=
                    current.spamSimilarity
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun checkAds(player: Player, message: String, current: Settings): Boolean {
        if (!current.adsEnabled || player.hasPermission("pupschat.bypass.ads")) return false

        val history = adMessageHistory.computeIfAbsent(player.uniqueId) { ArrayDeque() }
        val now = System.currentTimeMillis()

        synchronized(history) {
            discardExpired(history, now, current.adsHistorySeconds)

            val combined = buildString {
                for (cached in history) append(cached.text)
                append(message)
            }
            val detected = current.advertisementMatcher.contains(message) ||
                (history.isNotEmpty() && current.advertisementMatcher.contains(combined))

            if (detected) {
                history.clear()
                adMessageHistory.remove(player.uniqueId, history)
            } else {
                history.addLast(CachedMessage(message, now))
                val maxPreviousMessages = (current.adsHistoryMessages - 1).coerceAtLeast(0)
                while (history.size > maxPreviousMessages) history.removeFirst()
                if (maxPreviousMessages == 0) adMessageHistory.remove(player.uniqueId, history)
            }
            return detected
        }
    }

    private fun discardExpired(
        history: ArrayDeque<CachedMessage>,
        now: Long,
        lifetimeSeconds: Long
    ) {
        val lifetimeMs = lifetimeSeconds * 1_000L
        while (history.isNotEmpty() && now - history.first().time > lifetimeMs) {
            history.removeFirst()
        }
    }

    private fun checkWords(player: Player, message: String, current: Settings): Boolean {
        if (!current.wordsEnabled || player.hasPermission("pupschat.bypass.words")) return false
        val lowercaseMessage = message.lowercase(Locale.ROOT)
        return current.blockedWordsLower.any { lowercaseMessage.contains(it) }
    }

    private fun checkExcessiveSymbols(
        player: Player,
        message: String,
        current: Settings
    ): Boolean {
        return current.symbolsEnabled &&
            !player.hasPermission("pupschat.bypass.symbols") &&
            message.count { it in current.symbolsCharSet } > current.symbolsMax
    }

    private fun stripPlayerNames(message: String): String {
        var messageWithoutNames = message
        val tokens = NAME_CANDIDATE_PATTERN.matcher(message)
        val matchedNames = HashSet<String>()
        while (tokens.find()) {
            val token = tokens.group().removePrefix("@")
            val exact = onlinePlayerNamesLower[token.lowercase(Locale.ROOT)]
            if (exact != null) matchedNames.add(exact)
        }
        for (name in matchedNames) {
            val pattern = playerNamePatterns.computeIfAbsent(name) { createPlayerNamePattern(it) }
            messageWithoutNames = pattern.matcher(messageWithoutNames).replaceAll("")
        }
        return messageWithoutNames
    }

    private fun createPlayerNamePattern(name: String): Pattern = Pattern.compile(
        "(?i)(?<![\\p{L}\\p{N}_])@?${Pattern.quote(name)}(?![\\p{L}\\p{N}_])"
    )

    private fun parseFilterComponent(message: String, path: String): Component = try {
        miniMessage.deserializeChecked(message)
    } catch (exception: RuntimeException) {
        plugin.logger.warning("Некорректный MiniMessage в $path: ${exception.message}")
        miniMessage.deserialize("<color:#FF638F>Сообщение заблокировано фильтром")
    }

    private fun sendAlert(
        player: Player,
        check: Check,
        violations: Int,
        message: String,
        privateContext: String?,
        current: Settings
    ) {
        val template = if (privateContext.isNullOrEmpty()) current.alertsTemplate
        else "${current.alertsTemplate} <gray>(<pupschat_context>)"

        val component = miniMessage.deserialize(
            template,
            Placeholder.unparsed("pupschat_player", player.name),
            Placeholder.unparsed(
                "pupschat_check",
                current.checkNames[check] ?: check.defaultName
            ),
            Placeholder.unparsed("pupschat_violations", violations.toString()),
            Placeholder.unparsed("pupschat_message", message),
            Placeholder.unparsed("pupschat_context", privateContext.orEmpty())
        )

        for (staff in Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("pupschat.alerts")) staff.sendMessage(component)
        }
        Bukkit.getConsoleSender().sendMessage(component)
    }

    private fun preparePlaytimeTemplate(message: String): String {
        val template = message.replace("%minutes%", "<pupschat_minutes>")
        return try {
            miniMessage.deserializeChecked(
                template,
                Placeholder.unparsed("pupschat_minutes", "5")
            )
            template
        } catch (exception: RuntimeException) {
            plugin.logger.warning(
                "Некорректный MiniMessage в playtime.message: ${exception.message}"
            )
            DEFAULT_PLAYTIME_MESSAGE.replace("%minutes%", "<pupschat_minutes>")
        }
    }

    private fun prepareAlertTemplate(message: String): String {
        fun replacePlaceholders(input: String): String = input
            .replace("{player}", "<pupschat_player>")
            .replace("{check}", "<pupschat_check>")
            .replace("{violations}", "<pupschat_violations>")
            .replace("{message}", "<pupschat_message>")

        val template = replacePlaceholders(message)
        return try {
            miniMessage.deserializeChecked(
                template,
                Placeholder.unparsed("pupschat_player", "Player"),
                Placeholder.unparsed("pupschat_check", "Проверка"),
                Placeholder.unparsed("pupschat_violations", "1"),
                Placeholder.unparsed("pupschat_message", "message")
            )
            template
        } catch (exception: RuntimeException) {
            plugin.logger.warning(
                "Некорректный MiniMessage в alerts.format: ${exception.message}"
            )
            replacePlaceholders(DEFAULT_ALERT_FORMAT)
        }
    }

    private fun calculateSimilarity(first: String, second: String): Double {
        if (first == second) return 1.0
        val firstLength = first.length
        val secondLength = second.length
        if (firstLength == 0 || secondLength == 0) return 0.0

        var previousRow = IntArray(secondLength + 1) { it }
        var currentRow = IntArray(secondLength + 1)

        for (firstIndex in 1..firstLength) {
            currentRow[0] = firstIndex
            for (secondIndex in 1..secondLength) {
                val cost = if (first[firstIndex - 1] == second[secondIndex - 1]) 0 else 1
                currentRow[secondIndex] = minOf(
                    previousRow[secondIndex] + 1,
                    currentRow[secondIndex - 1] + 1,
                    previousRow[secondIndex - 1] + cost
                )
            }
            val completedRow = previousRow
            previousRow = currentRow
            currentRow = completedRow
        }

        return 1.0 - previousRow[secondLength].toDouble() /
            maxOf(firstLength, secondLength)
    }
}
