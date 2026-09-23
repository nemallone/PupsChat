package nemallone.bworld.chat

import io.papermc.paper.chat.ChatRenderer
import nemallone.bworld.chat.api.ChatChannel
import net.kyori.adventure.audience.Audience
import me.clip.placeholderapi.PlaceholderAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.configuration.file.FileConfiguration
import java.util.Collections
import java.util.IdentityHashMap

internal class ChatFormatter(private val plugin: PupsChat) {

    private class Settings(
        val enabled: Boolean,
        val format: ChatFormatTemplate,
        val channelFormats: Map<ChatChannel, ChatFormatTemplate>,
        val hoverEnabled: Boolean,
        val hoverLines: List<ChatFormatTemplate>,
        val restrictedPermission: String,
        val restrictedLines: List<ChatFormatTemplate>,
        val bypassPermission: String,
        val clickCommand: String,
        val placeholderApiAvailable: Boolean,
        val restrictedPlaceholder: String,
        val hiddenText: String
    )

    private var placeholderFailureLogged = false

    @Volatile
    private var settings = defaultSettings()

    fun loadConfig(): Boolean {
        placeholderFailureLogged = false
        return try {
            settings = readSettings()
            true
        } catch (exception: RuntimeException) {
            plugin.logger.warning(
                "Некорректный chat-format, используются предыдущие настройки: " +
                    (exception.message ?: exception.javaClass.simpleName)
            )
            false
        }
    }

    fun validateConfig(config: FileConfiguration) {
        readSettings(config)
    }

    private fun readSettings(config: FileConfiguration = plugin.config): Settings {
        val placeholderApiAvailable = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
        val format = config.getString("chat-format.format", DEFAULT_FORMAT) ?: DEFAULT_FORMAT
        val channelFormats = buildMap {
            config.getString("channels.global.format")?.takeIf(String::isNotBlank)?.let { put(ChatChannel.GLOBAL, it) }
            config.getString("channels.local.format")?.takeIf(String::isNotBlank)?.let { put(ChatChannel.LOCAL, it) }
            put(ChatChannel.STAFF, config.getString("staff-chat.format", DEFAULT_STAFF_FORMAT) ?: DEFAULT_STAFF_FORMAT)
        }
        val allFormats = channelFormats.values + format
        val restrictedPlaceholder = config.getString(
            "chat-format.restricted-placeholder.value",
            ""
        ) ?: ""
        val restrictedPlaceholders = if (placeholderApiAvailable) {
            buildList {
                if (restrictedPlaceholder.isNotEmpty()) add(restrictedPlaceholder)
                addAll(
                    config.getStringList("chat-format.restricted-placeholder.aliases")
                        .filter(String::isNotEmpty)
                )
            }.distinct().filter { placeholder -> allFormats.any { it.contains(placeholder) } }
        } else {
            emptyList()
        }
        return Settings(
            enabled = config.getBoolean("chat-format.enabled", true),
            format = ChatFormatTemplate.compile(format, restrictedPlaceholders),
            channelFormats = channelFormats.mapValues { ChatFormatTemplate.compile(it.value, restrictedPlaceholders) },
            hoverEnabled = config.getBoolean("chat-format.hover.enabled", false),
            hoverLines = compileLines(config.getStringList("chat-format.hover.lines")),
            restrictedPermission = config.getString("chat-format.hover.permission", "") ?: "",
            restrictedLines = compileLines(
                config.getStringList("chat-format.hover.permission-lines")
            ),
            bypassPermission = config.getString(
                "chat-format.hover.bypass-permission",
                ""
            ) ?: "",
            clickCommand = config.getString("chat-format.hover.click-command", "") ?: "",
            placeholderApiAvailable = placeholderApiAvailable,
            restrictedPlaceholder = restrictedPlaceholders.firstOrNull() ?: "",
            hiddenText = config.getString(
                "chat-format.restricted-placeholder.hidden-text",
                DEFAULT_HIDDEN_TEXT
            ) ?: DEFAULT_HIDDEN_TEXT
        )
    }

    fun renderer(sender: Player, message: Component, channel: ChatChannel, viewers: Set<Audience>): ChatRenderer? {
        val current = settings
        if (!current.enabled) return null
        val format = current.channelFormats[channel] ?: current.format

        val resolver = placeholderResolver(sender, current.placeholderApiAvailable)
        val hasRestricted = format.hasRestrictedPlaceholder
        val ownerRestricted = current.restrictedPermission.isNotEmpty() &&
            sender.hasPermission(current.restrictedPermission)
        val visibleValue = if (hasRestricted) {
            resolveRestrictedPlaceholder(sender, current.restrictedPlaceholder)
        } else {
            ""
        }

        fun buildBase(restrictedValue: String): ChatFormatTemplate.Prepared =
            format.prepare(sender.name, restrictedValue, resolver)

        val visibleBase = buildBase(visibleValue)
        val restrictedBase = if (ownerRestricted && hasRestricted) {
            buildBase(current.hiddenText)
        } else {
            visibleBase
        }
        val clickEvent = prepareClick(sender, current)
        val hoverActive = current.hoverEnabled &&
            (current.hoverLines.isNotEmpty() || current.restrictedLines.isNotEmpty())

        fun renderForViewer(showFullDetails: Boolean): (Component) -> Component {
            val base = if (showFullDetails) visibleBase else restrictedBase
            val hover = if (hoverActive) {
                val hoverLines = if (showFullDetails) {
                    current.hoverLines.ifEmpty { current.restrictedLines }
                } else {
                    current.restrictedLines.ifEmpty { current.hoverLines }
                }
                buildHover(sender, hoverLines, resolver)
            } else null
            return prepareMessageRenderer(base, message, hover, clickEvent)
        }

        return when {
            !ownerRestricted -> {
                val render = renderForViewer(true)
                ChatRenderer { _, _, finalMessage, _ -> render(finalMessage) }
            }
            current.bypassPermission.isEmpty() -> {
                val render = renderForViewer(false)
                ChatRenderer { _, _, finalMessage, _ -> render(finalMessage) }
            }
            else -> {
                val full = renderForViewer(true)
                val restricted = renderForViewer(false)
                val bypassViewers = Collections.newSetFromMap(
                    IdentityHashMap<Player, Boolean>()
                )
                for (viewer in viewers) {
                    if (viewer is Player && viewer.hasPermission(current.bypassPermission)) {
                        bypassViewers.add(viewer)
                    }
                }
                ChatRenderer { _, _, finalMessage, viewer ->
                    if (viewer is Player && viewer in bypassViewers) {
                        full(finalMessage)
                    } else {
                        restricted(finalMessage)
                    }
                }
            }
        }
    }

    private fun prepareClick(sender: Player, settings: Settings): ClickEvent? {
        if (settings.clickCommand.isEmpty()) return null
        var command = settings.clickCommand.replace("{player}", sender.name)
        if (settings.placeholderApiAvailable) {
            command = resolvePapiValue(sender, command) ?: command
        }
        return ClickEvent.suggestCommand(command)
    }

    private fun resolveRestrictedPlaceholder(sender: Player, placeholder: String): String {
        if (placeholder.isEmpty()) return ""
        val resolved = resolvePapiValue(sender, placeholder) ?: return ""
        return if (resolved == placeholder) "" else resolved
    }

    private fun placeholderResolver(
        player: Player,
        placeholderApiAvailable: Boolean
    ): (String) -> String = if (placeholderApiAvailable) {
        { resolvePapiValue(player, it) ?: it }
    } else {
        { it }
    }

    private fun resolvePapiValue(player: Player, value: String): String? {
        return try {
            PlaceholderAPI.setPlaceholders(player, value)
        } catch (exception: RuntimeException) {
            logPlaceholderFailure(exception)
            null
        } catch (exception: LinkageError) {
            logPlaceholderFailure(exception)
            null
        }
    }

    private fun logPlaceholderFailure(exception: Throwable) {
        if (placeholderFailureLogged) return
        placeholderFailureLogged = true
        plugin.logger.warning(
            "Ошибка PlaceholderAPI, плейсхолдеры оставлены без изменений: " +
                exception.javaClass.simpleName
        )
    }

    private fun compileLines(lines: List<String>) =
        lines.map { ChatFormatTemplate.compile(it, supportsMessage = false) }

    private fun buildHover(
        player: Player,
        lines: List<ChatFormatTemplate>,
        resolver: (String) -> String
    ): Component {
        var hover = Component.empty()
        for (index in lines.indices) {
            if (index > 0) hover = hover.append(Component.newline())
            hover = hover.append(lines[index].render(player.name, null, "", resolver))
        }
        return hover
    }

    private fun defaultSettings() = Settings(
        enabled = true,
        format = ChatFormatTemplate.compile(DEFAULT_FORMAT),
        channelFormats = mapOf(ChatChannel.STAFF to ChatFormatTemplate.compile(DEFAULT_STAFF_FORMAT)),
        hoverEnabled = false,
        hoverLines = emptyList(),
        restrictedPermission = "",
        restrictedLines = emptyList(),
        bypassPermission = "",
        clickCommand = "",
        placeholderApiAvailable = false,
        restrictedPlaceholder = "",
        hiddenText = DEFAULT_HIDDEN_TEXT
    )

    private companion object {
        const val DEFAULT_FORMAT = "<gray>{player} <dark_gray>→ <white>{message}"
        const val DEFAULT_STAFF_FORMAT = "<color:#FF916E>[ꜱᴛᴀꜰꜰ] <white>{player} → {message}"
        const val DEFAULT_HIDDEN_TEXT = "<gray>???"
    }
}

internal fun prepareMessageRenderer(
    template: ChatFormatTemplate.Prepared,
    originalMessage: Component,
    hover: Component?,
    click: ClickEvent?
): (Component) -> Component {
    fun render(message: Component): Component {
        var component = template.render(message)
        if (hover != null) component = component.hoverEvent(HoverEvent.showText(hover))
        if (click != null) component = component.clickEvent(click)
        return component
    }
    // Обычное сообщение повторно не разбираем, изменения других плагинов сохраняем
    val original = render(originalMessage)
    return { message -> if (message == originalMessage) original else render(message) }
}
