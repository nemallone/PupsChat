package nemallone.bworld.chat.integrations

import de.myzelyam.api.vanish.VanishAPI
import nemallone.bworld.chat.PupsChat
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

internal class VanishIntegration(private val plugin: PupsChat) {

    private var premiumVanishEnabled = true
    private var premiumVanishAvailable = false
    private var essentialsVanishEnabled = true
    private var essentialsPlugin: Plugin? = null

    init {
        loadConfig()
    }

    fun loadConfig() {
        val config = plugin.config
        val pluginManager = plugin.server.pluginManager
        premiumVanishEnabled = config.getBoolean("integrations.premium-vanish.enabled", true)
        premiumVanishAvailable = pluginManager.isPluginEnabled("PremiumVanish")
        essentialsVanishEnabled = config.getBoolean("integrations.essentials-vanish.enabled", true)
        essentialsPlugin = pluginManager.getPlugin("Essentials")?.takeIf(Plugin::isEnabled)
    }

    fun isVanished(player: Player): Boolean =
        isPremiumVanished(player) || isEssentialsVanished(player)

    private fun isPremiumVanished(player: Player): Boolean {
        if (!premiumVanishEnabled || !premiumVanishAvailable) return false
        return try {
            VanishAPI.isInvisible(player)
        } catch (exception: RuntimeException) {
            disablePremiumVanish(exception)
            false
        } catch (exception: LinkageError) {
            disablePremiumVanish(exception)
            false
        }
    }

    private fun disablePremiumVanish(cause: Throwable) {
        premiumVanishAvailable = false
        plugin.logger.warning(
            "PremiumVanish API отключён после ошибки ${cause.javaClass.simpleName}"
        )
    }

    private fun isEssentialsVanished(player: Player): Boolean {
        if (!essentialsVanishEnabled) return false
        val essentials = essentialsPlugin ?: return false
        return player.getMetadata("vanished").any { metadata ->
            metadata.owningPlugin === essentials && metadata.asBoolean()
        }
    }
}
