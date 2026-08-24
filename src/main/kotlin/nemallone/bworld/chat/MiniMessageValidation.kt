package nemallone.bworld.chat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import java.util.Locale

private val miniMessageTagStart = Regex("<(/?)([!?#]?[A-Za-z0-9_-]+)(?=[:/>])")
private val standardTagResolver = TagResolver.standard()

internal fun MiniMessage.deserializeChecked(
    input: String,
    vararg resolvers: TagResolver
): Component {
    for (match in miniMessageTagStart.findAll(input)) {
        if (isEscaped(input, match.range.first)) continue
        var name = match.groupValues[2].lowercase(Locale.ROOT)
        if (name.startsWith("!")) {
            name = name.substring(1)
        }
        val isHexColor = name.startsWith("#") && (name.length == 7 || name.length == 4) &&
            name.substring(1).all { it in "0123456789abcdef" }
        if (!isHexColor && !standardTagResolver.has(name) && resolvers.none { it.has(name) }) {
            throw IllegalArgumentException("Неизвестный MiniMessage-тег <$name>")
        }
    }
    return deserialize(input, *resolvers)
}

private fun isEscaped(input: String, tagIndex: Int): Boolean {
    var backslashes = 0
    var index = tagIndex - 1
    while (index >= 0 && input[index] == '\\') {
        backslashes++
        index--
    }
    return backslashes % 2 != 0
}
