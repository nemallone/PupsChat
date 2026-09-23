package nemallone.bworld.chat.messaging

import nemallone.bworld.chat.PupsChat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import org.bukkit.plugin.IllegalPluginAccessException
import java.util.Locale

internal enum class FeedbackOutput {
    CHAT,
    ACTION_BAR;

    companion object {
        fun parse(value: Any?, path: String): FeedbackOutput {
            require(value is String) { "$path must be CHAT or ACTION_BAR" }
            return entries.firstOrNull { it.name == value.trim().replace('-', '_').uppercase(Locale.ROOT) }
                ?: throw IllegalArgumentException("$path must be CHAT or ACTION_BAR")
        }
    }
}

internal data class FeedbackSettings(
    val defaultOutput: FeedbackOutput,
    val overrides: Map<String, FeedbackOutput>,
) {
    fun output(key: String): FeedbackOutput = overrides[key] ?: defaultOutput

    companion object {
        val DEFAULT = FeedbackSettings(FeedbackOutput.CHAT, emptyMap())

        fun read(config: ConfigurationSection): FeedbackSettings {
            require(!config.contains("notifications") || config.isConfigurationSection("notifications")) {
                "notifications must be a section"
            }
            val default = FeedbackOutput.parse(config.get("notifications.default-output") ?: "CHAT", "notifications.default-output")
            val root = "notifications.overrides"
            require(!config.contains(root) || config.isConfigurationSection(root)) { "$root must be a section" }
            val overrides = config.getConfigurationSection(root)?.getValues(true)
                ?.filterValues { it !is ConfigurationSection }
                ?.mapValues { (key, value) -> FeedbackOutput.parse(value, "$root.$key") }
                ?.toMutableMap() ?: mutableMapOf()
            // При обновлении сохраняем выбор из старого конфига
            if (!config.contains("$root.settings", true)) {
                overrides["settings"] = FeedbackOutput.parse(
                    config.get("command-messages.player-settings-output") ?: "ACTION_BAR",
                    "command-messages.player-settings-output",
                )
            }
            return FeedbackSettings(default, overrides.toMap())
        }
    }
}

internal class FeedbackService(private val plugin: PupsChat) {
    @Volatile
    private var settings = FeedbackSettings.DEFAULT

    init {
        loadConfig()
    }

    fun loadConfig(): Boolean {
        return try {
            val replacement = FeedbackSettings.read(plugin.config)
            settings = replacement
            true
        } catch (failure: IllegalArgumentException) {
            plugin.logger.warning("Invalid notification settings: ${failure.message}; keeping previous settings")
            false
        }
    }

    fun send(key: String, sender: CommandSender, component: Component) {
        val output = settings.output(key)
        val delivery = Runnable {
            if (!plugin.isEnabled || sender is Player && !sender.isOnline) return@Runnable
            if (sender is Player && output == FeedbackOutput.ACTION_BAR && fitsActionBar(component)) {
                sender.sendActionBar(component)
            } else {
                sender.sendMessage(component)
            }
        }
        if (Bukkit.isPrimaryThread()) {
            delivery.run()
        } else {
            try {
                Bukkit.getScheduler().runTask(plugin, delivery)
            } catch (_: IllegalPluginAccessException) {
                // Плагин мог отключиться до постановки задачи
            }
        }
    }
}

internal fun fitsActionBar(component: Component): Boolean {
    val text = PlainTextComponentSerializer.plainText().serialize(component)
    return text.length <= 160 && '\n' !in text && '\r' !in text
}
