package nemallone.bworld.chat

import nemallone.bworld.chat.filter.FilterManager
import nemallone.bworld.chat.filter.FloodManager
import nemallone.bworld.chat.filter.MuteManager
import nemallone.bworld.chat.filter.ToxicityManager
import nemallone.bworld.chat.filter.ViolationManager
import nemallone.bworld.chat.integrations.PracticeIntegration
import nemallone.bworld.chat.listeners.ChatListener
import nemallone.bworld.chat.messaging.AnnouncementManager
import nemallone.bworld.chat.messaging.AutoMessageMode
import nemallone.bworld.chat.messaging.AutoMessageManager
import nemallone.bworld.chat.messaging.ChatHideManager
import nemallone.bworld.chat.messaging.HintManager
import nemallone.bworld.chat.messaging.MentionsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.IOException
import java.util.HashSet
import java.util.Locale
import java.util.logging.Level

class PupsChat : JavaPlugin() {

    private val miniMessage = MiniMessage.miniMessage()

    private lateinit var filterManager: FilterManager
    private lateinit var violationManager: ViolationManager
    private lateinit var muteManager: MuteManager
    private lateinit var toxicityManager: ToxicityManager
    private lateinit var mentionsManager: MentionsManager
    private lateinit var floodManager: FloodManager
    private lateinit var chatHideManager: ChatHideManager
    private lateinit var announcementManager: AnnouncementManager
    private lateinit var hintManager: HintManager
    private lateinit var autoMessageManager: AutoMessageManager
    private lateinit var chatListener: ChatListener
    private lateinit var dataSaver: PlayerDataSaver
    private var practiceIntegration: PracticeIntegration? = null
    private val invalidMessageKeys = HashSet<String>()

    internal val playerDataSaver: PlayerDataSaver
        get() = dataSaver

    private data class ConfigurationFailure(
        val file: File,
        val exception: Exception
    ) {
        val reason: String
            get() = exception.message
                ?.lineSequence()
                ?.firstOrNull()
                ?.takeIf(String::isNotBlank)
                ?: exception.javaClass.simpleName
    }

    override fun onEnable() {
        val configurationFailure = validateConfigurationFiles()
        if (configurationFailure != null) {
            logger.log(
                Level.SEVERE,
                "Не удалось загрузить ${configurationFailure.file.name}. Исправьте YAML.",
                configurationFailure.exception
            )
            server.pluginManager.disablePlugin(this)
            return
        }
        reloadConfig()

        try {
            dataSaver = PlayerDataSaver(dataFolder.toPath().resolve("data"), logger)
            muteManager = MuteManager(this)
            toxicityManager = ToxicityManager(this, muteManager)
            if (!toxicityManager.isReady) {
                server.pluginManager.disablePlugin(this)
                return
            }
            violationManager = ViolationManager(this, muteManager)
            filterManager = FilterManager(this, violationManager)
            mentionsManager = MentionsManager(this)
            floodManager = FloodManager(this, violationManager)
            autoMessageManager = AutoMessageManager(this)

            practiceIntegration = if (server.pluginManager.isPluginEnabled("StrikePractice")) {
                val integration = PracticeIntegration(this, autoMessageManager)
                integration.register()
                integration
            } else {
                null
            }

            chatHideManager = ChatHideManager(this, practiceIntegration)
            announcementManager = AnnouncementManager(this)
            hintManager = HintManager(this)
            chatListener = ChatListener(
                this,
                filterManager,
                mentionsManager,
                floodManager,
                muteManager,
                toxicityManager,
                chatHideManager,
                hintManager,
                autoMessageManager
            )
            server.pluginManager.registerEvents(chatListener, this)
        } catch (exception: PlayerDataException) {
            logger.log(
                Level.SEVERE,
                "${exception.message}. Исходный файл не будет перезаписан: ${exception.path}",
                exception.cause ?: exception
            )
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        if (::announcementManager.isInitialized) announcementManager.stopTask()
        try {
            if (::muteManager.isInitialized) {
                queueFinalDataSave("mutes.yml", muteManager::queueSave)
            }
            if (::mentionsManager.isInitialized) {
                queueFinalDataSave("mentions.yml", mentionsManager::queueSave)
            }
            if (::chatHideManager.isInitialized) {
                queueFinalDataSave("chathide.yml", chatHideManager::queueSave)
            }
            if (::autoMessageManager.isInitialized) {
                queueFinalDataSave("automessage.yml", autoMessageManager::queueSave)
            }
        } finally {
            if (::dataSaver.isInitialized) dataSaver.close()
        }
    }

    private fun queueFinalDataSave(fileName: String, save: () -> Unit) {
        try {
            save()
        } catch (exception: RuntimeException) {
            logger.log(Level.SEVERE, "Не удалось подготовить сохранение $fileName", exception)
        }
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        return when (command.name.lowercase(Locale.ROOT)) {
            "pupschat" -> handleAdminCommand(sender, args)
            "unmutef" -> handleFilterUnmuteCommand(sender, args)
            "acb" -> handleActionBarCommand(sender, args)
            "chathide" -> sender is Player && handleChatHideCommand(sender)
            "automessage" -> sender is Player && handleAutoMessageCommand(sender)
            "mentions" -> sender is Player && handleMentionsCommand(sender)
            else -> false
        }
    }

    private fun handleAdminCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("pupschat.admin")) {
            sender.sendMessage(message("no-permission", "<color:#FF638F>Нет прав"))
            return true
        }
        if (args.firstOrNull()?.equals("reload", true) != true) {
            sender.sendMessage(message("reload-usage", "<gray>Использование: /pupschat reload"))
            return true
        }

        val configurationFailure = validateConfigurationFiles()
        if (configurationFailure != null) {
            logger.log(
                Level.WARNING,
                "Перезагрузка отменена: некорректный ${configurationFailure.file.name}",
                configurationFailure.exception
            )
            sender.sendMessage(
                message(
                    "reload-failed",
                    "<color:#FF638F>Не удалось загрузить {file}: {error}",
                    "file" to configurationFailure.file.name,
                    "error" to configurationFailure.reason
                )
            )
            return true
        }

        reloadConfig()
        invalidMessageKeys.clear()
        filterManager.loadConfig()
        violationManager.loadConfig()
        muteManager.loadConfig()
        val toxicityReloaded = toxicityManager.loadConfig()
        mentionsManager.loadConfig()
        floodManager.loadConfig()
        practiceIntegration?.loadConfig()
        announcementManager.reload()
        hintManager.loadConfig()
        autoMessageManager.loadConfig()
        val chatFormatReloaded = chatListener.loadConfig()
        when {
            !toxicityReloaded -> {
                sender.sendMessage(
                    message(
                        "reload-failed",
                        "<color:#FF638F>Не удалось загрузить {file}: {error}",
                        "file" to "toxicity.txt",
                        "error" to "используется предыдущий словарь"
                    )
                )
            }
            !chatFormatReloaded -> {
                sender.sendMessage(
                    message(
                        "reload-failed",
                        "<color:#FF638F>Не удалось загрузить {file}: {error}",
                        "file" to "config.yml",
                        "error" to "используются предыдущие настройки chat-format"
                    )
                )
            }
            else -> sender.sendMessage(message("reload-success", "<green>Конфиг перезагружен"))
        }
        return true
    }

    private fun handleFilterUnmuteCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("pupschat.unmutef")) {
            sender.sendMessage(message("no-permission", "<color:#FF638F>Нет прав"))
            return true
        }
        if (args.size != 1) {
            sender.sendMessage(message("unmutef-usage", "<gray>Использование: /unmutef <игрок>"))
            return true
        }

        val onlineTarget = Bukkit.getPlayerExact(args[0])
        val cachedTarget = if (onlineTarget == null) Bukkit.getOfflinePlayerIfCached(args[0]) else null
        val targetId = onlineTarget?.uniqueId
            ?: muteManager.findMutedPlayerId(args[0])
            ?: cachedTarget?.uniqueId
        if (targetId == null) {
            sender.sendMessage(message("player-not-found", "<color:#FF638F>Игрок не найден"))
            return true
        }

        val targetName = onlineTarget?.name ?: cachedTarget?.name ?: args[0]
        if (muteManager.unmute(targetId)) {
            sender.sendMessage(
                message(
                    "unmutef-success",
                    "<gray>Мут фильтра снят с игрока <white>{player}",
                    "player" to targetName
                )
            )
        } else {
            sender.sendMessage(
                message(
                    "unmutef-not-muted",
                    "<gray>Игрок <white>{player} <gray>не находится в муте фильтра",
                    "player" to targetName
                )
            )
        }
        return true
    }

    private fun validateConfigurationFiles(): ConfigurationFailure? {
        saveDefaultConfig()
        val announcementsFile = File(dataFolder, "announcements.yml")
        if (!announcementsFile.exists()) saveResource("announcements.yml", false)
        val toxicityFile = File(dataFolder, "toxicity.txt")
        if (!toxicityFile.exists()) saveResource("toxicity.txt", false)

        for (file in listOf(File(dataFolder, "config.yml"), announcementsFile)) {
            try {
                YamlConfiguration().load(file)
            } catch (exception: InvalidConfigurationException) {
                return ConfigurationFailure(file, exception)
            } catch (exception: IOException) {
                return ConfigurationFailure(file, exception)
            }
        }
        return null
    }

    private fun handleActionBarCommand(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("pupschat.acb")) {
            sender.sendMessage(message("no-permission", "<color:#FF638F>Нет прав"))
            return true
        }
        if (args.size < 2) {
            sender.sendMessage(
                message(
                    "action-bar-usage",
                    "<gray>Использование: /acb (ник|@a) \\<сообщение>"
                )
            )
            return true
        }

        val component = try {
            miniMessage.deserializeChecked(args.drop(1).joinToString(" "))
        } catch (exception: RuntimeException) {
            sender.sendMessage(
                message(
                    "invalid-format",
                    "<color:#FF638F>Ошибка форматирования: {error}",
                    "error" to (exception.message ?: exception.javaClass.simpleName)
                )
            )
            return true
        }

        if (args[0].equals("@a", true)) {
            for (player in Bukkit.getOnlinePlayers()) player.sendActionBar(component)
            return true
        }

        val target = Bukkit.getPlayerExact(args[0])
        if (target == null) {
            sender.sendMessage(message("player-not-found", "<color:#FF638F>Игрок не найден"))
        } else {
            target.sendActionBar(component)
        }
        return true
    }

    private fun handleChatHideCommand(player: Player): Boolean {
        if (!player.hasPermission("pupschat.chat")) {
            player.sendMessage(message("no-permission", "<color:#FF638F>Нет прав"))
            return true
        }

        val mode = chatHideManager.switchMode(player.uniqueId)
        val path = when (mode) {
            ChatHideManager.Mode.ALL -> "chat-all"
            ChatHideManager.Mode.DUEL -> "chat-duel"
            ChatHideManager.Mode.OFF -> "chat-off"
        }
        val fallback = when (mode) {
            ChatHideManager.Mode.ALL -> "<gray>Чат <color:#A4FF63>открыт"
            ChatHideManager.Mode.DUEL -> "<gray>Чат <color:#FF638F>скрыт <gray>(<color:#FFDD81>только в дуэли<gray>)"
            ChatHideManager.Mode.OFF -> "<gray>Чат <color:#FF638F>скрыт"
        }
        sendSettingFeedback(player, message(path, fallback))
        return true
    }

    private fun handleAutoMessageCommand(player: Player): Boolean {
        if (!player.hasPermission("pupschat.automessage")) {
            player.sendMessage(message("no-permission", "<color:#FF638F>Нет прав"))
            return true
        }
        if (practiceIntegration?.autoMessagesAvailable != true) {
            sendSettingFeedback(
                player,
                message(
                    "auto-message-unavailable",
                    "<color:#FF638F>Автосообщения сейчас недоступны"
                )
            )
            return true
        }

        val mode = autoMessageManager.switchMode(player.uniqueId)
        val path = when (mode) {
            AutoMessageMode.DEFAULT -> "auto-message-default"
            AutoMessageMode.TOXIC -> "auto-message-toxic"
            AutoMessageMode.OFF -> "auto-message-off"
        }
        val fallback = when (mode) {
            AutoMessageMode.DEFAULT ->
                "<gray>Автосообщения <color:#A4FF63>включены " +
                    "<gray>(<color:#FFDD81>ᴏбычʜый<gray>)"
            AutoMessageMode.TOXIC ->
                "<gray>Автосообщения <color:#A4FF63>включены " +
                    "<gray>(<color:#FFDD81>тᴏᴋᴄиᴋ<gray>)"
            AutoMessageMode.OFF -> "<gray>Автосообщения <color:#FF638F>выключены"
        }
        sendSettingFeedback(player, message(path, fallback))
        return true
    }

    private fun handleMentionsCommand(player: Player): Boolean {
        if (!player.hasPermission("pupschat.mentions")) {
            player.sendMessage(message("no-permission", "<color:#FF638F>Нет прав"))
            return true
        }

        val enabled = !mentionsManager.isMentionsEnabled(player.uniqueId)
        mentionsManager.setMentionsEnabled(player.uniqueId, enabled)
        val path = if (enabled) "mentions-enabled" else "mentions-disabled"
        val fallback = if (enabled) {
            "<gray>Упоминания <color:#A4FF63>включены"
        } else {
            "<gray>Упоминания <color:#FF638F>выключены"
        }
        sendSettingFeedback(player, message(path, fallback))
        return true
    }

    private fun sendSettingFeedback(player: Player, component: Component) {
        val output = config.getString(
            "command-messages.player-settings-output",
            "action-bar"
        )?.lowercase(Locale.ROOT)
        if (output == "chat") player.sendMessage(component) else player.sendActionBar(component)
    }

    private fun message(
        key: String,
        fallback: String,
        vararg replacements: Pair<String, String>
    ): Component {
        val template = config.getString("command-messages.$key", fallback) ?: fallback
        val resolvers = replacements.map { (placeholder, replacement) ->
            Placeholder.unparsed("pupschat_$placeholder", replacement)
        }.toTypedArray()
        val placeholderNames = replacements.map { it.first }
        val preparedTemplate = prepareCommandMessageTemplate(template, placeholderNames)
        val preparedFallback = prepareCommandMessageTemplate(fallback, placeholderNames)
        return try {
            miniMessage.deserializeChecked(preparedTemplate, *resolvers)
        } catch (_: RuntimeException) {
            if (invalidMessageKeys.add(key)) {
                logger.warning("Некорректный MiniMessage в command-messages.$key")
            }
            miniMessage.deserializeChecked(preparedFallback, *resolvers)
        }
    }
}

internal fun prepareCommandMessageTemplate(template: String, placeholders: Iterable<String>): String {
    return placeholders.fold(template) { current, placeholder ->
        current.replace("{$placeholder}", "<pupschat_$placeholder>")
    }
}
