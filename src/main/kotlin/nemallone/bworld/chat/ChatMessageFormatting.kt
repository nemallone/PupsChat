package nemallone.bworld.chat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

internal object ChatMessageFormatting {
    private const val PERMISSION_PREFIX = "pupschat.format."
    private val colors = mapOf(
        '0' to NamedTextColor.BLACK, '1' to NamedTextColor.DARK_BLUE,
        '2' to NamedTextColor.DARK_GREEN, '3' to NamedTextColor.DARK_AQUA,
        '4' to NamedTextColor.DARK_RED, '5' to NamedTextColor.DARK_PURPLE,
        '6' to NamedTextColor.GOLD, '7' to NamedTextColor.GRAY,
        '8' to NamedTextColor.DARK_GRAY, '9' to NamedTextColor.BLUE,
        'a' to NamedTextColor.GREEN, 'b' to NamedTextColor.AQUA,
        'c' to NamedTextColor.RED, 'd' to NamedTextColor.LIGHT_PURPLE,
        'e' to NamedTextColor.YELLOW, 'f' to NamedTextColor.WHITE
    )
    private val decorations = mapOf(
        'k' to ("obfuscated" to TextDecoration.OBFUSCATED),
        'l' to ("bold" to TextDecoration.BOLD),
        'm' to ("strikethrough" to TextDecoration.STRIKETHROUGH),
        'n' to ("underline" to TextDecoration.UNDERLINED),
        'o' to ("italic" to TextDecoration.ITALIC)
    )
    private val allDecorations = decorations.values.map { it.second }

    private data class Code(
        val length: Int,
        val permission: String,
        val color: TextColor? = null,
        val decoration: TextDecoration? = null,
        val reset: Boolean = false
    )

    private class State {
        var color: TextColor? = null
        var colorVersion = 0
        var resetDecorations = false
        val decorations = mutableSetOf<TextDecoration>()
        val decorationVersions = IntArray(allDecorations.size)

        val active: Boolean get() = color != null || decorations.isNotEmpty()

        fun apply(code: Code) {
            when {
                code.reset -> {
                    color = NamedTextColor.WHITE
                    resetDecorations = true
                    decorations.clear()
                    colorVersion++
                    for (index in decorationVersions.indices) decorationVersions[index]++
                }
                code.color != null -> {
                    color = code.color
                    resetDecorations = true
                    decorations.clear()
                    colorVersion++
                    for (index in decorationVersions.indices) decorationVersions[index]++
                }
                code.decoration != null -> {
                    decorations.add(code.decoration)
                    decorationVersions[allDecorations.indexOf(code.decoration)]++
                }
            }
        }

        fun style(context: StyleContext): Style {
            var style = Style.empty()
            if (color != null && colorVersion > context.colorBarrier) {
                style = style.color(color)
            }
            for ((index, decoration) in allDecorations.withIndex()) {
                if (decorationVersions[index] <= context.decorationBarriers[index]) continue
                if (decoration in decorations) {
                    style = style.decoration(decoration, TextDecoration.State.TRUE)
                } else if (resetDecorations) {
                    style = style.decoration(decoration, TextDecoration.State.FALSE)
                }
            }
            return style
        }
    }

    private class StyleContext(
        val colorBarrier: Int = -1,
        val decorationBarriers: IntArray = IntArray(allDecorations.size) { -1 }
    ) {
        fun with(original: Style, state: State): StyleContext {
            if (original.color() == null && allDecorations.all {
                original.decoration(it) == TextDecoration.State.NOT_SET
            }) return this
            val color = if (original.color() != null) state.colorVersion else colorBarrier
            val barriers = decorationBarriers.copyOf()
            for ((index, decoration) in allDecorations.withIndex()) {
                if (original.decoration(decoration) != TextDecoration.State.NOT_SET) {
                    barriers[index] = state.decorationVersions[index]
                }
            }
            return StyleContext(color, barriers)
        }
    }

    fun apply(message: Component, hasPermission: (String) -> Boolean): Component {
        val state = State()
        val permissions = HashMap<String, Boolean>()

        fun transform(component: Component, parentContext: StyleContext): Component {
            val context = parentContext.with(component.style(), state)
            val text = component as? TextComponent
            val parsed = text?.content()?.takeIf(String::isNotEmpty)?.let { content ->
                parse(content, context, state) { permission ->
                    permissions.getOrPut(permission) { hasPermission(permission) }
                }
            }
            val children = component.children()
            val mappedChildren = children.map { transform(it, context) }
            val childrenChanged = children.indices.any { children[it] !== mappedChildren[it] }
            if (parsed?.first != true && !childrenChanged) return component
            if (text != null && parsed?.first == true) {
                return text.content("").children(parsed.second + mappedChildren)
            }
            return component.children(mappedChildren)
        }

        return transform(message, StyleContext())
    }

    fun visibleText(message: String, hasPermission: (String) -> Boolean): String {
        if ('&' !in message) return message
        val visible = StringBuilder(message.length)
        val permissions = HashMap<String, Boolean>()
        var index = 0
        while (index < message.length) {
            val code = if (message[index] == '&') readCode(message, index) else null
            if (code != null && permissions.getOrPut(code.permission) { hasPermission(code.permission) }) {
                index += code.length
            } else if (code != null) {
                visible.append(message, index, index + code.length)
                index += code.length
            } else {
                visible.append(message[index++])
            }
        }
        return visible.toString()
    }

    private fun parse(
        content: String,
        context: StyleContext,
        state: State,
        hasPermission: (String) -> Boolean
    ): Pair<Boolean, List<Component>> {
        val parts = ArrayList<Component>()
        val literal = StringBuilder(content.length)
        var changed = false

        fun flush() {
            if (literal.isEmpty()) return
            parts.add(Component.text(literal.toString()).style(state.style(context)))
            literal.setLength(0)
        }

        var index = 0
        while (index < content.length) {
            val code = if (content[index] == '&') readCode(content, index) else null
            if (code == null) {
                literal.append(content[index++])
                continue
            }
            if (hasPermission(code.permission)) {
                flush()
                state.apply(code)
                changed = true
            } else {
                literal.append(content, index, index + code.length)
            }
            index += code.length
        }
        if (changed || state.active) flush()
        return (changed || state.active) to parts
    }

    private fun readCode(content: String, index: Int): Code? {
        if (index + 1 >= content.length) return null
        val symbol = content[index + 1].lowercaseChar()
        if (symbol == '#' && index + 8 <= content.length &&
            (index + 2 until index + 8).all { content[it].isHexDigit() }
        ) {
            val color = TextColor.color(content.substring(index + 2, index + 8).toInt(16))
            return Code(8, PERMISSION_PREFIX + "hex", color = color)
        }
        if (symbol == 'x' && index + 14 <= content.length) {
            val digits = CharArray(6)
            for (digit in digits.indices) {
                val separator = index + 2 + digit * 2
                if (content[separator] != '&' || !content[separator + 1].isHexDigit()) return null
                digits[digit] = content[separator + 1]
            }
            return Code(14, PERMISSION_PREFIX + "hex", color = TextColor.color(String(digits).toInt(16)))
        }
        colors[symbol]?.let { return Code(2, PERMISSION_PREFIX + "color", color = it) }
        decorations[symbol]?.let { (name, decoration) ->
            return Code(2, PERMISSION_PREFIX + name, decoration = decoration)
        }
        if (symbol == 'r') return Code(2, PERMISSION_PREFIX + "reset", reset = true)
        return null
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
