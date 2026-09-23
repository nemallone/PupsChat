package nemallone.bworld.chat.channels

import nemallone.bworld.chat.api.ChatChannel
import org.bukkit.configuration.ConfigurationSection

internal data class ChannelSettings(
    val localEnabled: Boolean = false,
    val localRadius: Double = 100.0,
    val globalPrefix: String = "!",
    val globalConsole: Boolean = true,
    val localConsole: Boolean = true,
    val staffEnabled: Boolean = false,
    val staffPrefix: String = "#",
    val staffSendPermission: String = "pupschat.staff.send",
    val staffReadPermission: String = "pupschat.staff.read",
    val staffConsole: Boolean = true,
    val stripGlobalPrefixWithoutLocal: Boolean = true
) {
    init {
        require(localRadius.isFinite() && localRadius > 0 && localRadius <= 30_000_000) {
            "channels.local.radius must be finite and between 0 (exclusive) and 30000000"
        }
        require(validPrefix(globalPrefix)) { "channels.global.prefix must contain 1–8 non-whitespace characters" }
        require(validPrefix(staffPrefix)) { "staff-chat.prefix must contain 1–8 non-whitespace characters" }
        require(!(localEnabled || stripGlobalPrefixWithoutLocal) || !staffEnabled ||
            (!globalPrefix.startsWith(staffPrefix) && !staffPrefix.startsWith(globalPrefix))) {
            "The global and staff prefixes must not overlap"
        }
        require(validPermission(staffSendPermission) && validPermission(staffReadPermission)) {
            "Staff permissions must not be empty or contain whitespace"
        }
    }

    fun consoleEnabled(channel: ChatChannel): Boolean = when (channel) {
        ChatChannel.GLOBAL -> globalConsole
        ChatChannel.LOCAL -> localConsole
        ChatChannel.STAFF -> staffConsole
    }

    companion object {
        fun read(config: ConfigurationSection): ChannelSettings {
            fun boolean(path: String, fallback: Boolean): Boolean {
                if (!config.contains(path)) return fallback
                return config.get(path) as? Boolean ?: throw IllegalArgumentException("$path must be a boolean")
            }
            fun number(path: String, fallback: Double): Double {
                if (!config.contains(path)) return fallback
                return (config.get(path) as? Number)?.toDouble() ?: throw IllegalArgumentException("$path must be a number")
            }
            fun text(path: String, fallback: String): String {
                if (!config.contains(path)) return fallback
                return config.get(path) as? String ?: throw IllegalArgumentException("$path must be a string")
            }
            return ChannelSettings(
                localEnabled = boolean("channels.local.enabled", false),
                localRadius = number("channels.local.radius", 100.0),
                globalPrefix = text("channels.global.prefix", "!"),
                globalConsole = boolean("channels.global.console", true),
                localConsole = boolean("channels.local.console", true),
                staffEnabled = boolean("staff-chat.enabled", false),
                staffPrefix = text("staff-chat.prefix", "#"),
                staffSendPermission = text("staff-chat.send-permission", "pupschat.staff.send"),
                staffReadPermission = text("staff-chat.read-permission", "pupschat.staff.read"),
                staffConsole = boolean("staff-chat.console", true),
                stripGlobalPrefixWithoutLocal = boolean("channels.global.strip-prefix-without-local", true)
            )
        }

        private fun validPrefix(prefix: String) = prefix.length in 1..8 && prefix.none { it.isWhitespace() || it.isISOControl() }
        private fun validPermission(permission: String) = permission.isNotBlank() && permission.length <= 128 &&
            permission.none { it.isWhitespace() || it.isISOControl() }
    }
}
