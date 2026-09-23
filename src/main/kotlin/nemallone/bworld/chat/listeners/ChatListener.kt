package nemallone.bworld.chat.listeners

import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.chat.ChatRenderer
import nemallone.bworld.chat.api.ChatChannel
import nemallone.bworld.chat.api.PupsChatMessageEvent
import nemallone.bworld.chat.channels.ChannelSettings
import nemallone.bworld.chat.channels.ChannelPolicy
import nemallone.bworld.chat.channels.ChannelRejection
import nemallone.bworld.chat.channels.ChatPosition
import nemallone.bworld.chat.channels.BoundedChatText
import nemallone.bworld.chat.channels.ChatAuditRecord
import nemallone.bworld.chat.ChatFormatter
import nemallone.bworld.chat.PupsChat
import nemallone.bworld.chat.filter.FilterManager
import nemallone.bworld.chat.filter.FilterResult
import nemallone.bworld.chat.filter.FloodManager
import nemallone.bworld.chat.filter.MuteManager
import nemallone.bworld.chat.filter.ToxicityManager
import nemallone.bworld.chat.filter.ai.AiFilterManager
import nemallone.bworld.chat.filter.ai.FilterCategory
import nemallone.bworld.chat.integrations.VanishIntegration
import nemallone.bworld.chat.messaging.AutoMessageManager
import nemallone.bworld.chat.messaging.ChatHideManager
import nemallone.bworld.chat.messaging.HintManager
import nemallone.bworld.chat.messaging.MentionsManager
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TextReplacementConfig
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
    private val aiFilter: AiFilterManager,
    private val chatHideManager: ChatHideManager,
    private val hintManager: HintManager,
    private val autoMessageManager: AutoMessageManager,
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
        val channels: ChannelSettings,
        val maxMessageLength: Int,
        val commandParser: ChatCommandParser
    )

    private val chatFormatter = ChatFormatter(plugin)
    private val vanishIntegration = VanishIntegration(plugin)

    @Volatile
    private var settings = defaultSettings()

    @Volatile
    private var mentionSound: Sound? = null

    private val mentionPatterns = ConcurrentHashMap<String, Pattern>()
    private val pendingAudits = ConcurrentHashMap<AsyncChatEvent, ChatAuditRecord>()

    @Volatile
    private var closed = false

    private enum class DeliveryResult { SENT, REJECTED, CANCELLED }

    private data class PreparedMessage(
        val result: DeliveryResult,
        val audit: ChatAuditRecord,
        val message: Component? = null,
        val recipients: Set<Audience> = emptySet(),
        val renderer: ChatRenderer? = null,
        val settingsSnapshot: Settings? = null,
        val channel: ChatChannel? = null,
        val autoMessage: Boolean = false
    )

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
        val channels: ChannelSettings
        val maxMessageLength: Int
        try {
            channels = ChannelSettings.read(config)
            maxMessageLength = BoundedChatText.readMaximum(config)
        } catch (exception: IllegalArgumentException) {
            plugin.logger.warning("Некорректные настройки каналов, используются предыдущие: ${exception.message}")
            return false
        }
        val next = Settings(
            globalMentionPattern = globalPattern,
            channels = channels,
            maxMessageLength = maxMessageLength,
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
        if (!chatFormatter.loadConfig()) return false
        settings = next
        vanishIntegration.loadConfig()
        updateMentionSound()
        return true
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
        try {
            onServerThread {
                val prepared = prepareChat(event) ?: return@onServerThread
                finishChat(event, prepared)
            }
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

    private fun <T> onServerThread(action: () -> T): T =
        if (Bukkit.isPrimaryThread()) action() else Bukkit.getScheduler().callSyncMethod(plugin, action).get()

    private fun prepareChat(event: AsyncChatEvent): PreparedMessage? {
        if (event.isCancelled || closed) return null
        val currentSettings = settings
        val original = BoundedChatText.read(event.originalMessage(), currentSettings.maxMessageLength)
        val current = if (event.message() === event.originalMessage()) original
            else BoundedChatText.read(event.message(), currentSettings.maxMessageLength)
        val originalChannel = ChatAuditRecord.classify(original, currentSettings.channels)
        val currentChannel = ChatAuditRecord.classify(current, currentSettings.channels)
        val audit = ChatAuditRecord(
            event.player.uniqueId,
            if (originalChannel == ChatChannel.STAFF) originalChannel else currentChannel,
            original.text,
            current.text,
            "processing-failed"
        )
        pendingAudits[event] = audit
        if (originalChannel == ChatChannel.STAFF && currentChannel != ChatChannel.STAFF) {
            pendingAudits[event] = audit.copy(outcome = "channel-changed-externally")
            plugin.feedback("staff-route-failed", event.player, plugin.message("staff-route-failed", "<color:#FF638F>Не удалось отправить сообщение в стафф-чат"))
            event.isCancelled = true
            return null
        }
        if (!current.complete) {
            pendingAudits[event] = audit.copy(outcome = "message-too-long")
            rejectLongMessage(event.player)
            event.isCancelled = true
            return null
        }
        val prepared = prepareMessage(event.player, event.message(), event.viewers().toSet(), current.text, currentSettings, audit)
        pendingAudits[event] = prepared.audit
        if (prepared.result != DeliveryResult.SENT) {
            event.isCancelled = true
            return null
        }
        return prepared
    }

    private fun finishChat(event: AsyncChatEvent, draft: PreparedMessage) {
        if (closed || event.isCancelled || !event.player.isOnline || Bukkit.getPlayer(event.player.uniqueId) !== event.player) {
            event.isCancelled = true
            pendingAudits[event] = draft.audit.copy(outcome = "delivery-cancelled")
            return
        }
        if (settings !== draft.settingsSnapshot || muteManager.rejectIfMuted(event.player)) {
            event.isCancelled = true
            pendingAudits[event] = draft.audit.copy(outcome = "settings-or-mute-changed")
            return
        }
        val prepared = finishMessage(event.player, draft)
        pendingAudits[event] = prepared.audit
        if (prepared.result != DeliveryResult.SENT) {
            event.isCancelled = true
            return
        }
        event.viewers().clear()
        event.viewers().addAll(prepared.recipients)
        event.message(requireNotNull(prepared.message))
        prepared.renderer?.let(event::renderer)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun auditChat(event: AsyncChatEvent) {
        val prepared = pendingAudits.remove(event)
        if (closed) return
        val currentSettings = settings
        val audit = prepared ?: run {
            val original = BoundedChatText.read(event.originalMessage(), currentSettings.maxMessageLength)
            ChatAuditRecord(
                event.player.uniqueId,
                ChatAuditRecord.classify(original, currentSettings.channels),
                original.text,
                original.text,
                if (event.isCancelled) ChatAuditRecord.CANCELLED_EXTERNAL else "not-processed-by-pupschat"
            )
        }
        val cancelled = event.isCancelled
        plugin.recordRouted(audit.senderId, audit.channel.id, audit.originalText, audit.finalOutcome(cancelled))
    }

    fun close() {
        closed = true
        pendingAudits.clear()
    }

    private fun rejectLongMessage(player: Player) {
        plugin.feedback("message-too-long", player, plugin.message("message-too-long", "<color:#FF638F>Сообщение слишком длинное"))
    }

    private fun prepareMessage(
        sender: Player,
        originalComponent: Component,
        originalViewers: Set<Audience>,
        originalText: String,
        currentSettings: Settings,
        originalAudit: ChatAuditRecord
    ): PreparedMessage {
        check(Bukkit.isPrimaryThread()) { "Обработка каналов требует основной поток сервера" }
        val channels = currentSettings.channels
        val selected = ChannelPolicy.select(
            originalText, channels, sender.hasPermission(channels.staffSendPermission)
        )
        var plainMessage = selected.text
        val removedCharacters = originalText.length - plainMessage.length
        var messageComponent = when {
            !selected.consumedPrefix -> originalComponent
            originalComponent is TextComponent && originalComponent.content().length >= removedCharacters ->
                originalComponent.content(originalComponent.content().substring(removedCharacters))
            else -> Component.text(plainMessage).style(originalComponent.style())
        }
        val channel = selected.channel
        val auditChannel = if (originalAudit.channel == ChatChannel.STAFF) ChatChannel.STAFF else channel

        fun reject(outcome: String, result: DeliveryResult = DeliveryResult.REJECTED): PreparedMessage {
            return PreparedMessage(result, originalAudit.copy(channel = auditChannel, processedText = plainMessage, outcome = outcome))
        }

        if (selected.rejection != null) {
            val feedback = when (selected.rejection) {
                ChannelRejection.NO_PERMISSION -> "no-permission" to "<color:#FF638F>Недостаточно прав"
                ChannelRejection.EMPTY -> "empty-message" to "<gray>Введите сообщение"
            }
            plugin.feedback(feedback.first, sender, plugin.message(feedback.first, feedback.second))
            return reject(selected.rejection.name.lowercase(Locale.ROOT))
        }
        if (muteManager.rejectIfMuted(sender)) return reject("muted")

        val autoMessage = autoMessageManager.consumeAutoMessage(sender.uniqueId, plainMessage)
        if (!autoMessage) {
            when (val filterResult = applyMessageFilters(sender, plainMessage, if (channel == ChatChannel.STAFF) "staff" else null, commitHistory = false)) {
                FilterResult.Blocked -> return reject("filter")
                is FilterResult.Modified -> {
                    plainMessage = filterResult.message
                    messageComponent = Component.text(plainMessage)
                }
                FilterResult.Allowed -> Unit
            }
            if (floodManager.checkFlood(sender)) return reject("flood")
        }
        val aiCategory = if (autoMessage || channel == ChatChannel.STAFF || sender.hasPermission("pupschat.bypass.ai")) null
            else aiFilter.classify(plainMessage)
        if (toxicityManager.checkMessage(sender, plainMessage, aiCategory == FilterCategory.TARGETED_INSULT) == FilterResult.Blocked) {
            return reject("toxicity")
        }

        return PreparedMessage(
            DeliveryResult.SENT,
            originalAudit.copy(channel = auditChannel, processedText = plainMessage, outcome = "pending-delivery"),
            messageComponent, originalViewers, settingsSnapshot = currentSettings, channel = channel,
            autoMessage = autoMessage,
        )
    }

    private fun finishMessage(sender: Player, draft: PreparedMessage): PreparedMessage {
        val currentSettings = requireNotNull(draft.settingsSnapshot)
        val channels = currentSettings.channels
        val channel = requireNotNull(draft.channel)
        val plainMessage = draft.audit.processedText
        var messageComponent = requireNotNull(draft.message)
        fun reject(outcome: String, result: DeliveryResult = DeliveryResult.REJECTED) =
            PreparedMessage(result, draft.audit.copy(outcome = outcome))
        if (channel == ChatChannel.STAFF && !sender.hasPermission(channels.staffSendPermission)) return reject("no-permission")
        val recipients = selectRecipients(sender, channel, channels, draft.recipients)
        recipients.removeIf { viewer ->
            channel != ChatChannel.STAFF && viewer is Player && viewer !== sender && chatHideManager.shouldHide(viewer, sender)
        }
        val allowed = recipients.toSet()
        val routedEvent = PupsChatMessageEvent(sender, channel, messageComponent, recipients)
        Bukkit.getPluginManager().callEvent(routedEvent)
        if (routedEvent.isCancelled) return reject("cancelled", DeliveryResult.CANCELLED)
        if (!draft.autoMessage) filterManager.rememberMessage(sender.uniqueId, plainMessage)
        if (!draft.autoMessage && channel != ChatChannel.STAFF) hintManager.checkHints(sender, plainMessage)
        // Обработчики события могут убрать получателей, но не обойти ограничения канала
        recipients.retainAll(allowed)
        recipients.retainAll(selectRecipients(sender, channel, channels, recipients))
        recipients.removeIf { viewer ->
            channel != ChatChannel.STAFF && viewer is Player && viewer !== sender && chatHideManager.shouldHide(viewer, sender)
        }
        recipients.add(sender)

        messageComponent = handleMentions(
            sender, plainMessage.lowercase(Locale.ROOT), messageComponent, currentSettings, recipients, channel
        )
        val renderer = chatFormatter.renderer(sender, messageComponent, channel, recipients)
        if (channel == ChatChannel.LOCAL && recipients.none {
                it is Player && it !== sender && !vanishIntegration.isVanished(it)
            }) {
            plugin.feedback("local-no-audience", sender, plugin.message("local-no-audience", "<gray>Рядом нет игроков, которые увидят сообщение"))
        }
        val audit = draft.audit.copy(
            processedText = plainMessage,
            outcome = ChatAuditRecord.ACCEPTED,
        )
        return PreparedMessage(DeliveryResult.SENT, audit, messageComponent, recipients.toSet(), renderer)
    }

    private fun selectRecipients(
        sender: Player,
        channel: ChatChannel,
        settings: ChannelSettings,
        original: Set<Audience>
    ): MutableSet<Audience> {
        val origin = if (channel == ChatChannel.LOCAL) sender.location.let {
            ChatPosition(sender.world.uid, it.x, it.y, it.z)
        } else null
        val result = original.filterTo(LinkedHashSet()) { viewer ->
            when {
                viewer === sender -> true
                viewer === Bukkit.getConsoleSender() -> settings.consoleEnabled(channel)
                viewer !is Player -> channel == ChatChannel.GLOBAL
                !viewer.isOnline -> false
                channel == ChatChannel.STAFF -> viewer.hasPermission(settings.staffReadPermission)
                channel == ChatChannel.LOCAL -> {
                    val location = viewer.location
                    ChannelPolicy.withinRadius(requireNotNull(origin), ChatPosition(viewer.world.uid, location.x, location.y, location.z), settings.localRadius)
                }
                else -> true
            }
        }
        result.add(sender)
        return result
    }

    private fun applyMessageFilters(
        player: Player,
        message: String,
        context: String?,
        commitHistory: Boolean = true,
    ): FilterResult {
        val replacedMessage = filterManager.applyReplacements(message)
        if (replacedMessage.length > settings.maxMessageLength) {
            rejectLongMessage(player)
            return FilterResult.Blocked
        }
        return when (
            val result = filterManager.filterMessage(player, replacedMessage, context, commitHistory)
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

    private fun handleMentions(
        sender: Player,
        lowerMessage: String,
        originalMessage: Component,
        settings: Settings,
        viewers: Set<Audience>,
        channel: ChatChannel
    ): Component {
        var message = originalMessage

        if (sender.hasPermission("pupschat.globalmentions") &&
            settings.globalMentionPattern.matcher(lowerMessage).find()) {
            for (target in viewers) {
                if (target !is Player || target == sender) continue
                if (target !in viewers) continue
                if (vanishIntegration.isVanished(target)) continue
                if (!mentionsManager.isMentionsEnabled(target.uniqueId)) continue
                if (channel != ChatChannel.STAFF && chatHideManager.shouldHide(target, sender)) continue
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
            if (target != sender && target in viewers && !vanishIntegration.isVanished(target)) {
                matchedTargets.add(target)
            }
        }

        for (target in matchedTargets) {
            val pattern = mentionPatterns.computeIfAbsent(target.name, ::createMentionPattern)

            if (target in viewers &&
                mentionsManager.isMentionsEnabled(target.uniqueId) &&
                (channel == ChatChannel.STAFF || !chatHideManager.shouldHide(target, sender))) {
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
        val currentSettings = settings
        val command = currentSettings.commandParser.parse(event.message) ?: return
        if (event.message.length - command.messageStart > currentSettings.maxMessageLength) {
            rejectLongMessage(player)
            event.isCancelled = true
            return
        }

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
        channels = ChannelSettings(),
        maxMessageLength = BoundedChatText.DEFAULT_MAX_LENGTH,
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
