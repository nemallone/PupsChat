package nemallone.bworld.chat.messaging

import nemallone.bworld.chat.PupsChat
import nemallone.bworld.chat.PlayerDataSaver
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal interface DuelProvider {
    val available: Boolean
    fun getOpponent(player: Player): Player?
}

internal fun nextChatMode(
    current: ChatHideManager.Mode,
    duelAvailable: Boolean
): ChatHideManager.Mode {
    if (!duelAvailable) {
        return if (current == ChatHideManager.Mode.ALL) {
            ChatHideManager.Mode.OFF
        } else {
            ChatHideManager.Mode.ALL
        }
    }
    return when (current) {
        ChatHideManager.Mode.ALL -> ChatHideManager.Mode.DUEL
        ChatHideManager.Mode.DUEL -> ChatHideManager.Mode.OFF
        ChatHideManager.Mode.OFF -> ChatHideManager.Mode.ALL
    }
}

internal class ChatHideManager(
    private val plugin: PupsChat,
    private val duelProvider: DuelProvider?
) {
    enum class Mode {
        ALL, DUEL, OFF;

        internal fun effective(duelAvailable: Boolean): Mode =
            if (this == DUEL && !duelAvailable) ALL else this

        companion object {
            fun fromString(value: String): Mode? = when (value.lowercase(Locale.ROOT)) {
                "all" -> ALL
                "duel" -> DUEL
                "off" -> OFF
                else -> null
            }
        }
    }

    private val modes = ConcurrentHashMap<UUID, Mode>()
    private val dataSaver: PlayerDataSaver = plugin.playerDataSaver

    init {
        loadData()
    }

    private fun loadData() {
        val config = dataSaver.load(DATA_FILE_NAME) ?: return
        val section = config.getConfigurationSection("modes") ?: return

        for (key in section.getKeys(false)) {
            try {
                val mode = Mode.fromString(section.getString(key) ?: "all")
                if (mode != null && mode != Mode.ALL) modes[UUID.fromString(key)] = mode
            } catch (_: IllegalArgumentException) {
                plugin.logger.warning("Некорректный UUID в chathide.yml: $key")
            }
        }
    }

    internal fun queueSave() {
        val snapshot = modes.toMap()
        dataSaver.save(DATA_FILE_NAME) { config ->
            for ((uuid, mode) in snapshot) {
                if (mode == Mode.ALL) continue
                config.set("modes.$uuid", mode.name.lowercase(Locale.ROOT))
            }
        }
    }

    fun getMode(uuid: UUID): Mode = modes.getOrDefault(uuid, Mode.ALL)

    fun setMode(uuid: UUID, mode: Mode) {
        if (mode == Mode.ALL) modes.remove(uuid)
        else modes[uuid] = mode
        queueSave()
    }

    fun switchMode(uuid: UUID): Mode {
        val current = getMode(uuid)
        val next = nextChatMode(
            current,
            duelProvider?.available == true
        )
        setMode(uuid, next)
        return next
    }

    fun shouldHide(viewer: Player, sender: Player): Boolean {
        return when (effectiveMode(viewer.uniqueId)) {
            Mode.ALL -> false
            Mode.OFF -> true
            Mode.DUEL -> {
                val opponent = duelProvider?.getOpponent(viewer)
                opponent != null && sender.uniqueId != opponent.uniqueId
            }
        }
    }

    private fun effectiveMode(uuid: UUID): Mode {
        return getMode(uuid).effective(duelProvider?.available == true)
    }

    private companion object {
        const val DATA_FILE_NAME = "chathide.yml"
    }
}
