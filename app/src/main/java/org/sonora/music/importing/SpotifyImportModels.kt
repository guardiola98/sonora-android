package org.sonora.music.importing

import org.sonora.music.domain.Track

/** Playlist metadata only; no account authorization or credentials. */
data class RemotePlaylist(val id: String, val name: String, val count: Int?, val link: String, val likes: Boolean = false)
data class RemoteSong(val id: String, val title: String, val artists: List<String>, val durationSeconds: Long,
    val link: String, val playable: Track? = null, val unavailable: String? = null)
class ImportAccessException(message: String) : Exception(message)
