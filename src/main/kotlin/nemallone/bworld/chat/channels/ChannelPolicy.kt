package nemallone.bworld.chat.channels

import nemallone.bworld.chat.api.ChatChannel
import java.util.UUID

internal data class ChannelSelection(
    val channel: ChatChannel,
    val text: String,
    val consumedPrefix: Boolean,
    val rejection: ChannelRejection? = null
)

internal enum class ChannelRejection { NO_PERMISSION, EMPTY }

internal data class ChatPosition(val world: UUID, val x: Double, val y: Double, val z: Double)

internal object ChannelPolicy {
    fun select(
        text: String,
        settings: ChannelSettings,
        canSendStaff: Boolean
    ): ChannelSelection {
        val channel: ChatChannel
        var content = text
        var consumed = false
        when {
            settings.staffEnabled && text.startsWith(settings.staffPrefix) -> {
                channel = ChatChannel.STAFF
                content = text.substring(settings.staffPrefix.length).trimStart()
                consumed = true
            }
            (settings.localEnabled || settings.stripGlobalPrefixWithoutLocal) && text.startsWith(settings.globalPrefix) -> {
                channel = ChatChannel.GLOBAL
                content = text.substring(settings.globalPrefix.length).trimStart()
                consumed = true
            }
            settings.localEnabled -> channel = ChatChannel.LOCAL
            else -> channel = ChatChannel.GLOBAL
        }
        val rejection = when {
            channel == ChatChannel.STAFF && !canSendStaff -> ChannelRejection.NO_PERMISSION
            content.isBlank() -> ChannelRejection.EMPTY
            else -> null
        }
        return ChannelSelection(channel, content, consumed, rejection)
    }

    fun withinRadius(sender: ChatPosition, recipient: ChatPosition, radius: Double): Boolean {
        if (sender.world != recipient.world) return false
        val dx = sender.x - recipient.x
        val dy = sender.y - recipient.y
        val dz = sender.z - recipient.z
        return dx * dx + dy * dy + dz * dz <= radius * radius
    }
}
