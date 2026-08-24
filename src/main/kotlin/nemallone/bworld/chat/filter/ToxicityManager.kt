package nemallone.bworld.chat.filter

import nemallone.bworld.chat.PupsChat
import nemallone.bworld.chat.deserializeChecked
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

private enum class ToxicityAction(val configValue: String) {
    WARN("warn"),
    BLOCK("block");

    companion object {
        fun fromConfig(value: String): ToxicityAction? {
            return entries.firstOrNull { it.configValue.equals(value, ignoreCase = true) }
        }
    }
}

internal class ToxicityManager(
    private val plugin: PupsChat,
    private val muteManager: MuteManager
) {

    private val miniMessage = MiniMessage.miniMessage()
    private val lexiconStore = ToxicityLexiconStore(
        rulesFile = File(plugin.dataFolder, "toxicity.txt"),
        bundledRules = { plugin.getResource("toxicity.txt") }
    )
    private val violations = ConcurrentHashMap<UUID, ArrayDeque<Long>>()

    @Volatile
    private var enabled = true

    @Volatile
    private var action = ToxicityAction.WARN

    @Volatile
    private var maxViolations = 10

    @Volatile
    private var windowMs = 10 * 60_000L

    @Volatile
    private var warningMessages: List<Component> = emptyList()

    internal val isReady: Boolean

    init {
        isReady = loadConfig(initialLoad = true)

        if (isReady) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable {
                val now = System.currentTimeMillis()
                for (uuid in violations.keys) {
                    violations.computeIfPresent(uuid) { _, timestamps ->
                        synchronized(timestamps) {
                            removeExpired(timestamps, now)
                            if (timestamps.isEmpty()) null else timestamps
                        }
                    }
                }
            }, CLEANUP_INTERVAL_TICKS, CLEANUP_INTERVAL_TICKS)
        }
    }

    fun loadConfig(): Boolean = loadConfig(initialLoad = false)

    private fun loadConfig(initialLoad: Boolean): Boolean {
        val config = plugin.config
        enabled = config.getBoolean("toxicity.enabled", true)
        val configuredAction = config.getString(
            "toxicity.action",
            ToxicityAction.WARN.configValue
        ) ?: ToxicityAction.WARN.configValue
        val parsedAction = ToxicityAction.fromConfig(configuredAction)
        if (parsedAction == null) {
            plugin.logger.warning(
                "Некорректное значение toxicity.action: $configuredAction; используется warn"
            )
        }
        action = parsedAction ?: ToxicityAction.WARN
        maxViolations = config.getInt("toxicity.max-violations", 10).coerceAtLeast(1)
        windowMs = config.getLong("toxicity.window-minutes", 10).coerceAtLeast(1) * 60_000L

        val configuredWarnings = config.getStringList("toxicity.messages")
        val warnings = configuredWarnings.ifEmpty {
            listOf("<color:#FF638F>Это всего лишь игра. Относитесь к другим игрокам спокойнее.")
        }
        val preparedWarnings = warnings.mapNotNull { message ->
            try {
                miniMessage.deserializeChecked(message)
            } catch (_: RuntimeException) {
                plugin.logger.warning("Некорректное сообщение toxicity.messages: $message")
                null
            }
        }
        warningMessages = preparedWarnings.ifEmpty {
            listOf(
                miniMessage.deserialize(
                    "<color:#FF638F>Это всего лишь игра. Относитесь к другим игрокам спокойнее."
                )
            )
        }

        val allowedWords = config.getStringList("toxicity.allowed-words")
        if (initialLoad) {
            return when (
                val result = lexiconStore.loadInitial(allowedWords, plugin.logger::warning)
            ) {
                InitialLexiconLoadResult.LoadedFromFile -> true
                is InitialLexiconLoadResult.LoadedFromFallback -> {
                    plugin.logger.log(
                        Level.WARNING,
                        "Не удалось загрузить внешний toxicity.txt; используется встроенный словарь",
                        result.fileFailure
                    )
                    true
                }
                is InitialLexiconLoadResult.Failed -> {
                    plugin.logger.log(
                        Level.SEVERE,
                        "Не удалось загрузить внешний toxicity.txt",
                        result.fileFailure
                    )
                    plugin.logger.log(
                        Level.SEVERE,
                        "Не удалось загрузить встроенный toxicity.txt; PupsChat будет отключён",
                        result.fallbackFailure
                    )
                    false
                }
            }
        }

        val failure = lexiconStore.reload(allowedWords, plugin.logger::warning)
        if (failure != null) {
            plugin.logger.log(
                Level.WARNING,
                "Не удалось загрузить toxicity.txt; используется предыдущий словарь",
                failure
            )
            return false
        }
        return true
    }

    fun checkMessage(player: Player, message: String): FilterResult {
        if (!enabled || player.hasPermission("pupschat.bypass.toxicity")) {
            return FilterResult.Allowed
        }
        if (!lexiconStore.containsToxicity(message)) return FilterResult.Allowed

        val shouldMute = addViolation(player.uniqueId)
        val warning = warningMessages.random()

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline) {
                if (shouldMute) muteManager.mute(player)
                return@Runnable
            }

            player.sendMessage(warning)

            if (shouldMute) muteManager.mute(player)
        }, 2L)

        return if (action == ToxicityAction.BLOCK) {
            FilterResult.Blocked
        } else {
            FilterResult.Allowed
        }
    }

    private fun addViolation(uuid: UUID): Boolean {
        val now = System.currentTimeMillis()
        var shouldMute = false

        violations.compute(uuid) { _, existing ->
            val timestamps = existing ?: ArrayDeque()
            synchronized(timestamps) {
                removeExpired(timestamps, now)
                timestamps.addLast(now)
                if (timestamps.size >= maxViolations) {
                    shouldMute = true
                    timestamps.clear()
                }
            }
            timestamps
        }

        return shouldMute
    }

    private fun removeExpired(timestamps: ArrayDeque<Long>, now: Long) {
        while (timestamps.isNotEmpty() && now - timestamps.first() > windowMs) {
            timestamps.removeFirst()
        }
    }

    private companion object {
        const val CLEANUP_INTERVAL_TICKS = 20L * 60
    }
}
