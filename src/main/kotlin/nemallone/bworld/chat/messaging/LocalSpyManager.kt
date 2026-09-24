package nemallone.bworld.chat.messaging

import nemallone.bworld.chat.PlayerDataSaver
import nemallone.bworld.chat.PupsChat
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class LocalSpyManager(plugin: PupsChat) {
    private val enabledPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val dataSaver: PlayerDataSaver = plugin.playerDataSaver

    init {
        val config = dataSaver.load(DATA_FILE_NAME)
        val section = config?.getConfigurationSection("enabled")
        if (section != null) {
            for (key in section.getKeys(false)) {
                try {
                    if (section.getBoolean(key, false)) enabledPlayers.add(UUID.fromString(key))
                } catch (_: IllegalArgumentException) {
                    plugin.logger.warning("Некорректный UUID в localspy.yml: $key")
                }
            }
        }
    }

    fun isEnabled(player: Player): Boolean =
        player.hasPermission(PERMISSION) && enabledPlayers.contains(player.uniqueId)

    fun switchMode(uuid: UUID): Boolean {
        val enabled = if (enabledPlayers.remove(uuid)) false else {
            enabledPlayers.add(uuid)
            true
        }
        queueSave()
        return enabled
    }

    fun queueSave() {
        val snapshot = enabledPlayers.toSet()
        dataSaver.save(DATA_FILE_NAME) { config ->
            for (uuid in snapshot) config.set("enabled.$uuid", true)
        }
    }

    companion object {
        const val PERMISSION = "pupschat.localspy"
        private const val DATA_FILE_NAME = "localspy.yml"
    }
}
