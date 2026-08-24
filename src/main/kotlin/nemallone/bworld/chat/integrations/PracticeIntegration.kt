package nemallone.bworld.chat.integrations

import nemallone.bworld.chat.PupsChat
import nemallone.bworld.chat.messaging.AutoMessageManager
import nemallone.bworld.chat.messaging.DuelProvider
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.lang.reflect.Method

private const val PLUGIN_NAME = "StrikePractice"
private const val STRIKE_PRACTICE_CLASS = "ga.strikepractice.StrikePractice"
private const val FIGHT_END_EVENT = "ga.strikepractice.events.FightEndEvent"
private const val DUEL_END_EVENT = "ga.strikepractice.events.DuelEndEvent"
private const val PARTY_FFA_END_EVENT = "ga.strikepractice.events.PartyFFAEndEvent"
private const val ROUND_END_EVENT = "ga.strikepractice.events.RoundEndEvent"
private const val FIGHT_DEATH_EVENT = "ga.strikepractice.events.FightDeathEvent"
private const val DUEL_FIGHT = "ga.strikepractice.fights.duel.Duel"
private const val FFA_FIGHT = "ga.strikepractice.fights.other.FFAFight"

internal fun handlesWinnerFightEnd(eventClassName: String): Boolean =
    eventClassName == DUEL_END_EVENT || eventClassName == PARTY_FFA_END_EVENT

internal fun handlesRoundEnd(endingNow: Boolean, fightClassName: String?): Boolean =
    !endingNow || (fightClassName != null && fightClassName != DUEL_FIGHT)

internal class PracticeIntegration(
    private val plugin: PupsChat,
    private val autoMessageManager: AutoMessageManager
) : Listener, DuelProvider {

    private class ApiAccess(val instance: Any, val getDuelOpponent: Method)

    private var enabled = true

    private var duelChatEnabled = true

    private var autoMessagesEnabled = true

    private var apiAccess: ApiAccess? = null

    private var registeredAutoMessageEventCount = 0

    private var opponentFailureLogged = false
    private val invocationFailuresLogged = HashSet<String>()

    fun register() {
        loadConfig()
        if (!plugin.server.pluginManager.isPluginEnabled(PLUGIN_NAME)) return

        apiAccess = resolveApi()
        registeredAutoMessageEventCount = listOf(
            registerFightEndEvent(),
            registerRoundEndEvent(),
            registerFfaDeathEvent()
        ).count { it }

        if (enabled && autoMessagesEnabled && registeredAutoMessageEventCount == 0) {
            plugin.logger.warning(
                "StrikePractice: события для автосообщений не найдены"
            )
        }
        plugin.logger.info(
            "StrikePractice: дуэльный API ${if (apiAccess != null) "доступен" else "недоступен"}, " +
                "событий автосообщений $registeredAutoMessageEventCount/3"
        )
    }

    fun loadConfig() {
        val config = plugin.config
        enabled = config.getBoolean("integrations.strike-practice.enabled", true)
        duelChatEnabled = config.getBoolean("integrations.strike-practice.duel-chat", true)
        autoMessagesEnabled = config.getBoolean("integrations.strike-practice.auto-messages", true)
    }

    override val available: Boolean
        get() = enabled && duelChatEnabled && apiAccess != null

    val autoMessagesAvailable: Boolean
        get() = enabled && autoMessagesEnabled && autoMessageManager.enabled &&
            registeredAutoMessageEventCount > 0

    override fun getOpponent(player: Player): Player? {
        if (!enabled || !duelChatEnabled) return null
        val access = apiAccess ?: return null
        return try {
            access.getDuelOpponent.invoke(access.instance, player) as? Player
        } catch (exception: ReflectiveOperationException) {
            disableDuelApi(exception)
        } catch (exception: LinkageError) {
            disableDuelApi(exception)
        }
    }

    private fun resolveApi(): ApiAccess? {
        return try {
            val pluginClass = Class.forName(STRIKE_PRACTICE_CLASS)
            val api = pluginClass.getMethod("getAPI").invoke(null) ?: return null
            val getDuelOpponent = api.javaClass.getMethod("getDuelOpponent", Player::class.java)
            ApiAccess(api, getDuelOpponent)
        } catch (exception: ReflectiveOperationException) {
            plugin.logger.warning("StrikePractice API недоступен: ${exception.javaClass.simpleName}")
            null
        } catch (exception: LinkageError) {
            plugin.logger.warning("StrikePractice API несовместим: ${exception.javaClass.simpleName}")
            null
        }
    }

    private fun registerFightEndEvent(): Boolean {
        return registerEvent(FIGHT_END_EVENT) { event ->
            if (!handlesWinnerFightEnd(event.javaClass.name)) return@registerEvent
            sendAutoMessage(invokeNoArgs(event, "getWinner") as? Player)
        }
    }

    private fun registerRoundEndEvent(): Boolean {
        return registerEvent(ROUND_END_EVENT) { event ->
            val endingNow = invokeNoArgs(event, "isEndingNow") as? Boolean ?: return@registerEvent
            val fightClassName = invokeNoArgs(event, "getFight")?.javaClass?.name
            if (!handlesRoundEnd(endingNow, fightClassName)) return@registerEvent

            val winners = invokeNoArgs(event, "getWinners") as? Iterable<*> ?: return@registerEvent
            for (winner in winners) sendAutoMessage(winner as? Player)
        }
    }

    private fun registerFfaDeathEvent(): Boolean {
        return registerEvent(FIGHT_DEATH_EVENT) { event ->
            val fight = invokeNoArgs(event, "getFight") ?: return@registerEvent
            if (fight.javaClass.name != FFA_FIGHT) return@registerEvent
            sendAutoMessage(invokeNoArgs(event, "getKiller") as? Player)
        }
    }

    private fun registerEvent(className: String, handler: (Event) -> Unit): Boolean {
        val eventClass = try {
            Class.forName(className).asSubclass(Event::class.java)
        } catch (_: ReflectiveOperationException) {
            return false
        } catch (_: ClassCastException) {
            return false
        } catch (_: LinkageError) {
            return false
        }

        plugin.server.pluginManager.registerEvent(
            eventClass,
            this,
            EventPriority.MONITOR,
            { _, event ->
                if (autoMessagesAvailable) handler(event)
            },
            plugin,
            true
        )
        return true
    }

    private fun invokeNoArgs(target: Any, methodName: String): Any? {
        return try {
            target.javaClass.getMethod(methodName).invoke(target)
        } catch (exception: ReflectiveOperationException) {
            logInvocationFailure(target, methodName, exception)
            null
        } catch (exception: LinkageError) {
            logInvocationFailure(target, methodName, exception)
            null
        }
    }

    private fun disableDuelApi(exception: Throwable): Player? {
        apiAccess = null
        if (!opponentFailureLogged) {
            opponentFailureLogged = true
            plugin.logger.warning(
                "StrikePractice getDuelOpponent перестал работать: ${exception.javaClass.simpleName}"
            )
        }
        return null
    }

    private fun logInvocationFailure(target: Any, methodName: String, exception: Throwable) {
        val key = "${target.javaClass.name}#$methodName"
        if (!invocationFailuresLogged.add(key)) return
        plugin.logger.warning(
            "StrikePractice $key недоступен: ${exception.javaClass.simpleName}"
        )
    }

    private fun sendAutoMessage(player: Player?) {
        player ?: return
        val message = autoMessageManager.getRandomMessage(
            autoMessageManager.getMode(player.uniqueId)
        ) ?: return
        autoMessageManager.markAutoMessage(player.uniqueId, message)
        plugin.server.scheduler.runTask(plugin) { _ ->
            if (player.isOnline) player.chat(message)
        }
    }
}
