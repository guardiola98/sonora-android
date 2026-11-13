package org.sonora.music.ui

import org.sonora.music.i18n.tr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import org.sonora.music.domain.PodcastLinks
import org.sonora.music.domain.Track
import org.sonora.music.offline.OfflineEntry
import org.sonora.music.offline.OfflineState

@UnstableApi
data class DownloadActions(val state: OfflineState = OfflineState(), val add: (List<Track>) -> Unit = {},
    val remove: (String) -> Unit = {}, val pause: (String) -> Unit = {}, val resume: (String) -> Unit = {})
@UnstableApi
val LocalDownloads = staticCompositionLocalOf { DownloadActions() }

@UnstableApi
@Composable fun DownloadMenuItem(track: Track, close: () -> Unit) {
    val actions = LocalDownloads.current
    val item = actions.state.items.firstOrNull { it.track.url == track.url }
    val label = when {
        item?.ready == true -> tr("Remove download")
        item?.busy == true -> tr("Cancel download")
        item?.state == Download.STATE_STOPPED -> tr("Resume download")
        item != null -> tr("Retry download")
        else -> tr("Download for offline listening")
    }
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(if (item?.ready == true) Icons.Rounded.DownloadDone else Icons.Rounded.Download, null) },
        onClick = { close(); when {
            item?.ready == true || item?.busy == true -> actions.remove(track.url)
            item?.state == Download.STATE_STOPPED -> actions.resume(track.url)
            else -> actions.add(listOf(track))
        } })
}
@UnstableApi
@Composable fun DownloadButton(track: Track, iconOnly: Boolean = false) {
    val actions = LocalDownloads.current
    val item = actions.state.items.firstOrNull { it.track.url == track.url }
    var menu by remember { mutableStateOf(false) }
    Box {
        val click = { if (item == null) actions.add(listOf(track)) else menu = true }
        val icon = if (item?.ready == true) Icons.Rounded.DownloadDone else Icons.Rounded.Download
        if (iconOnly) IconButton(onClick = click) { Icon(icon, item?.status ?: tr("Download for offline listening"), tint = MaterialTheme.colorScheme.primary) }
        else TextButton(onClick = click) {
            Icon(icon, null)
            Spacer(Modifier.width(6.dp)); Text(item?.status ?: tr("Download"))
        }
        DropdownMenu(menu, { menu = false }) { DownloadMenuItem(track) { menu = false } }
    }
}
@UnstableApi
@Composable fun DownloadStatus(track: Track) {
    val item = LocalDownloads.current.state.items.firstOrNull { it.track.url == track.url } ?: return
    Text(item.status, style = MaterialTheme.typography.labelSmall, color = if (item.state == Download.STATE_FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
}
private fun audioSize(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) String.format(org.sonora.music.i18n.AppLanguage.locale, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    else String.format(org.sonora.music.i18n.AppLanguage.locale, "%.1f MB", bytes / (1024.0 * 1024))

@UnstableApi
@Composable fun DownloadsScreen(vm: MusicViewModel, connected: Boolean) {
    val state by vm.downloads.collectAsStateWithLifecycle()
    val actions = LocalDownloads.current
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var removing by remember { mutableStateOf<OfflineEntry?>(null) }
    val items = state.items.filter { filter == 0 || PodcastLinks.isEpisode(it.track.url) == (filter == 2) }
    val playable = items.filter { it.ready }.map { it.track }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(tr("Downloads"), style = MaterialTheme.typography.headlineMedium)
            Text(tr("%1\$s available offline · %2\$s", state.items.count { it.ready }, audioSize(state.items.sumOf { it.bytes })), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Wi-Fi only"))
                    Text(tr("Uses unmetered networks. Turn off to allow mobile data."), style = MaterialTheme.typography.bodySmall)
                }
                Switch(state.wifiOnly, vm::downloadWifiOnly)
            }
            if (state.waitingForNetwork && state.items.any { it.busy }) Text(tr("Downloads are waiting for an allowed network."), color = MaterialTheme.colorScheme.primary)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(tr("Completed audio plays without internet. Downloads stay on this device and are not included in library backups."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(tr("All"), tr("Music"), tr("Podcasts")).forEachIndexed { index, label -> FilterChip(filter == index, { filter = index }, label = { Text(label) }) }
            }
            if (playable.isNotEmpty()) FilledTonalButton(onClick = { vm.playback.play(playable, 0) }, enabled = connected) {
                Icon(Icons.Rounded.PlayArrow, null); Text(tr("Play downloaded"))
            }
        }
        if (items.isEmpty()) item { EmptyState(Icons.Rounded.Download, tr("Ready when you are"), tr("Choose Download in a song's menu or on a podcast episode. You can also download a complete playlist.")) }
        items(items, key = { it.track.url }) { entry ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Artwork(entry.track, Modifier.size(48.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(entry.track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(entry.track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        if (entry.ready) IconButton(onClick = { vm.playback.play(listOf(entry.track), 0) }, enabled = connected) { Icon(Icons.Rounded.PlayArrow, tr("Play downloaded %1\$s", entry.track.title)) }
                    }
                    Text("${entry.status} · ${audioSize(entry.bytes)}", style = MaterialTheme.typography.bodySmall,
                        color = if (entry.state == Download.STATE_FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.state == Download.STATE_DOWNLOADING) {
                        if (entry.percent >= 0) LinearProgressIndicator(progress = { (entry.percent / 100).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (entry.state == Download.STATE_FAILED) Text(tr("Check your connection and free storage, then retry. The source may also be unavailable."), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        when {
                            entry.state == Download.STATE_STOPPED -> TextButton(onClick = { actions.resume(entry.track.url) }) { Text(tr("Resume")) }
                            entry.state == Download.STATE_FAILED || entry.state == Download.STATE_COMPLETED && !entry.ready -> TextButton(onClick = { actions.add(listOf(entry.track)) }) { Text(tr("Retry")) }
                            entry.busy && entry.state != Download.STATE_REMOVING -> TextButton(onClick = { actions.pause(entry.track.url) }) { Text(tr("Pause")) }
                        }
                        TextButton(onClick = { if (entry.ready) removing = entry else actions.remove(entry.track.url) }, enabled = entry.state != Download.STATE_REMOVING) {
                            Text(if (entry.ready) tr("Remove download") else tr("Cancel and remove"))
                        }
                    }
                }
            }
        }
    }
    removing?.let { entry -> AlertDialog(onDismissRequest = { removing = null }, title = { Text(tr("Remove downloaded audio?")) },
        text = { Text(tr("%1\$s will need internet to play again. Your playlists, favourites and episode progress are kept.", entry.track.title)) },
        confirmButton = { TextButton(onClick = { actions.remove(entry.track.url); removing = null }) { Text(tr("Remove")) } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text(tr("Keep")) } }) }
}
