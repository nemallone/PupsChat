package nemallone.bworld.chat.filter

import nemallone.bworld.chat.PupsChat
import nemallone.bworld.chat.deserializeChecked
import nemallone.bworld.chat.PlayerDataSaver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class MuteManager(private val plugin: PupsChat) {

    private data class EscalationState(
        val tier: Int,
        val lastMuteAt: Long
    )

    private val miniMessage = MiniMessage.miniMessage()

    private val mutes = MuteRegistry()
    private val escalationStates = ConcurrentHashMap<UUID, EscalationState>()
    private val dataSaver: PlayerDataSaver = plugin.playerDataSaver

    @Volatile
    private var enabled = true

    @Volatile
    private var muteDurations = listOf(2, 5, 10, 15)

    @Volatile
    private var escalationResetMs = 1_800_000L

    @Volatile
    private var muteMessageTemplate = prepareTemplate(
        DEFAULT_MUTE_MESSAGE,
        "punishment.mute-message",
        DEFAULT_MUTE_MESSAGE
    )

    @Volatile
    private var mutedMessageTemplate = prepareTemplate(
        DEFAULT_MUTED_MESSAGE,
        "punishment.muted-message",
        DEFAULT_MUTED_MESSAGE
    )

    init {
        loadConfig()
        loadData()

        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable {
            val now = System.currentTimeMillis()
            mutes.removeExpired()
            for ((uuid, state) in escalationStates.entries) {
                if (now - state.lastMuteAt > escalationResetMs) {
                    escalationStates.remove(uuid, state)
                }
            }
        }, CLEANUP_INTERVAL_TICKS, CLEANUP_INTERVAL_TICKS)
    }

    fun loadConfig() {
        val config = plugin.config
        enabled = config.getBoolean("punishment.enabled", true)
        val durations = config.getIntegerList("punishment.mute-durations").filter { it > 0 }
        muteDurations = durations.ifEmpty { listOf(2, 5, 10, 15) }
        escalationResetMs = config.getLong("punishment.escalation-reset-minutes", 30)
            .coerceAtLeast(1) * MILLISECONDS_PER_MINUTE
        muteMessageTemplate = prepareTemplate(
            config.getString("punishment.mute-message") ?: DEFAULT_MUTE_MESSAGE,
            "punishment.mute-message",
            DEFAULT_MUTE_MESSAGE
        )
        mutedMessageTemplate = prepareTemplate(
            config.getString("punishment.muted-message") ?: DEFAULT_MUTED_MESSAGE,
            "punishment.muted-message",
            DEFAULT_MUTED_MESSAGE
        )
    }

    fun mute(player: Player) {
        if (!enabled) return

        val uuid = player.uniqueId
        val now = System.currentTimeMillis()
        val durations = muteDurations
        val state = escalationStates.compute(uuid) { _, current ->
            val currentTier = if (
                current == null || now - current.lastMuteAt > escalationResetMs
            ) {
                0
            } else {
                current.tier.coerceAtLeast(0)
            }
            val index = minOf(currentTier, durations.lastIndex)
            EscalationState(index + 1, now)
        }
        val minutes = durations[requireNotNull(state).tier - 1]

        mutes.put(uuid, player.name, now + minutes * MILLISECONDS_PER_MINUTE)
        queueSave()

        player.sendMessage(render(muteMessageTemplate, formatMinutes(minutes)))
    }

    fun findMutedPlayerId(playerName: String): UUID? = mutes.findActivePlayerId(playerName)

    fun unmute(uuid: UUID): Boolean {
        val result = mutes.unmute(uuid)
        if (result) queueSave()
        return result
    }

    fun rejectIfMuted(player: Player): Boolean {
        if (!enabled || player.hasPermission("pupschat.bypass.mute")) return false

        val expiration = mutes.expiration(player.uniqueId) ?: return false
        val remaining = expiration - System.currentTimeMillis()

        if (remaining > 0) {
            player.sendMessage(render(mutedMessageTemplate, formatRemaining(remaining)))
            return true
        }

        mutes.unmute(player.uniqueId)
        queueSave()
        return false
    }

    private fun loadData() {
        val config = dataSaver.load(DATA_FILE_NAME) ?: return
        val now = System.currentTimeMillis()

        val mutesSection = config.getConfigurationSection("mutes")
        if (mutesSection != null) {
            val loadedMutes = HashMap<UUID, StoredMute>()
            for (key in mutesSection.getKeys(false)) {
                val uuid = parsePlayerId(key) ?: continue
                val name = mutesSection.getString("$key.name") ?: key
                val expires = mutesSection.getLong("$key.expires")
                if (expires > now) {
                    loadedMutes[uuid] = StoredMute(name, expires)
                }
            }
            mutes.restore(loadedMutes)
        }

        val loadedTiers = HashMap<UUID, Int>()
        val tiersSection = config.getConfigurationSection("tiers")
        if (tiersSection != null) {
            for (key in tiersSection.getKeys(false)) {
                val uuid = parsePlayerId(key) ?: continue
                val tier = tiersSection.getInt(key)
                if (tier < 0) {
                    plugin.logger.warning("Отрицательный уровень мута в $DATA_FILE_NAME: $key")
                    continue
                }
                loadedTiers[uuid] = minOf(tier, muteDurations.size)
            }
        }

        val loadedTimes = HashMap<UUID, Long>()
        val timesSection = config.getConfigurationSection("last-mute-times")
        if (timesSection != null) {
            for (key in timesSection.getKeys(false)) {
                val uuid = parsePlayerId(key) ?: continue
                loadedTimes[uuid] = timesSection.getLong(key)
            }
        }

        for ((uuid, tier) in loadedTiers) {
            escalationStates[uuid] = EscalationState(tier, loadedTimes[uuid] ?: 0L)
        }
        for ((uuid, lastMuteAt) in loadedTimes) {
            escalationStates.putIfAbsent(uuid, EscalationState(0, lastMuteAt))
        }
    }

    private fun parsePlayerId(value: String): UUID? {
        return try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            plugin.logger.warning("Некорректный UUID в $DATA_FILE_NAME: $value")
            null
        }
    }

    internal fun queueSave() {
        val muteSnapshot = mutes.snapshot()
        val escalationSnapshot = escalationStates.toMap()
        dataSaver.save(DATA_FILE_NAME) { config ->
            for ((uuid, mute) in muteSnapshot) {
                config.set("mutes.$uuid.name", mute.playerName)
                config.set("mutes.$uuid.expires", mute.expiresAt)
            }
            for ((uuid, state) in escalationSnapshot) {
                config.set("tiers.$uuid", state.tier)
                config.set("last-mute-times.$uuid", state.lastMuteAt)
            }
        }
    }

    private fun formatMinutes(minutes: Int): String = "${minutes}м"

    private fun formatRemaining(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1000) + 1
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return when {
            minutes <= 0 -> "${seconds}с"
            seconds == 0L -> "${minutes}м"
            else -> "${minutes}м ${seconds}с"
        }
    }

    private fun prepareTemplate(message: String, path: String, fallback: String): String {
        val template = message.replace("%time%", "<pupschat_time>")
        return try {
            miniMessage.deserializeChecked(
                template,
                Placeholder.unparsed("pupschat_time", "1м")
            )
            template
        } catch (exception: RuntimeException) {
            plugin.logger.warning("Некорректный MiniMessage в $path: ${exception.message}")
            fallback.replace("%time%", "<pupschat_time>")
        }
    }

    private fun render(template: String, time: String): Component {
        return miniMessage.deserialize(template, Placeholder.unparsed("pupschat_time", time))
    }

    private companion object {
        const val DATA_FILE_NAME = "mutes.yml"
        const val MILLISECONDS_PER_MINUTE = 60_000L
        const val CLEANUP_INTERVAL_TICKS = 20L * 300
        const val DEFAULT_MUTE_MESSAGE =
            "<color:#FF638F>Чат временно недоступен. Длительность мута: %time%"
        const val DEFAULT_MUTED_MESSAGE =
            "<color:#FF638F>До снятия мута осталось %time%"
    }
}
