package nemallone.bworld.chat.listeners

import io.papermc.paper.event.player.AsyncChatEvent
import nemallone.bworld.chat.ChatFormatter
import nemallone.bworld.chat.PupsChat
import nemallone.bworld.chat.filter.FilterManager
import nemallone.bworld.chat.filter.FilterResult
import nemallone.bworld.chat.filter.FloodManager
import nemallone.bworld.chat.filter.MuteManager
import nemallone.bworld.chat.filter.ToxicityManager
import nemallone.bworld.chat.integrations.VanishIntegration
import nemallone.bworld.chat.messaging.AutoMessageManager
import nemallone.bworld.chat.messaging.ChatHideManager
import nemallone.bworld.chat.messaging.HintManager
import nemallone.bworld.chat.messaging.MentionsManager
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Statistic
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.logging.Level
import java.util.regex.Pattern

internal class ChatListener(
    private val plugin: PupsChat,
    private val filterManager: FilterManager,
    private val mentionsManager: MentionsManager,
    private val floodManager: FloodManager,
    private val muteManager: MuteManager,
    private val toxicityManager: ToxicityManager,
    private val chatHideManager: ChatHideManager,
    private val hintManager: HintManager,
    private val autoMessageManager: AutoMessageManager
) : Listener {

    private companion object {
        private val NEVER_MATCH = Pattern.compile("(?!x)x")
        private val DEFAULT_TARGETED_PRIVATE_MESSAGE_COMMANDS = setOf(
            "msg", "m", "tell", "t", "w", "whisper", "pm", "message",
            "emsg", "etell", "et", "ew", "ewhisper", "epm", "emessage"
        )
        private val DEFAULT_REPLY_PRIVATE_MESSAGE_COMMANDS = setOf("r", "reply", "er", "ereply")
        private val DEFAULT_DIRECT_CHAT_COMMANDS = setOf("me", "action", "eme", "eaction")
        private val DEFAULT_MAIL_COMMANDS = setOf("mail", "email")
        private val DEFAULT_TARGETED_MAIL_SUBCOMMANDS = setOf("send")
        private val DEFAULT_BROADCAST_MAIL_SUBCOMMANDS = setOf("sendall")
        private val NAME_CANDIDATE_PATTERN = Pattern.compile("(?<![\\p{L}\\p{N}_])@?[A-Za-z0-9_]{3,16}(?![\\p{L}\\p{N}_])")
    }

    private data class Settings(
        val globalMentionPattern: Pattern,
        val leadingPrefixPattern: Pattern?,
        val commandParser: ChatCommandParser
    )

    private val plainSerializer = PlainTextComponentSerializer.plainText()
    private val chatFormatter = ChatFormatter(plugin)
    private val vanishIntegration = VanishIntegration(plugin)

    @Volatile
    private var settings = defaultSettings()

    @Volatile
    private var mentionSound: Sound? = null

    private val mentionPatterns = ConcurrentHashMap<String, Pattern>()

    init {
        loadConfig()
        Bukkit.getOnlinePlayers().forEach(::registerPlayer)
    }

    fun loadConfig(): Boolean {
        val config = plugin.config
        val globalAliases = configuredSet(
            config,
            "mentions.global-aliases",
            setOf("all", "все")
        )
        val globalPattern = if (globalAliases.isEmpty()) {
            NEVER_MATCH
        } else {
            Pattern.compile(
                "(?i)@(?:${globalAliases.joinToString("|") { Pattern.quote(it) }})(?![\\p{L}\\p{N}_])"
            )
        }
        val leadingPrefix = config.getString("chat-processing.remove-leading-prefix", "!") ?: "!"

        val leadingPrefixPattern = if (leadingPrefix.isEmpty()) {
            null
        } else {
            Pattern.compile("^${Pattern.quote(leadingPrefix)}\\s*")
        }

        settings = Settings(
            globalMentionPattern = globalPattern,
            leadingPrefixPattern = leadingPrefixPattern,
            commandParser = ChatCommandParser(
                targetedPrivateCommands = configuredSet(
                    config,
                    "chat-processing.commands.targeted-private",
                    DEFAULT_TARGETED_PRIVATE_MESSAGE_COMMANDS
                ),
                replyPrivateCommands = configuredSet(
                    config,
                    "chat-processing.commands.reply-private",
                    DEFAULT_REPLY_PRIVATE_MESSAGE_COMMANDS
                ),
                directChatCommands = configuredSet(
                    config,
                    "chat-processing.commands.direct-chat",
                    DEFAULT_DIRECT_CHAT_COMMANDS
                ),
                mailCommands = configuredSet(
                    config,
                    "chat-processing.commands.mail",
                    DEFAULT_MAIL_COMMANDS
                ),
                targetedMailSubcommands = configuredSet(
                    config,
                    "chat-processing.commands.mail-targeted-subcommands",
                    DEFAULT_TARGETED_MAIL_SUBCOMMANDS
                ),
                broadcastMailSubcommands = configuredSet(
                    config,
                    "chat-processing.commands.mail-broadcast-subcommands",
                    DEFAULT_BROADCAST_MAIL_SUBCOMMANDS
                )
            )
        )
        val chatFormatLoaded = chatFormatter.loadConfig()
        vanishIntegration.loadConfig()
        updateMentionSound()
        return chatFormatLoaded
    }

    private fun updateMentionSound() {
        val soundName = mentionsManager.soundName
        mentionSound = try {
            val key = if (soundName.contains(":")) Key.key(soundName)
            else Key.key("minecraft", soundName)
            Sound.sound(
                key, Sound.Source.MASTER,
                mentionsManager.soundVolume, mentionsManager.soundPitch
            )
        } catch (_: RuntimeException) {
            plugin.logger.warning("Некорректный звук mentions.sound.name: $soundName")
            null
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (!event.isAsynchronous || Bukkit.isPrimaryThread()) {
            processChatSafely(event)
            return
        }

        try {
            // форматирование и интеграции ниже используют апи, привязанные к потоку сервера
            Bukkit.getScheduler().callSyncMethod(plugin) {
                processChat(event)
            }.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            event.isCancelled = true
            plugin.logger.warning("Обработка сообщения была прервана")
        } catch (exception: ExecutionException) {
            val cause = exception.cause ?: exception
            event.isCancelled = true
            if (cause is Error) throw cause
            plugin.logger.log(Level.SEVERE, "Ошибка при обработке сообщения", cause)
        } catch (_: CancellationException) {
            event.isCancelled = true
            if (plugin.isEnabled) {
                plugin.logger.warning("Обработка сообщения была отменена")
            }
        } catch (exception: RuntimeException) {
            event.isCancelled = true
            if (plugin.isEnabled) {
                plugin.logger.log(
                    Level.SEVERE,
                    "Не удалось передать обработку сообщения на основной поток",
                    exception
                )
            }
        }
    }

    private fun processChatSafely(event: AsyncChatEvent) {
        try {
            processChat(event)
        } catch (exception: RuntimeException) {
            event.isCancelled = true
            plugin.logger.log(Level.SEVERE, "Ошибка при обработке сообщения", exception)
        }
    }

    private fun processChat(event: AsyncChatEvent) {
        val sender = event.player
        val currentSettings = settings

        if (muteManager.rejectIfMuted(sender)) {
            event.isCancelled = true
            return
        }

        var plainMessage = plainSerializer.serialize(event.message())
        var messageComponent = event.message()

        val prefixPattern = currentSettings.leadingPrefixPattern
        if (prefixPattern != null && prefixPattern.matcher(plainMessage).find()) {
            plainMessage = prefixPattern.matcher(plainMessage).replaceFirst("")
            messageComponent = messageComponent.replaceText(
                TextReplacementConfig.builder()
                    .match(prefixPattern)
                    .replacement("")
                    .once()
                    .build()
            )
        }

        if (plainMessage.trim().isEmpty()) {
            event.isCancelled = true
            return
        }

        val autoMessage = autoMessageManager.consumeAutoMessage(sender.uniqueId, plainMessage)
        if (!autoMessage) {
            when (val filterResult = applyMessageFilters(sender, plainMessage, null)) {
                FilterResult.Blocked -> {
                    event.isCancelled = true
                    return
                }
                is FilterResult.Modified -> {
                    plainMessage = filterResult.message
                    messageComponent = Component.text(plainMessage)
                }
                FilterResult.Allowed -> Unit
            }

            if (floodManager.checkFlood(sender)) {
                event.isCancelled = true
                return
            }

            hintManager.checkHints(sender, plainMessage)
        }

        if (toxicityManager.checkMessage(sender, plainMessage) == FilterResult.Blocked) {
            event.isCancelled = true
            return
        }

        val lowercaseMessage = plainMessage.lowercase(Locale.ROOT)

        messageComponent = handleMentions(
            sender,
            lowercaseMessage,
            messageComponent,
            currentSettings,
            event.viewers()
        )

        event.message(messageComponent)

        chatFormatter.format(event, sender, messageComponent)

        applyChatHiding(event, sender)
    }

    private fun applyMessageFilters(
        player: Player,
        message: String,
        context: String?
    ): FilterResult {
        val replacedMessage = filterManager.applyReplacements(message)
        return when (
            val result = filterManager.filterMessage(player, replacedMessage, context)
        ) {
            FilterResult.Blocked -> FilterResult.Blocked
            is FilterResult.Modified -> result
            FilterResult.Allowed -> if (replacedMessage == message) {
                FilterResult.Allowed
            } else {
                FilterResult.Modified(replacedMessage)
            }
        }
    }

    private fun applyChatHiding(
        event: AsyncChatEvent,
        sender: Player
    ) {
        event.viewers().removeIf { viewer ->
            if (viewer !is Player || viewer === sender) return@removeIf false
            chatHideManager.shouldHide(viewer, sender)
        }
    }

    private fun handleMentions(
        sender: Player,
        lowerMessage: String,
        originalMessage: Component,
        settings: Settings,
        viewers: Set<Audience>
    ): Component {
        var message = originalMessage

        if (sender.hasPermission("pupschat.globalmentions") &&
            settings.globalMentionPattern.matcher(lowerMessage).find()) {
            for (target in Bukkit.getOnlinePlayers()) {
                if (target == sender) continue
                if (target !in viewers) continue
                if (vanishIntegration.isVanished(target)) continue
                if (!mentionsManager.isMentionsEnabled(target.uniqueId)) continue
                if (chatHideManager.shouldHide(target, sender)) continue
                notifyMention(target, sender.name)
            }

            return message.replaceText(
                TextReplacementConfig.builder()
                    .match(settings.globalMentionPattern)
                    .replacement { matchResult, _ -> Component.text(matchResult.group()) }
                    .build()
            )
        }

        val tokens = NAME_CANDIDATE_PATTERN.matcher(lowerMessage)
        val matchedTargets = HashSet<Player>()
        while (tokens.find()) {
            val token = tokens.group().removePrefix("@")
            val exactName = filterManager.findExactPlayerName(token) ?: continue
            val target = Bukkit.getPlayerExact(exactName) ?: continue
            if (target != sender && !vanishIntegration.isVanished(target)) {
                matchedTargets.add(target)
            }
        }

        for (target in matchedTargets) {
            val pattern = mentionPatterns.computeIfAbsent(target.name, ::createMentionPattern)

            if (target in viewers &&
                mentionsManager.isMentionsEnabled(target.uniqueId) &&
                !chatHideManager.shouldHide(target, sender)) {
                notifyMention(target, sender.name)
            }

            message = message.replaceText(
                TextReplacementConfig.builder()
                    .match(pattern)
                    .replacement { matchResult, _ ->
                        val match = matchResult.group()
                        if (match.startsWith("@")) Component.text(match)
                        else Component.text("@$match")
                    }
                    .build()
            )
        }

        return message
    }

    private fun notifyMention(target: Player, senderName: String) {
        val sound = mentionSound
        if (sound != null) target.playSound(sound)
        target.sendActionBar(mentionsManager.getActionBarMessage(senderName))
    }

    private fun createMentionPattern(name: String): Pattern = Pattern.compile(
        "(?i)(?<![\\p{L}\\p{N}_])@?${Pattern.quote(name)}(?![\\p{L}\\p{N}_])"
    )

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        val command = settings.commandParser.parse(event.message) ?: return

        if (muteManager.rejectIfMuted(player)) {
            event.isCancelled = true
            return
        }

        val messageStart = command.messageStart
        val originalMessage = event.message.substring(messageStart)
        if (originalMessage.isEmpty()) return

        val prefix = event.message.substring(0, messageStart)
        val finalMessage = when (
            val result = applyMessageFilters(player, originalMessage, command.context)
        ) {
            FilterResult.Blocked -> {
                event.isCancelled = true
                return
            }
            is FilterResult.Modified -> {
                event.message = prefix + result.message
                result.message
            }
            FilterResult.Allowed -> originalMessage
        }

        if (floodManager.checkFlood(player)) {
            event.isCancelled = true
            return
        }

        if (
            command.checkToxicity &&
            toxicityManager.checkMessage(player, finalMessage) == FilterResult.Blocked
        ) {
            event.isCancelled = true
        }
    }

    private fun configuredSet(
        config: FileConfiguration,
        path: String,
        fallback: Set<String>
    ): Set<String> {
        val values = if (config.contains(path)) config.getStringList(path) else fallback
        return values.asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter(String::isNotEmpty)
            .toSet()
    }

    private fun defaultSettings() = Settings(
        globalMentionPattern = NEVER_MATCH,
        leadingPrefixPattern = Pattern.compile("^!\\s*"),
        commandParser = ChatCommandParser(
            DEFAULT_TARGETED_PRIVATE_MESSAGE_COMMANDS,
            DEFAULT_REPLY_PRIVATE_MESSAGE_COMMANDS,
            DEFAULT_DIRECT_CHAT_COMMANDS,
            DEFAULT_MAIL_COMMANDS,
            DEFAULT_TARGETED_MAIL_SUBCOMMANDS,
            DEFAULT_BROADCAST_MAIL_SUBCOMMANDS
        )
    )

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        registerPlayer(event.player)
    }

    private fun registerPlayer(player: Player) {
        filterManager.addPlayerName(player.name)
        filterManager.registerPlayerPlaytime(
            player.uniqueId,
            player.getStatistic(Statistic.PLAY_ONE_MINUTE).toLong()
        )
        mentionPatterns.computeIfAbsent(player.name, ::createMentionPattern)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        filterManager.clearPlayer(player.uniqueId, player.name)
        floodManager.clearPlayer(player.uniqueId)
        mentionPatterns.remove(player.name)
    }
}
