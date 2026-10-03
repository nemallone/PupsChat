package nemallone.bworld.chat.placeholders

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import nemallone.bworld.chat.PupsChat
import nemallone.bworld.chat.messaging.ChatHideManager
import org.bukkit.entity.Player
import java.util.Locale

internal class PupsChatExpansion(
    private val plugin: PupsChat,
    private val chatHideManager: ChatHideManager,
) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "pupschat"

    override fun getAuthor(): String = plugin.pluginMeta.authors.joinToString(", ")

    override fun getVersion(): String = plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun onPlaceholderRequest(player: Player?, params: String): String? {
        if (player == null) return ""
        return when (params.lowercase(Locale.ROOT)) {
            "chathide_mode" -> chatHideManager.getMode(player.uniqueId).name.lowercase(Locale.ROOT)
            else -> null
        }
    }
}
