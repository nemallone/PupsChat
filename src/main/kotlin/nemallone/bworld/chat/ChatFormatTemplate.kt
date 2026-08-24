package nemallone.bworld.chat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import java.util.regex.Pattern

internal class ChatFormatTemplate private constructor(
    private val pattern: String,
    private val segments: List<Segment>
) {

    val hasRestrictedPlaceholder: Boolean = segments.any { it === Segment.Restricted }

    fun render(
        playerName: String,
        message: Component?,
        restrictedValue: String,
        resolvePlaceholder: (String) -> String
    ): Component {
        val resolvers = ArrayList<TagResolver>(segments.size + 1)
        if (message != null) resolvers.add(Placeholder.component("message", message))
        for (i in segments.indices) {
            val rendered = render(segments[i], playerName, message, restrictedValue, resolvePlaceholder)
            resolvers.add(Placeholder.component(SEGMENT_TAG_PREFIX + i, rendered))
        }
        return ComponentParser.miniMessage.deserializeChecked(pattern, *resolvers.toTypedArray())
    }

    private fun render(
        segment: Segment,
        playerName: String,
        message: Component?,
        restrictedValue: String,
        resolvePlaceholder: (String) -> String
    ): Component = when (segment) {
        is Segment.Papi -> ComponentParser.parseMixed(
            segment.placeholders.joinToString("") { resolvePlaceholder(it) }
        )
        Segment.Restricted -> ComponentParser.parseMixed(restrictedValue)
        is Segment.Nick -> colorizeText(segment.metas, playerName, resolvePlaceholder)
        is Segment.Message -> colorizeMessage(
            segment.metas,
            requireNotNull(message) { "Шаблон сообщения отрисован без текста сообщения" },
            resolvePlaceholder
        )
    }

    // мета цвета и её содержимое разбираются одной строкой, иначе градиент из меты
    // растягивается на весь остаток формата, а не на ник или сообщение
    private fun colorizeText(
        metas: List<String>,
        text: String,
        resolvePlaceholder: (String) -> String
    ): Component {
        if (metas.isEmpty()) return Component.text(text)

        val mini = ComponentParser.miniMessage
        return mini.deserialize(renderMeta(metas, resolvePlaceholder) + mini.escapeTags(text))
    }

    private fun colorizeMessage(
        metas: List<String>,
        message: Component,
        resolvePlaceholder: (String) -> String
    ): Component {
        if (metas.isEmpty()) return message

        return ComponentParser.miniMessage.deserialize(
            renderMeta(metas, resolvePlaceholder) + "<message>",
            Placeholder.component("message", message)
        )
    }

    private fun renderMeta(
        metas: List<String>,
        resolvePlaceholder: (String) -> String
    ): String {
        return buildString {
            for (placeholder in metas) {
                append(ComponentParser.toMiniMessageFragment(resolvePlaceholder(placeholder)))
            }
        }
    }

    private sealed interface Segment {

        class Papi(val placeholders: List<String>) : Segment

        data object Restricted : Segment

        class Nick(val metas: List<String>) : Segment

        class Message(val metas: List<String>) : Segment
    }

    companion object {

        private const val SEGMENT_TAG_PREFIX = "pupschat_segment_"
        private val TOKEN: Pattern = Pattern.compile("%[^%]+%|\\{player}|\\{message}")

        fun compile(
            raw: String,
            restrictedPlaceholders: Collection<String> = emptyList(),
            supportsMessage: Boolean = true
        ): ChatFormatTemplate {
            val pattern = StringBuilder(raw.length + 32)
            val segments = mutableListOf<Segment>()
            val pendingMetas = mutableListOf<String>()

            fun appendSegment(segment: Segment) {
                pattern.append('<').append(SEGMENT_TAG_PREFIX).append(segments.size).append('>')
                segments.add(segment)
            }

            fun flushMetas() {
                if (pendingMetas.isEmpty()) return
                appendSegment(Segment.Papi(pendingMetas.toList()))
                pendingMetas.clear()
            }

            fun appendLiteral(text: String) {
                if (text.isEmpty()) return
                flushMetas()
                pattern.append(ComponentParser.toMiniMessageFragment(text))
            }

            fun takeMetas(): List<String> {
                val metas = pendingMetas.toList()
                pendingMetas.clear()
                return metas
            }

            val matcher = TOKEN.matcher(raw)
            var last = 0
            while (matcher.find()) {
                appendLiteral(raw.substring(last, matcher.start()))
                last = matcher.end()
                when (val token = matcher.group()) {
                    "{player}" -> appendSegment(Segment.Nick(takeMetas()))
                    "{message}" -> {
                        if (supportsMessage) appendSegment(Segment.Message(takeMetas()))
                        else appendLiteral(token)
                    }
                    in restrictedPlaceholders -> {
                        flushMetas()
                        appendSegment(Segment.Restricted)
                    }
                    else -> pendingMetas.add(token)
                }
            }
            appendLiteral(raw.substring(last))
            flushMetas()

            val template = ChatFormatTemplate(pattern.toString(), segments)
            template.render(
                playerName = "Player",
                message = Component.text("message"),
                restrictedValue = "",
                resolvePlaceholder = { it }
            )
            return template
        }
    }
}
