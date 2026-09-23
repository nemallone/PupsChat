package nemallone.bworld.chat.channels

import nemallone.bworld.chat.api.ChatChannel
import java.util.UUID

internal data class ChatAuditRecord(
    val senderId: UUID,
    val channel: ChatChannel,
    val originalText: String,
    val processedText: String,
    val outcome: String,
) {
    fun finalOutcome(cancelled: Boolean): String =
        if (cancelled && outcome == ACCEPTED) CANCELLED_EXTERNAL else outcome

    companion object {
        const val ACCEPTED = "accepted-by-pupschat"
        const val CANCELLED_EXTERNAL = "cancelled-external"

        fun classify(text: BoundedChatText, settings: ChannelSettings): ChatChannel {
            // Неполный префикс считаем служебным, чтобы не раскрыть сообщение в общем журнале
            if (settings.staffEnabled && !text.complete && settings.staffPrefix.startsWith(text.text)) {
                return ChatChannel.STAFF
            }
            return ChannelPolicy.select(text.text, settings, canSendStaff = true).channel
        }
    }
}
