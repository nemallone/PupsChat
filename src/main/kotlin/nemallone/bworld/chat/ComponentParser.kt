package nemallone.bworld.chat

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage

internal object ComponentParser {

    val miniMessage: MiniMessage = MiniMessage.miniMessage()

    private val LEGACY_CODE = Regex("[§&](?:#[0-9a-fA-F]{6}|[xX](?:[§&][0-9a-fA-F]){6}|[0-9a-fA-Fk-oK-OrR])")

    private val LEGACY_TAGS = mapOf(
        '0' to "black", '1' to "dark_blue", '2' to "dark_green", '3' to "dark_aqua",
        '4' to "dark_red", '5' to "dark_purple", '6' to "gold", '7' to "gray",
        '8' to "dark_gray", '9' to "blue", 'a' to "green", 'b' to "aqua",
        'c' to "red", 'd' to "light_purple", 'e' to "yellow", 'f' to "white",
        'k' to "obfuscated", 'l' to "bold", 'm' to "strikethrough",
        'n' to "underlined", 'o' to "italic", 'r' to "reset"
    )

    fun parseMixed(raw: String): Component = miniMessage.deserialize(toMiniMessageFragment(raw))

    fun toMiniMessageFragment(raw: String): String = LEGACY_CODE.replace(raw) { match ->
        val token = match.value
        if (token.startsWith("&#") || token.startsWith("§#")) {
            "<reset><#${token.substring(2)}>"
        } else if (token.length > 2) {
            "<reset><#${token.substring(3).filter(Char::isLetterOrDigit)}>"
        } else {
            val code = token[1].lowercaseChar()
            val tag = LEGACY_TAGS[code] ?: return@replace token
            if (code in "0123456789abcdef") "<reset><$tag>" else "<$tag>"
        }
    }
}
