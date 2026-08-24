package nemallone.bworld.chat.messaging

import nemallone.bworld.chat.PupsChat
import nemallone.bworld.chat.deserializeChecked
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.Locale

internal class AnnouncementManager(private val plugin: PupsChat) {

    private val miniMessage = MiniMessage.miniMessage()

    private var enabled = false
    private var intervalSeconds = 300
    private var currentIndex = 0
    private var task: BukkitTask? = null

    private val announcements = ArrayList<PreparedAnnouncement>()

    private data class PreparedAnnouncement(
        val message: Component,
        val sound: Sound,
        val bypassPermission: String?
    )

    init {
        loadConfig()
        startTask()
    }

    private fun loadConfig() {
        val file = File(plugin.dataFolder, "announcements.yml")
        if (!file.exists()) plugin.saveResource("announcements.yml", false)

        val config = YamlConfiguration.loadConfiguration(file)
        enabled = config.getBoolean("enabled", false)
        intervalSeconds = config.getInt("interval", 300).coerceAtLeast(1)
        announcements.clear()
        currentIndex = 0

        if (!config.contains("messages")) return
        val messagesList = config.getList("messages") ?: return

        for (entry in messagesList) {
            if (entry !is Map<*, *>) continue

            val lines = ArrayList<String>()
            val configuredLines = entry["lines"]
            if (configuredLines is List<*>) {
                for (line in configuredLines) lines.add(line.toString())
            }
            if (lines.isEmpty()) continue

            val message = try {
                var component = Component.empty()
                for (i in lines.indices) {
                    if (i > 0) component = component.append(Component.newline())
                    component = component.append(miniMessage.deserializeChecked(lines[i]))
                }
                component
            } catch (exception: RuntimeException) {
                plugin.logger.warning(
                    "Некорректное объявление пропущено: ${exception.message}"
                )
                continue
            }

            var soundName = "block.note_block.pling"
            var soundVolume = 1.0f
            var soundPitch = 1.0f

            val soundMap = entry["sound"]
            if (soundMap is Map<*, *>) {
                soundName = soundMap["name"]?.toString() ?: soundName
                soundVolume = (soundMap["volume"] as? Number)?.toFloat() ?: 1.0f
                soundPitch = (soundMap["pitch"] as? Number)?.toFloat() ?: 1.0f
            }

            val sound = try {
                val normalizedSoundName = soundName.lowercase(Locale.ROOT)
                val soundKey = if (normalizedSoundName.contains(":")) Key.key(normalizedSoundName)
                else Key.key("minecraft", normalizedSoundName)
                Sound.sound(soundKey, Sound.Source.MASTER, soundVolume, soundPitch)
            } catch (_: RuntimeException) {
                plugin.logger.warning(
                    "Звук '$soundName' не найден, используется block.note_block.pling"
                )
                Sound.sound(
                    Key.key("minecraft", "block.note_block.pling"),
                    Sound.Source.MASTER,
                    soundVolume,
                    soundPitch
                )
            }

            val bypassPermission = entry["bypass-permission"]
                ?.toString()
                ?.takeIf(String::isNotBlank)

            announcements.add(PreparedAnnouncement(message, sound, bypassPermission))
        }
    }

    private fun startTask() {
        stopTask()
        if (!enabled || announcements.isEmpty()) return

        val periodTicks = intervalSeconds * 20L
        task = Bukkit.getScheduler().runTaskTimer(
            plugin,
            Runnable {
                val announcement = announcements[currentIndex]
                currentIndex = (currentIndex + 1) % announcements.size

                for (player in Bukkit.getOnlinePlayers()) {
                    val permission = announcement.bypassPermission
                    if (permission != null && player.hasPermission(permission)) continue

                    player.sendMessage(announcement.message)
                    player.playSound(announcement.sound)
                }
            },
            periodTicks,
            periodTicks
        )
    }

    fun stopTask() {
        task?.cancel()
        task = null
    }

    fun reload() {
        loadConfig()
        startTask()
    }
}
