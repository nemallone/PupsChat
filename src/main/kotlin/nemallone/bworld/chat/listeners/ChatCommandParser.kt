package nemallone.bworld.chat.listeners

import java.util.Locale

internal data class ChatCommand(
    val messageStart: Int,
    val context: String,
    val checkToxicity: Boolean
)

internal class ChatCommandParser(
    private val targetedPrivateCommands: Set<String>,
    private val replyPrivateCommands: Set<String>,
    private val directChatCommands: Set<String>,
    private val mailCommands: Set<String>,
    private val targetedMailSubcommands: Set<String>,
    private val broadcastMailSubcommands: Set<String>
) {
    fun parse(rawMessage: String): ChatCommand? {
        val commandEnd = findWhitespace(rawMessage, 0)
        val commandToken = rawMessage.substring(0, commandEnd)
        val commandName = normalizeCommandName(commandToken)
        val argsStart = skipWhitespace(rawMessage, commandEnd)

        return when (commandName) {
            in replyPrivateCommands -> if (argsStart < rawMessage.length) {
                ChatCommand(
                    messageStart = argsStart,
                    context = commandToken,
                    checkToxicity = true
                )
            } else {
                null
            }
            in targetedPrivateCommands -> {
                val messageStart = findMessageStartAfterArgs(rawMessage, argsStart, 1)
                    ?: return null
                ChatCommand(
                    messageStart = messageStart,
                    context = contextBeforeMessage(rawMessage, commandToken, messageStart),
                    checkToxicity = true
                )
            }
            in directChatCommands -> if (argsStart < rawMessage.length) {
                ChatCommand(
                    messageStart = argsStart,
                    context = commandToken,
                    checkToxicity = false
                )
            } else {
                null
            }
            in mailCommands -> parseMail(rawMessage, commandToken, argsStart)
            else -> null
        }
    }

    private fun parseMail(
        rawMessage: String,
        commandToken: String,
        argsStart: Int
    ): ChatCommand? {
        val subcommandStart = skipWhitespace(rawMessage, argsStart)
        if (subcommandStart >= rawMessage.length) return null

        val subcommandEnd = findWhitespace(rawMessage, subcommandStart)
        val subcommand = rawMessage.substring(subcommandStart, subcommandEnd)
            .lowercase(Locale.ROOT)
        val argumentsToSkip = when (subcommand) {
            in targetedMailSubcommands -> 2
            in broadcastMailSubcommands -> 1
            else -> return null
        }
        val messageStart = findMessageStartAfterArgs(
            rawMessage,
            subcommandStart,
            argumentsToSkip
        ) ?: return null
        return ChatCommand(
            messageStart = messageStart,
            context = contextBeforeMessage(rawMessage, "$commandToken $subcommand", messageStart),
            checkToxicity = false
        )
    }

    private fun findMessageStartAfterArgs(
        rawMessage: String,
        argsStart: Int,
        argumentsToSkip: Int
    ): Int? {
        var index = skipWhitespace(rawMessage, argsStart)
        repeat(argumentsToSkip) {
            if (index >= rawMessage.length) return null
            index = skipWhitespace(rawMessage, findWhitespace(rawMessage, index))
        }
        return index.takeIf { it < rawMessage.length }
    }

    private fun contextBeforeMessage(
        rawMessage: String,
        fallback: String,
        messageStart: Int
    ): String = rawMessage.substring(0, messageStart).trimEnd().ifEmpty { fallback }

    private fun normalizeCommandName(commandToken: String): String {
        val commandName = commandToken.removePrefix("/").lowercase(Locale.ROOT)
        val namespaceIndex = commandName.indexOf(':')
        return if (namespaceIndex in 0 until commandName.lastIndex) {
            commandName.substring(namespaceIndex + 1)
        } else {
            commandName
        }
    }

    private fun findWhitespace(value: String, start: Int): Int {
        var index = start
        while (index < value.length && !value[index].isWhitespace()) index++
        return index
    }

    private fun skipWhitespace(value: String, start: Int): Int {
        var index = start
        while (index < value.length && value[index].isWhitespace()) index++
        return index
    }
}
