package nemallone.bworld.chat.messaging

import nemallone.bworld.chat.PupsChat
import nemallone.bworld.chat.deserializeChecked
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.Locale

internal class HintManager(private val plugin: PupsChat) {

    private val miniMessage = MiniMessage.miniMessage()

    @Volatile
    private var enabled = true

    @Volatile
    private var hints: List<PreparedHint> = emptyList()

    private data class PreparedHint(
        val keywords: List<String>,
        val message: Component?,
        val sound: Sound?
    )

    init {
        loadConfig()
    }

    fun loadConfig() {
        val config = plugin.config
        enabled = config.getBoolean("hints.enabled", true)
        val preparedHints = ArrayList<PreparedHint>()

        for (entry in config.getMapList("hints.list")) {
            val keywords = ArrayList<String>()
            val configuredKeywords = entry["keywords"]
            if (configuredKeywords is List<*>) {
                for (value in configuredKeywords) {
                    val keyword = value.toString().trim().lowercase(Locale.ROOT)
                    if (keyword.isNotEmpty()) keywords.add(keyword)
                }
            }

            val rawMessage = entry["message"]?.toString() ?: ""
            val message = if (rawMessage.isEmpty()) {
                null
            } else {
                try {
                    miniMessage.deserializeChecked(rawMessage)
                } catch (_: RuntimeException) {
                    plugin.logger.warning("Некорректный MiniMessage в hints.list: $rawMessage")
                    null
                }
            }
            val soundName = entry["sound"]?.toString() ?: ""
            val volume = (entry["volume"] as? Number)?.toFloat() ?: 1.0f
            val pitch = (entry["pitch"] as? Number)?.toFloat() ?: 1.0f

            val sound = if (soundName.isEmpty()) {
                null
            } else {
                try {
                    val key = if (soundName.contains(":")) {
                        Key.key(soundName)
                    } else {
                        Key.key("minecraft", soundName)
                    }
                    Sound.sound(key, Sound.Source.MASTER, volume, pitch)
                } catch (_: RuntimeException) {
                    plugin.logger.warning("Некорректный звук в hints.list: $soundName")
                    null
                }
            }

            if (keywords.isNotEmpty()) {
                preparedHints.add(PreparedHint(keywords, message, sound))
            }
        }
        hints = preparedHints
    }

    fun checkHints(player: Player, message: String) {
        if (!enabled) return
        val lowercaseMessage = message.lowercase(Locale.ROOT)
        for (hint in hints) {
            if (hint.keywords.any { lowercaseMessage.contains(it) }) {
                sendHint(player, hint)
            }
        }
    }

    private fun sendHint(player: Player, hint: PreparedHint) {
        Bukkit.getScheduler()
            .runTaskLater(
                plugin,
                Runnable {
                    if (!player.isOnline) return@Runnable
                    val message = hint.message
                    if (message != null) player.sendMessage(message)
                    val sound = hint.sound
                    if (sound != null) player.playSound(sound)
                },
                2L
            )
    }
}
