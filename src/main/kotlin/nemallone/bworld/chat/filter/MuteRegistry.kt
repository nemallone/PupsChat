package nemallone.bworld.chat.filter

import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class StoredMute(
    val playerName: String,
    val expiresAt: Long
)

internal class MuteRegistry(
    private val clock: () -> Long = System::currentTimeMillis
) {
    private class ActiveMute(
        val playerId: UUID,
        val playerName: String,
        val expiresAt: Long
    )

    private val byPlayer = ConcurrentHashMap<UUID, ActiveMute>()
    private val byName = ConcurrentHashMap<String, ActiveMute>()

    fun put(playerId: UUID, playerName: String, expiresAt: Long) {
        val mute = ActiveMute(playerId, playerName, expiresAt)
        val previous = byPlayer.put(playerId, mute)
        if (previous != null) {
            byName.remove(nameKey(previous.playerName), previous)
        }
        byName[nameKey(playerName)] = mute
    }

    fun expiration(playerId: UUID): Long? {
        val mute = byPlayer[playerId] ?: return null
        if (mute.expiresAt > clock()) return mute.expiresAt

        remove(playerId, mute)
        return null
    }

    fun findActivePlayerId(playerName: String): UUID? {
        val key = nameKey(playerName)
        val mute = byName[key] ?: return null
        if (byPlayer[mute.playerId] !== mute) {
            byName.remove(key, mute)
            return null
        }
        if (mute.expiresAt > clock()) return mute.playerId

        remove(mute.playerId, mute)
        return null
    }

    fun unmute(playerId: UUID): Boolean {
        val mute = byPlayer.remove(playerId) ?: return false
        byName.remove(nameKey(mute.playerName), mute)
        return mute.expiresAt > clock()
    }

    fun snapshot(): Map<UUID, StoredMute> {
        val now = clock()
        val result = HashMap<UUID, StoredMute>()
        for ((uuid, mute) in byPlayer) {
            if (mute.expiresAt > now) {
                result[uuid] = StoredMute(mute.playerName, mute.expiresAt)
            }
        }
        return result
    }

    fun restore(mutes: Map<UUID, StoredMute>) {
        val now = clock()
        for ((uuid, mute) in mutes) {
            if (mute.expiresAt > now) {
                put(uuid, mute.playerName, mute.expiresAt)
            }
        }
    }

    fun removeExpired() {
        val now = clock()
        for ((playerId, mute) in byPlayer.entries) {
            if (mute.expiresAt <= now) remove(playerId, mute)
        }
    }

    private fun remove(playerId: UUID, mute: ActiveMute) {
        if (byPlayer.remove(playerId, mute)) {
            byName.remove(nameKey(mute.playerName), mute)
        }
    }

    private fun nameKey(playerName: String): String = playerName.lowercase(Locale.ROOT)
}
