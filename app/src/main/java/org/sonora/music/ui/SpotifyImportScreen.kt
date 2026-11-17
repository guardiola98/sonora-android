package org.sonora.music.ui

import org.sonora.music.i18n.tr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.media3.common.util.UnstableApi
import org.sonora.music.importing.*
import org.sonora.music.domain.formatDuration

@Composable private fun SpotifyAttribution() {
    coil3.compose.AsyncImage(model = org.sonora.music.R.drawable.spotify_full_logo_white,
        contentDescription = tr("Metadata from Spotify"), modifier = Modifier.width(90.dp).height(28.dp))
}

@Composable private fun SafeImportLink(url: String, label: String) {
    if (url.isBlank()) return
    val uri = LocalUriHandler.current
    var failed by remember { mutableStateOf(false) }
    TextButton(onClick = { failed = runCatching { uri.openUri(url) }.isFailure }) { Text(label) }
    if (failed) Text(tr("No browser available. %1\$s", url), style = MaterialTheme.typography.bodySmall)
}

@Composable fun SpotifyImportReview(presenter: SpotifyImportPresenter, state: SpotifyImportState) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SpotifyAttribution()
            Text(tr("Spotify import"), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = presenter::cancel) { Text(tr("Cancel import")) }
            if (state.publicLink) {
                Text(tr("Public Spotify playlist. No sign-in or developer setup needed."), color = MaterialTheme.colorScheme.primary)
                Text(tr("Spotify's public page may expose only part of a playlist. Review the number of entries below before continuing; use a Spotify ZIP / JSON export for lists that are incomplete here."), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(tr("Imported from a file. No account is connected. Selection and review stay in memory until you leave."), color = MaterialTheme.colorScheme.primary)
                Text(tr("Exports usually omit playlist IDs. Reimporting the same name merges into its previous file-import destination; renaming it creates a new one. Link imports remain separate unless the export contains a playlist ID."), style = MaterialTheme.typography.bodySmall)
            }
        }
        state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (state.busy) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.progress)
        }
        if (!state.busy && state.review.isEmpty() && state.active) {
            item { Text(tr("Select playlists"), style = MaterialTheme.typography.titleLarge)
                if (state.lists.isEmpty()) Text(tr("No playlists found. Try another link or file.")) }
            items(state.lists, key = { it.id }) { list ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(list.id in state.selected, { presenter.select(list.id) }, modifier = Modifier.semantics { contentDescription = tr("Select %1\$s", list.name) })
                        Column(Modifier.weight(1f)) {
                            SpotifyAttribution()
                            Text(list.name, style = MaterialTheme.typography.titleMedium)
                            Text(list.count?.let { tr("%1\$s entries", it) } ?: tr("Separate likes collection"), style = MaterialTheme.typography.bodySmall)
                            SafeImportLink(list.link, tr("View on Spotify"))
                        }
                    }
                }
            }
            item {
                run {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state.searchOnline, presenter::searchOnline, modifier = Modifier.semantics { contentDescription = tr("Search selected songs online") })
                        Text(tr("Search YouTube for selected song titles and artists"), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(if (state.publicLink) tr("Review the suggested versions before saving. You can turn search off and look up individual entries later. Spotify audio is not downloaded.")
                        else tr("Exported songs have no reliable duration, so versions need your confirmation. Turn search off to review offline and look up individual entries later."), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = presenter::readSelected, enabled = state.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(tr("Review selected lists")) }
            }
        }
        if (state.review.isNotEmpty()) {
            item {
                Text(tr("Review playable versions"), style = MaterialTheme.typography.titleLarge)
                Text(tr("%1\$s entries · %2\$s pending review. Only chosen YouTube references will be saved, in source order. Reimport merges new entries into existing playlists.", state.review.sumOf { it.songs.size }, state.pending))
            }
            state.review.forEach { list ->
                item(key = "list:${list.source.id}") { Text(list.source.name, style = MaterialTheme.typography.titleLarge); SafeImportLink(list.source.link, tr("Original playlist")) }
                items(list.songs.size, key = { "${list.source.id}:$it" }) { index ->
                    val row = list.songs[index]
                    var expanded by remember { mutableStateOf(false) }
                    var link by remember { mutableStateOf("") }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SpotifyAttribution()
                            Text("${index + 1}. ${row.source.title}", style = MaterialTheme.typography.titleMedium)
                            Text("${row.source.artists.joinToString()} · ${if (row.source.durationSeconds > 0) formatDuration(row.source.durationSeconds) else tr("Duration unavailable")}", style = MaterialTheme.typography.bodySmall)
                            Text(row.reason, color = if (row.chosen == null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                            row.chosen?.let { Text(tr("Chosen: %1\$s · %2\$s · %3\$s", it.title, it.artist, formatDuration(it.durationSeconds)), style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) tr("Hide versions") else tr("Review / change version")) }
                            if (expanded) {
                                SafeImportLink(row.source.link, tr("Original entry"))
                                row.candidates.forEach { candidate ->
                                    Text("${candidate.title} · ${candidate.artist} · ${formatDuration(candidate.durationSeconds)}")
                                    SafeImportLink(candidate.url, tr("Listen in browser"))
                                    OutlinedButton(onClick = { presenter.choose(list.source.id, index, candidate) }, enabled = !state.busy) { Text(tr("Use this version")) }
                                }
                                TextButton(onClick = { presenter.retry(list.source.id, index) }, enabled = !state.busy) { Text(tr("Search again")) }
                                OutlinedTextField(link, { link = it }, label = { Text(tr("YouTube video link for manual review")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                TextButton(onClick = { presenter.retry(list.source.id, index, link) }, enabled = !state.busy && link.isNotBlank()) { Text(tr("Look up link")) }
                                if (row.chosen != null) TextButton(onClick = { presenter.choose(list.source.id, index, null) }, enabled = !state.busy) { Text(tr("Leave pending")) }
                            }
                        }
                    }
                }
            }
            item {
                if (state.pending > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state.skipPending, presenter::skipPending, enabled = !state.busy, modifier = Modifier.semantics { contentDescription = tr("Leave pending entries out") })
                    Text(tr("Leave %1\$s pending entries out of this import. They will not be saved; reopen the source to review them later.", state.pending), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = presenter::confirm, enabled = !state.busy && (state.pending == 0 || state.skipPending) && state.review.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text(tr("Continue to local import preview")) }
            }
        }
    }
}
