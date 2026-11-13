package org.sonora.music.accountimport

import org.sonora.music.i18n.tr

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.sonora.music.domain.Track

/** These objects live only in the import flow. No profile, account identifier or credentials. */
enum class ImportProvider(val label: String) { SPOTIFY("Spotify"), YOUTUBE("YouTube / YouTube Music") }
data class RemotePlaylist(val id: String, val name: String, val count: Int?, val link: String, val likes: Boolean = false)
data class RemoteSong(val id: String, val title: String, val artists: List<String>, val durationSeconds: Long,
    val link: String, val playable: Track? = null, val unavailable: String? = null)
data class AccountPage<T>(val items: List<T>, val next: String? = null)
interface AccountPlaylistSource {
    suspend fun playlists(access: TemporaryAccess, cursor: String?): AccountPage<RemotePlaylist>
    suspend fun songs(access: TemporaryAccess, playlist: RemotePlaylist, cursor: String?): AccountPage<RemoteSong>
}
class ImportAccessException(message: String) : Exception(message)

/** Deliberately not a data class: neither toString nor UI state can expose the credential. */
class TemporaryAccess(token: String, lifetimeSeconds: Long, private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onDiscard: (String) -> Unit = {}) : AutoCloseable {
    private var secret: CharArray? = token.toCharArray()
    private val deadline = clock() + lifetimeSeconds.coerceIn(1, 3600) * 1000
    @Synchronized fun header(): String {
        if (clock() >= deadline) close()
        return secret?.let { "Bearer ${String(it)}" } ?: throw ImportAccessException(tr("Authorization expired. Connect again to continue."))
    }
    @Synchronized override fun close() {
        val old = secret ?: return
        secret = null
        try { onDiscard(String(old)) } finally { old.fill('\u0000') }
    }
    @Synchronized fun isClosed() = secret == null
    override fun toString() = "TemporaryAccess(redacted)"
}

/** Reject looping pages and oversize imports instead of quietly saving a truncated playlist. */
suspend fun <T> collectAccountPages(limit: Int, page: suspend (String?) -> AccountPage<T>): List<T> {
    val result = mutableListOf<T>(); val seen = mutableSetOf<String>(); var cursor: String? = null
    do {
        currentCoroutineContext().ensureActive()
        val next = page(cursor)
        result += next.items
        if (result.size > limit) throw ImportAccessException(tr("This selection exceeds %1\$s entries. Import smaller lists separately.", limit))
        cursor = next.next
        if (cursor != null && (!seen.add(cursor) || seen.size > 500))
            throw ImportAccessException(tr("The provider returned repeated or excessive pages. Nothing was imported."))
    } while (cursor != null)
    return result
}
