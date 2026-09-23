package nemallone.bworld.chat.filter.ai

import nemallone.bworld.chat.channels.BoundedChatText
import org.bukkit.configuration.file.FileConfiguration

internal data class AiFilterSettings(
    val enabled: Boolean,
    val threshold: Double,
) {
    companion object {
        fun read(config: FileConfiguration): AiFilterSettings {
            require(!config.contains("ai-filter") || config.isConfigurationSection("ai-filter")) {
                "ai-filter: требуется раздел настроек"
            }
            val enabled = config.get("ai-filter.enabled") ?: false
            require(enabled is Boolean) { "ai-filter.enabled: укажите true или false" }
            val threshold = (config.get("ai-filter.threshold") ?: 0.9) as? Number
            require(threshold != null && threshold.toDouble().isFinite() && threshold.toDouble() in 0.5..1.0) {
                "ai-filter.threshold: требуется число от 0.5 до 1.0"
            }
            if (enabled) {
                require(BoundedChatText.readMaximum(config) <= 512) {
                    "chat-processing.max-message-length: при включённом ai-filter допустимо не больше 512 символов"
                }
            }
            return AiFilterSettings(enabled, threshold.toDouble())
        }
    }
}
