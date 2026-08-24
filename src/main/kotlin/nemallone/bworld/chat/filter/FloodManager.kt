package nemallone.bworld.chat.filter

import nemallone.bworld.chat.PupsChat
import nemallone.bworld.chat.deserializeChecked
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class FloodManager(
    private val plugin: PupsChat,
    private val violationManager: ViolationManager
) {

    private val miniMessage = MiniMessage.miniMessage()

    private val timestampsByPlayer = ConcurrentHashMap<UUID, ArrayDeque<Long>>()

    @Volatile
    private var enabled = true

    @Volatile
    private var maxMessages = 4

    @Volatile
    private var timeWindowMs = 6_000L

    @Volatile
    private var floodMessage: Component = Component.empty()

    init {
        loadConfig()
    }

    fun loadConfig() {
        val config = plugin.config
        enabled = config.getBoolean("flood.enabled", true)
        maxMessages = config.getInt("flood.max-messages", 4).coerceAtLeast(1)
        timeWindowMs = config.getLong("flood.time-window", 6).coerceAtLeast(1) * 1000L
        val message = config.getString("flood.message") ?: DEFAULT_MESSAGE
        floodMessage = try {
            miniMessage.deserializeChecked(message)
        } catch (exception: RuntimeException) {
            plugin.logger.warning(
                "Некорректный MiniMessage в flood.message: ${exception.message}"
            )
            miniMessage.deserialize(DEFAULT_MESSAGE)
        }
    }

    fun checkFlood(player: Player): Boolean {
        if (!enabled || player.hasPermission("pupschat.bypass.flood")) return false

        val uuid = player.uniqueId
        val now = System.currentTimeMillis()
        val timestamps = timestampsByPlayer.computeIfAbsent(uuid) { ArrayDeque() }

        synchronized(timestamps) {
            while (timestamps.isNotEmpty() && now - timestamps.first() > timeWindowMs) {
                timestamps.removeFirst()
            }
            timestamps.addLast(now)

            if (timestamps.size > maxMessages) {
                timestamps.clear()
                player.sendMessage(floodMessage)
                violationManager.addViolation(player)
                return true
            }
        }

        return false
    }

    fun clearPlayer(uuid: UUID) {
        timestampsByPlayer.remove(uuid)
    }

    private companion object {
        const val DEFAULT_MESSAGE = "<color:#FF638F>Вы отправляете сообщения слишком быстро"
    }
}
