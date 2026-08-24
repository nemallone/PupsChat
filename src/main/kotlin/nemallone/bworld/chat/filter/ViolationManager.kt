package nemallone.bworld.chat.filter

import nemallone.bworld.chat.PupsChat
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class ViolationManager(
    private val plugin: PupsChat,
    private val muteManager: MuteManager
) {

    private val violations = ConcurrentHashMap<UUID, ArrayDeque<Long>>()

    @Volatile
    private var enabled = true

    @Volatile
    private var maxViolations = 3

    @Volatile
    private var expireMs = 180_000L

    init {
        loadConfig()

        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable {
            val now = System.currentTimeMillis()
            for (uuid in violations.keys) {
                violations.computeIfPresent(uuid) { _, list ->
                    discardExpired(list, now)
                    if (list.isEmpty()) null else list
                }
            }
        }, CLEANUP_INTERVAL_TICKS, CLEANUP_INTERVAL_TICKS)
    }

    fun loadConfig() {
        val config = plugin.config
        enabled = config.getBoolean("punishment.enabled", true)
        maxViolations = config.getInt("punishment.max-violations", 3).coerceAtLeast(1)
        expireMs = config.getLong("punishment.violation-expire-seconds", 180)
            .coerceAtLeast(1) * 1000L
    }

    fun addViolation(player: Player): Int {
        if (!enabled) return 0

        val uuid = player.uniqueId
        val now = System.currentTimeMillis()
        var count = 0
        var shouldMute = false

        violations.compute(uuid) { _, existingTimestamps ->
            val timestamps = existingTimestamps ?: ArrayDeque()
            discardExpired(timestamps, now)
            timestamps.addLast(now)
            count = timestamps.size

            if (count >= maxViolations) {
                shouldMute = true
                timestamps.clear()
            }
            timestamps
        }

        if (shouldMute) muteManager.mute(player)
        return count
    }

    private fun discardExpired(timestamps: ArrayDeque<Long>, now: Long) {
        while (timestamps.isNotEmpty() && now - timestamps.first() > expireMs) {
            timestamps.removeFirst()
        }
    }

    private companion object {
        const val CLEANUP_INTERVAL_TICKS = 20L * 60
    }
}
