package nemallone.bworld.chat.logging

import io.papermc.paper.event.player.AsyncChatEvent
import nemallone.bworld.chat.PupsChat
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class ChatLogManager(private val plugin: PupsChat) : Listener {

    private data class PlayerIdentity(val name: String, val ip: String)

    private val plainSerializer = PlainTextComponentSerializer.plainText()
    private val identities = ConcurrentHashMap<UUID, PlayerIdentity>()
    private val store: ChatLogStore

    @Volatile
    private var enabled = true

    @Volatile
    private var maxEntryLength = DEFAULT_MAX_ENTRY_LENGTH

    @Volatile
    private var sensitiveCommands = DEFAULT_SENSITIVE_COMMANDS

    init {
        val options = readOptions()
        enabled = options.enabled
        reloadLocalOptions()
        store = ChatLogStore(
            plugin.dataFolder.toPath().resolve("logs"),
            System.currentTimeMillis(),
            plugin.logger,
            options,
        )
        Bukkit.getOnlinePlayers().forEach(::remember)
    }

    fun reload() {
        val options = readOptions()
        enabled = options.enabled
        reloadLocalOptions()
        store.reload(options)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (!enabled) return
        val identity = identities[event.player.uniqueId] ?: return
        record(ChatLogType.MESSAGES, identity, plainSerializer.serialize(event.originalMessage()))
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!enabled) return
        val identity = identities[event.player.uniqueId] ?: return
        record(ChatLogType.COMMANDS, identity, redactSensitiveCommand(event.message, sensitiveCommands))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        remember(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        identities.remove(event.player.uniqueId)
    }

    private fun remember(player: Player) {
        store.registerPlayerName(player.name)
        identities[player.uniqueId] = PlayerIdentity(
            player.name,
            player.address?.address?.hostAddress.orEmpty(),
        )
    }

    private fun record(type: ChatLogType, identity: PlayerIdentity, rawText: String) {
        store.offer(
            ChatLogEntry(
                type,
                identity.name,
                identity.ip,
                rawText.take(maxEntryLength),
                System.currentTimeMillis(),
            ),
        )
    }

    fun handleCommand(sender: CommandSender, args: List<String>) {
        val canView = sender.hasPermission("pupschat.logs.view")
        val canClear = sender.hasPermission("pupschat.logs.clear")
        if (!canView && !canClear) {
            sender.sendMessage(plugin.message("no-permission", "<color:#FF638F>Недостаточно прав"))
            return
        }
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "messages" -> show(sender, ChatLogType.MESSAGES, args.drop(1))
            "commands" -> show(sender, ChatLogType.COMMANDS, args.drop(1))
            "clear" -> clear(sender, args.drop(1))
            else -> usage(sender, canView, canClear)
        }
    }

    fun tabComplete(sender: CommandSender, args: List<String>): List<String> {
        val canView = sender.hasPermission("pupschat.logs.view")
        val playerNames = if (
            canView && args.size == 3 &&
            args[0].lowercase(Locale.ROOT) in VIEW_TYPES &&
            args[1].equals("player", true)
        ) {
            store.suggestPlayerNames(args[2])
        } else {
            emptyList()
        }
        return logTabCompletions(
            args,
            canView,
            sender.hasPermission("pupschat.logs.clear"),
            playerNames,
        )
    }

    private fun show(sender: CommandSender, type: ChatLogType, args: List<String>) {
        if (!sender.hasPermission("pupschat.logs.view")) {
            sender.sendMessage(plugin.message("no-permission", "<color:#FF638F>Недостаточно прав"))
            return
        }
        val request = parseViewRequest(args)
        if (request == null) {
            viewUsage(sender, type)
            return
        }
        store.readLatest(type, request.first, request.second).whenComplete { lines, failure ->
            runSync {
                if (failure != null) {
                    sender.sendMessage(plugin.message("logs-read-failed", "<color:#FF638F>Не удалось прочитать логи"))
                } else if (lines.isEmpty()) {
                    sender.sendMessage(plugin.message("logs-not-found", "<gray>Подходящие логи не найдены"))
                } else {
                    sender.sendMessage(
                        plugin.message(
                            "logs-header",
                            "<color:#FCD05C>Последние {count} {type}",
                            "count" to lines.size.toString(),
                            "type" to type.label,
                        ),
                    )
                    lines.forEach { sender.sendMessage(plugin.message("logs-entry", "<gray>{entry}", "entry" to it)) }
                }
            }
        }
    }

    private fun parseViewRequest(args: List<String>): Pair<String?, Int>? {
        val configured = plugin.config.getInt("logging.view-lines", 20).coerceIn(1, MAX_VIEW_LINES)
        if (args.isEmpty()) return null to configured
        return when (args[0].lowercase(Locale.ROOT)) {
            "all" -> when (args.size) {
                1 -> null to configured
                2 -> args[1].toIntOrNull()?.takeIf { it in 1..MAX_VIEW_LINES }?.let { null to it }
                else -> null
            }
            "player" -> {
                if (args.size !in 2..3 || !args[1].matches(PLAYER_NAME)) return null
                val amount = if (args.size == 3) {
                    args[2].toIntOrNull()?.takeIf { it in 1..MAX_VIEW_LINES } ?: return null
                } else {
                    configured
                }
                args[1] to amount
            }
            else -> null
        }
    }

    private fun clear(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("pupschat.logs.clear")) {
            sender.sendMessage(plugin.message("no-permission", "<color:#FF638F>Недостаточно прав"))
            return
        }
        val days = args.singleOrNull()?.toLongOrNull()?.takeIf { it in 1L..MAX_RETENTION_DAYS }
        if (days == null) {
            sender.sendMessage(plugin.message("logs-clear-usage", "<gray>Использование: /pupschat logs clear <старше дней>"))
            return
        }
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days)
        store.clearOlderThan(cutoff).whenComplete { deleted, failure ->
            runSync {
                if (failure != null) {
                    sender.sendMessage(plugin.message("logs-clear-failed", "<color:#FF638F>Не удалось очистить логи"))
                } else {
                    sender.sendMessage(
                        plugin.message(
                            "logs-clear-success",
                            "<green>Удалено архивов и сессий логов: {count}",
                            "count" to deleted.toString(),
                        ),
                    )
                }
            }
        }
    }

    private fun usage(sender: CommandSender, canView: Boolean, canClear: Boolean) {
        if (canView) {
            sender.sendMessage(plugin.message("logs-usage-view", "<gray>/pupschat logs (messages/commands) all [строк]"))
            sender.sendMessage(plugin.message("logs-usage-player", "<gray>/pupschat logs (messages/commands) player <ник> [строк]"))
        }
        if (canClear) {
            sender.sendMessage(plugin.message("logs-clear-usage", "<gray>/pupschat logs clear <старше дней>"))
        }
    }

    private fun viewUsage(sender: CommandSender, type: ChatLogType) {
        sender.sendMessage(
            plugin.message(
                "logs-view-usage",
                "<gray>Использование: /pupschat logs {type} all [строк] | player <ник> [строк]",
                "type" to type.yamlKey,
            ),
        )
    }

    private fun runSync(block: () -> Unit) {
        if (!plugin.isEnabled) return
        runCatching {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (plugin.isEnabled) block()
            })
        }
    }

    private fun readOptions(): ChatLogOptions = ChatLogOptions(
        enabled = plugin.config.getBoolean("logging.enabled", true),
        flushIntervalMillis = plugin.config.getLong("logging.flush-interval-millis", 250L).coerceIn(50L, 5_000L),
        maxLatestBytes = megabytes("logging.max-latest-file-megabytes", 64L),
        maxTotalBytes = megabytes("logging.max-total-megabytes", 1_024L),
        retentionMillis = TimeUnit.DAYS.toMillis(
            plugin.config.getLong("logging.retention-days", 30L).coerceIn(1L, MAX_RETENTION_DAYS),
        ),
    )

    private fun reloadLocalOptions() {
        maxEntryLength = plugin.config.getInt("logging.max-entry-length", DEFAULT_MAX_ENTRY_LENGTH)
            .coerceIn(256, 32_768)
        sensitiveCommands = plugin.config.getStringList("logging.sensitive-commands")
            .ifEmpty { DEFAULT_SENSITIVE_COMMANDS.toList() }
            .asSequence()
            .map { it.trim().removePrefix("/").substringAfter(':').lowercase(Locale.ROOT) }
            .filter(String::isNotEmpty)
            .toSet()
    }

    private fun megabytes(path: String, fallback: Long): Long =
        plugin.config.getLong(path, fallback).coerceIn(1L, MAX_MEGABYTES) * 1_024L * 1_024L

    fun close() {
        identities.clear()
        store.close()
    }

    private companion object {
        const val DEFAULT_MAX_ENTRY_LENGTH = 4_096
        const val MAX_VIEW_LINES = 100
        const val MAX_RETENTION_DAYS = 36_500L
        const val MAX_MEGABYTES = 1_048_576L
        val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
        val DEFAULT_SENSITIVE_COMMANDS = setOf(
            "login", "l", "register", "reg", "changepassword", "changepass", "cp",
        )
    }
}

internal fun redactSensitiveCommand(command: String, sensitiveCommands: Set<String>): String {
    val trimmed = command.trimStart()
    val body = trimmed.removePrefix("/")
    val separator = body.indexOfFirst(Char::isWhitespace)
    if (separator < 0) return command
    val name = body.substring(0, separator)
    val plainName = name.substringAfter(':').lowercase(Locale.ROOT)
    return if (plainName in sensitiveCommands) "/$name <скрыто>" else command
}

internal fun logTabCompletions(
    args: List<String>,
    canView: Boolean,
    canClear: Boolean,
    playerNames: List<String>,
): List<String> {
    if (args.isEmpty()) return emptyList()
    val candidates = when (args.size) {
        1 -> buildList {
            if (canView) {
                add("messages")
                add("commands")
            }
            if (canClear) add("clear")
        }
        2 -> when (args[0].lowercase(Locale.ROOT)) {
            "messages", "commands" -> if (canView) listOf("all", "player") else emptyList()
            "clear" -> if (canClear) listOf("7", "30", "90") else emptyList()
            else -> emptyList()
        }
        3 -> when {
            !canView -> emptyList()
            args[0].lowercase(Locale.ROOT) !in VIEW_TYPES -> emptyList()
            args[1].equals("player", true) -> playerNames
            args[1].equals("all", true) -> VIEW_LINE_SUGGESTIONS
            else -> emptyList()
        }
        4 -> if (
            canView && args[0].lowercase(Locale.ROOT) in VIEW_TYPES && args[1].equals("player", true)
        ) {
            VIEW_LINE_SUGGESTIONS
        } else {
            emptyList()
        }
        else -> emptyList()
    }
    val input = args.last().lowercase(Locale.ROOT)
    return candidates.filter { it.lowercase(Locale.ROOT).startsWith(input) }
}

private val VIEW_LINE_SUGGESTIONS = listOf("10", "20", "50", "100")
private val VIEW_TYPES = setOf("messages", "commands")
