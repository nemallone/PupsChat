package nemallone.bworld.chat.api

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Вызывается на основном потоке после фильтров, до упоминаний и отправки
 * Можно отменить сообщение или убрать получателей; добавленные получатели игнорируются
 * Канал и текст неизменяемы; отправитель получает свою реплику, если событие не отменено
 */
class PupsChatMessageEvent(
    val sender: Player,
    val channel: ChatChannel,
    val message: Component,
    val recipients: MutableSet<Audience>
) : Event(), Cancellable {
    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
