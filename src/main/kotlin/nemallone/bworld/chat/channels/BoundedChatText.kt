package nemallone.bworld.chat.channels

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.KeybindComponent
import net.kyori.adventure.text.ScoreComponent
import net.kyori.adventure.text.SelectorComponent
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.TranslatableComponent
import org.bukkit.configuration.ConfigurationSection
import java.util.ArrayDeque

// При превышении лимита сохраняем начало текста для журнала
internal data class BoundedChatText(val text: String, val complete: Boolean) {
    companion object {
        const val DEFAULT_MAX_LENGTH = 512
        private const val MAX_COMPONENT_NODES = 256

        fun readMaximum(config: ConfigurationSection): Int {
            val path = "chat-processing.max-message-length"
            if (!config.contains(path)) return DEFAULT_MAX_LENGTH
            val value = (config.get(path) as? Number)?.toDouble()
            require(value != null && value.isFinite() && value in 32.0..1024.0 && value % 1.0 == 0.0) {
                "$path must be an integer between 32 and 1024"
            }
            return value.toInt()
        }

        fun read(component: Component, maxLength: Int): BoundedChatText {
            require(maxLength in 32..1024)
            val text = StringBuilder(minOf(maxLength, 128))
            val pending = ArrayDeque<Component>()
            pending.add(component)
            var visited = 0
            while (pending.isNotEmpty()) {
                if (++visited > MAX_COMPONENT_NODES) return BoundedChatText(text.toString(), false)
                val current = pending.removeLast()
                val content = when (current) {
                    is TextComponent -> current.content()
                    is TranslatableComponent -> {
                        // Аргументы перевода не входят в children и могут скрывать текст от фильтров
                        if (current.arguments().isNotEmpty()) return BoundedChatText(text.toString(), false)
                        current.fallback() ?: current.key()
                    }
                    is KeybindComponent -> current.keybind()
                    is SelectorComponent -> current.pattern()
                    is ScoreComponent -> current.value().orEmpty()
                    else -> return BoundedChatText(text.toString(), false)
                }
                val available = maxLength - text.length
                if (content.length > available) {
                    text.append(content, 0, available)
                    return BoundedChatText(text.toString(), false)
                }
                text.append(content)
                val children = current.children()
                // Проверяем размер до добавления дочерних компонентов в очередь
                if (visited + pending.size + children.size > MAX_COMPONENT_NODES) {
                    return BoundedChatText(text.toString(), false)
                }
                for (index in children.indices.reversed()) pending.addLast(children[index])
            }
            return BoundedChatText(text.toString(), true)
        }
    }
}
