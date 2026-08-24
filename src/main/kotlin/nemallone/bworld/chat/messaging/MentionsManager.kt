package nemallone.bworld.chat.messaging

import nemallone.bworld.chat.PupsChat
import nemallone.bworld.chat.deserializeChecked
import nemallone.bworld.chat.PlayerDataSaver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class MentionsManager(private val plugin: PupsChat) {

    private val miniMessage = MiniMessage.miniMessage()
    private val toggles = ConcurrentHashMap<UUID, Boolean>()
    private val dataSaver: PlayerDataSaver = plugin.playerDataSaver

    @Volatile
    var soundName: String = "entity.experience_orb.pickup"
        private set

    @Volatile
    var soundVolume: Float = 1.0f
        private set

    @Volatile
    var soundPitch: Float = 1.0f
        private set

    @Volatile
    private var actionBarTemplate = prepareActionBarTemplate(DEFAULT_ACTION_BAR)

    init {
        loadConfig()
        loadData()
    }

    fun loadConfig() {
        val config = plugin.config
        soundName = config.getString("mentions.sound.name") ?: "entity.experience_orb.pickup"
        soundVolume = config.getDouble("mentions.sound.volume", 1.0).toFloat()
        soundPitch = config.getDouble("mentions.sound.pitch", 1.0).toFloat()
        actionBarTemplate = prepareActionBarTemplate(
            config.getString("mentions.action-bar") ?: DEFAULT_ACTION_BAR
        )
    }

    private fun loadData() {
        val config = dataSaver.load(DATA_FILE_NAME) ?: return
        val section = config.getConfigurationSection("toggles") ?: return

        for (key in section.getKeys(false)) {
            try {
                val uuid = UUID.fromString(key)
                if (!section.getBoolean(key, true)) toggles[uuid] = false
            } catch (_: IllegalArgumentException) {
                plugin.logger.warning("Некорректный UUID в mentions.yml: $key")
            }
        }
    }

    internal fun queueSave() {
        val snapshot = toggles.toMap()
        dataSaver.save(DATA_FILE_NAME) { config ->
            for ((uuid, enabled) in snapshot) {
                config.set("toggles.$uuid", enabled)
            }
        }
    }

    fun isMentionsEnabled(uuid: UUID): Boolean = toggles.getOrDefault(uuid, true)

    fun setMentionsEnabled(uuid: UUID, enabled: Boolean) {
        if (enabled) toggles.remove(uuid) else toggles[uuid] = false
        queueSave()
    }

    fun getActionBarMessage(player: String): Component {
        return miniMessage.deserialize(
            actionBarTemplate,
            Placeholder.unparsed("pupschat_player", player)
        )
    }

    private fun prepareActionBarTemplate(message: String): String {
        val template = message.replace("%player%", "<pupschat_player>")
        return try {
            miniMessage.deserializeChecked(
                template,
                Placeholder.unparsed("pupschat_player", "Player")
            )
            template
        } catch (exception: RuntimeException) {
            plugin.logger.warning(
                "Некорректный MiniMessage в mentions.action-bar: ${exception.message}"
            )
            DEFAULT_ACTION_BAR.replace("%player%", "<pupschat_player>")
        }
    }

    private companion object {
        const val DATA_FILE_NAME = "mentions.yml"
        const val DEFAULT_ACTION_BAR = "<color:#FFDD81>%player% <gray>упомянул вас в чате"
    }
}
