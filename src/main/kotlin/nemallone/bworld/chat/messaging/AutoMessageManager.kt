package nemallone.bworld.chat.messaging

import nemallone.bworld.chat.PupsChat
import nemallone.bworld.chat.PlayerDataSaver
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val PENDING_MESSAGE_TICKS = 100L

internal enum class AutoMessageMode(val storageKey: String) {
    OFF("off"),
    DEFAULT("default"),
    TOXIC("toxic");

    fun next(): AutoMessageMode = when (this) {
        OFF -> DEFAULT
        DEFAULT -> TOXIC
        TOXIC -> OFF
    }

    companion object {
        fun fromStorageKey(value: String): AutoMessageMode? {
            return entries.firstOrNull { it.storageKey.equals(value, ignoreCase = true) }
        }
    }
}

internal class AutoMessageManager(private val plugin: PupsChat) {

    private class PendingAutoMessage(val message: String)

    private val playerModes = ConcurrentHashMap<UUID, AutoMessageMode>()
    private val pendingAutoMessages = ConcurrentHashMap<UUID, PendingAutoMessage>()
    private val dataSaver: PlayerDataSaver = plugin.playerDataSaver

    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    private var defaultMessages: List<String> = emptyList()

    @Volatile
    private var toxicMessages: List<String> = emptyList()

    init {
        loadConfig()
        loadData()
    }

    fun loadConfig() {
        val config = plugin.config
        enabled = config.getBoolean("auto-message.enabled", true)
        defaultMessages = config.getStringList("auto-message.messages.default").toList()
        toxicMessages = config.getStringList("auto-message.messages.toxic").toList()
    }

    fun getMode(uuid: UUID): AutoMessageMode {
        return playerModes.getOrDefault(uuid, AutoMessageMode.OFF)
    }

    fun setMode(uuid: UUID, mode: AutoMessageMode) {
        if (mode == AutoMessageMode.OFF) playerModes.remove(uuid) else playerModes[uuid] = mode
        queueSave()
    }

    fun switchMode(uuid: UUID): AutoMessageMode {
        val next = getMode(uuid).next()
        setMode(uuid, next)
        return next
    }

    fun getRandomMessage(mode: AutoMessageMode): String? = when (mode) {
        AutoMessageMode.DEFAULT -> defaultMessages.randomOrNull()
        AutoMessageMode.TOXIC -> toxicMessages.randomOrNull()
        AutoMessageMode.OFF -> null
    }

    fun markAutoMessage(uuid: UUID, message: String) {
        val pending = PendingAutoMessage(message)
        pendingAutoMessages[uuid] = pending
        Bukkit.getScheduler().runTaskLater(
            plugin,
            Runnable { pendingAutoMessages.remove(uuid, pending) },
            PENDING_MESSAGE_TICKS
        )
    }

    fun consumeAutoMessage(uuid: UUID, message: String): Boolean {
        val pending = pendingAutoMessages[uuid] ?: return false
        return pending.message == message && pendingAutoMessages.remove(uuid, pending)
    }

    private fun loadData() {
        val config = dataSaver.load(DATA_FILE_NAME) ?: return
        val section = config.getConfigurationSection("modes") ?: return
        for (key in section.getKeys(false)) {
            try {
                val storedMode = section.getString(key) ?: continue
                val mode = AutoMessageMode.fromStorageKey(storedMode)
                if (mode == AutoMessageMode.DEFAULT || mode == AutoMessageMode.TOXIC) {
                    playerModes[UUID.fromString(key)] = mode
                }
            } catch (_: IllegalArgumentException) {
                plugin.logger.warning("Некорректный UUID в automessage.yml: $key")
            }
        }
    }

    internal fun queueSave() {
        val snapshot = playerModes.toMap()
        dataSaver.save(DATA_FILE_NAME) { config ->
            for ((uuid, mode) in snapshot) config.set("modes.$uuid", mode.storageKey)
        }
    }

    private companion object {
        const val DATA_FILE_NAME = "automessage.yml"
    }
}
