package org.sonora.music.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.media3.common.util.UnstableApi
import org.sonora.music.BuildConfig
import org.sonora.music.accountimport.*
import org.sonora.music.domain.formatDuration

private const val DISCONNECT_NOTICE = "Sonora keeps access only in memory while reading your selection, then discards it. This does not sign out of your browser, and may not revoke earlier consent. No automatic sync."
@Composable private fun SpotifyAttribution() {
    coil3.compose.AsyncImage(model = org.sonora.music.R.drawable.spotify_full_logo_white,
        contentDescription = "Metadata from Spotify", modifier = Modifier.width(90.dp).height(28.dp))
}

private const val SETUP_URL = "https://github.com/guardiola98/sonora-android/blob/main/docs/OAUTH-IMPORT.md"

@UnstableApi
@Composable fun AccountImportButtons(vm: MusicViewModel) {
    var provider by remember { mutableStateOf<ImportProvider?>(null) }
    var likes by remember { mutableStateOf(false) }
    val context = LocalContext.current
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connect once, import, disconnect", style = MaterialTheme.typography.titleLarge)
            Text("Choose private or public playlists with temporary read permission.")
            Button(onClick = { provider = ImportProvider.SPOTIFY; likes = false }, modifier = Modifier.fillMaxWidth()) { Text("Import from Spotify") }
            OutlinedButton(onClick = { provider = ImportProvider.YOUTUBE; likes = false }, modifier = Modifier.fillMaxWidth()) { Text("Import from YouTube / YouTube Music") }
            Text(DISCONNECT_NOTICE, style = MaterialTheme.typography.bodySmall)
        }
    }
    provider?.let { selected ->
        val configured = if (selected == ImportProvider.SPOTIFY) BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank() else BuildConfig.YOUTUBE_OAUTH_ENABLED
        AlertDialog(onDismissRequest = { provider = null }, title = { Text("Import from ${selected.label}") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (selected == ImportProvider.SPOTIFY) {
                    Text("Opens your system browser with PKCE. Spotify development apps require a Premium app owner and allow up to five registered testers. Only owned or collaborative playlists expose their contents; followed and automatic mixes may be unavailable.")
                    Text("To find playable equivalents, selected song titles and artists are searched on YouTube. Uncertain versions need your review. Spotify audio is never played or downloaded.")
                } else {
                    Text("Experimental: YouTube API rules require data refresh/deletion and restrict background/audio-only clients. A permanent disconnected import needs use-case approval; registration alone is not enough. This integration is disabled by default. Use Takeout or public links meanwhile.", color = MaterialTheme.colorScheme.tertiary)
                    Text("Opens Google in your system browser with PKCE. A Sonora-operated HTTPS broker exchanges the one-time code; it must not store or log credentials. Read-only access lists playlists owned by the selected YouTube channel, including Music playlists when the API exposes them. Saved third-party lists, radio mixes, uploads and the full Music library are not guaranteed. Takeout remains available.")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(likes, { likes = it })
                    Text(if (selected == ImportProvider.SPOTIFY) "Also read liked songs (adds library read permission)" else "Also read YouTube liked videos (may include non-music; not a complete YouTube Music likes export)", style = MaterialTheme.typography.bodySmall)
                }
                if (!configured) Text("Account import is not configured in this build. The app owner must register OAuth access first (Google also needs the optional HTTPS broker); no secret should be entered here. File and link imports are available.", color = MaterialTheme.colorScheme.error)
                Text(DISCONNECT_NOTICE, style = MaterialTheme.typography.bodySmall)
                SafeImportLink(SETUP_URL, "Setup and availability")
            }
        }, confirmButton = {
            if (configured) TextButton(onClick = {
                provider = null
                vm.accountImporter.connect(selected, likes) {
                    val openBrowser: (String) -> Unit = { url ->
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                        catch (_: Exception) { throw ImportAccessException("No system browser is available. Install one and try again.") }
                    }
                    if (selected == ImportProvider.SPOTIFY) vm.spotifyAuthorization.authorize(BuildConfig.SPOTIFY_CLIENT_ID, likes, openBrowser)
                    else vm.googleImportAuthorization.authorize(openBrowser)
                }
            }) { Text("Authorize read access") }
            else TextButton(onClick = { provider = null }) { Text("Use file or link import") }
        }, dismissButton = { TextButton(onClick = { provider = null }) { Text("Cancel") } })
    }
}

@Composable private fun SafeImportLink(url: String, label: String) {
    if (url.isBlank()) return
    val uri = LocalUriHandler.current
    var failed by remember { mutableStateOf(false) }
    TextButton(onClick = { failed = runCatching { uri.openUri(url) }.isFailure }) { Text(label) }
    if (failed) Text("No browser available. $url", style = MaterialTheme.typography.bodySmall)
}

@Composable fun AccountImportReview(presenter: AccountImportPresenter, state: AccountImportState) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (state.provider == ImportProvider.SPOTIFY) SpotifyAttribution()
            Text("${state.provider?.label ?: "Account"} import", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = presenter::cancel) { Text(if (state.fromFile) "Cancel export import" else "Cancel and discard connection") }
            if (state.fromFile) {
                Text("Imported from a file. No account is connected. Selection and review stay in memory until you leave.", color = MaterialTheme.colorScheme.primary)
                Text("Exports usually omit playlist IDs. Reimporting the same name merges into its previous file-import destination; renaming it creates a new one. API imports remain separate unless the export contains a playlist ID.", style = MaterialTheme.typography.bodySmall)
            } else {
                if (state.disconnected && !state.authorizing) Text("Connection discarded. Review stays in memory until you leave.", color = MaterialTheme.colorScheme.primary)
                Text(DISCONNECT_NOTICE, style = MaterialTheme.typography.bodySmall)
            }
        }
        state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (state.busy) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.progress)
            if (state.authorizing) Text("After authorizing in the browser, return to Sonora. If you closed consent, cancel here to discard the attempt.")
        }
        if (!state.busy && state.review.isEmpty() && state.provider != null) {
            item { Text("Select playlists", style = MaterialTheme.typography.titleLarge)
                if (state.lists.isEmpty()) Text("The API returned no accessible playlists. Try a different channel/account, or import a Takeout file or public link.") }
            items(state.lists, key = { it.id }) { list ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(list.id in state.selected, { presenter.select(list.id) }, modifier = Modifier.semantics { contentDescription = "Select ${list.name}" })
                        Column(Modifier.weight(1f)) {
                            if (state.provider == ImportProvider.SPOTIFY) SpotifyAttribution()
                            Text(list.name, style = MaterialTheme.typography.titleMedium)
                            Text(list.count?.let { "$it entries" } ?: "Separate likes collection", style = MaterialTheme.typography.bodySmall)
                            SafeImportLink(list.link, "View on ${state.provider.label}")
                        }
                    }
                }
            }
            item {
                if (state.fromFile) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state.searchOnline, presenter::searchOnline, modifier = Modifier.semantics { contentDescription = "Search selected songs online" })
                        Text("Search YouTube for selected song titles and artists", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("Exported songs have no reliable duration, so versions need your confirmation. Turn search off to review offline and look up individual entries later.", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = presenter::readSelected, enabled = state.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(if (state.fromFile) "Review selected lists" else "Read selection and disconnect") }
            }
        }
        if (state.review.isNotEmpty()) {
            item {
                Text("Review playable versions", style = MaterialTheme.typography.titleLarge)
                Text("${state.review.sumOf { it.songs.size }} entries · ${state.pending} pending review. Only chosen YouTube references will be saved, in source order. Reimport merges new entries into existing playlists.")
            }
            state.review.forEach { list ->
                item(key = "list:${list.source.id}") { Text(list.source.name, style = MaterialTheme.typography.titleLarge); SafeImportLink(list.source.link, "Original playlist") }
                items(list.songs.size, key = { "${list.source.id}:$it" }) { index ->
                    val row = list.songs[index]
                    var expanded by remember { mutableStateOf(false) }
                    var link by remember { mutableStateOf("") }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (state.provider == ImportProvider.SPOTIFY) SpotifyAttribution()
                            Text("${index + 1}. ${row.source.title}", style = MaterialTheme.typography.titleMedium)
                            Text("${row.source.artists.joinToString()} · ${if (row.source.durationSeconds > 0) formatDuration(row.source.durationSeconds) else "Duration unavailable"}", style = MaterialTheme.typography.bodySmall)
                            Text(row.reason, color = if (row.chosen == null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                            row.chosen?.let { Text("Chosen: ${it.title} · ${it.artist} · ${formatDuration(it.durationSeconds)}", style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide versions" else "Review / change version") }
                            if (expanded) {
                                SafeImportLink(row.source.link, "Original entry")
                                row.candidates.forEach { candidate ->
                                    Text("${candidate.title} · ${candidate.artist} · ${formatDuration(candidate.durationSeconds)}")
                                    SafeImportLink(candidate.url, "Listen in browser")
                                    OutlinedButton(onClick = { presenter.choose(list.source.id, index, candidate) }, enabled = !state.busy) { Text("Use this version") }
                                }
                                TextButton(onClick = { presenter.retry(list.source.id, index) }, enabled = !state.busy && state.provider == ImportProvider.SPOTIFY) { Text("Search again") }
                                OutlinedTextField(link, { link = it }, label = { Text("YouTube video link for manual review") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                TextButton(onClick = { presenter.retry(list.source.id, index, link) }, enabled = !state.busy && link.isNotBlank()) { Text("Look up link") }
                                if (row.chosen != null) TextButton(onClick = { presenter.choose(list.source.id, index, null) }, enabled = !state.busy) { Text("Leave pending") }
                            }
                        }
                    }
                }
            }
            item {
                if (state.pending > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.skipPending, presenter::skipPending, enabled = !state.busy, modifier = Modifier.semantics { contentDescription = "Leave pending entries out" })
                    Text("Leave ${state.pending} pending entries out of this import. They will not be saved; reopen the source to review them later.", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = presenter::confirm, enabled = !state.busy && (state.pending == 0 || state.skipPending) && state.review.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Continue to local import preview") }
            }
        }
    }
}
