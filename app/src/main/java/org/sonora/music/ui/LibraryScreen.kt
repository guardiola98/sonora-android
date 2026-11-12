package org.sonora.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import org.sonora.music.data.PlaylistSummary
import org.sonora.music.domain.Track

@UnstableApi
@Composable fun LibraryScreen(vm: MusicViewModel, favorites: List<Track>, history: List<Track>, favoriteIds: Set<String>, connected: Boolean,
    onSearch: () -> Unit, playlists: List<PlaylistSummary>, selected: PlaylistSummary?, playlistTracks: List<Track>,
    onImport: () -> Unit, onBackup: () -> Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var clearHistory by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<PlaylistSummary?>(null) }
    var create by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val tracks = if (selected != null) playlistTracks else if (section == 1) history else favorites
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) IconButton(onClick = { vm.selectPlaylist(null); section = 2 }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to playlists") }
            Text(selected?.playlist?.name ?: "Your library", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (selected == null) {
                IconButton(onClick = onImport) { Icon(Icons.Rounded.FileDownload, "Import music") }
                IconButton(onClick = onBackup) { Icon(Icons.Rounded.SettingsBackupRestore, "Backup and restore") }
            } else {
                IconButton(onClick = { rename = true }) { Icon(Icons.Rounded.Edit, "Rename playlist") }
                IconButton(onClick = { delete = selected }) { Icon(Icons.Rounded.DeleteOutline, "Delete playlist") }
            }
        }
        if (selected == null) LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item { FilterChip(section == 0, { section = 0 }, label = { Text("Favorites · ${favorites.size}") }) }
            item { FilterChip(section == 1, { section = 1 }, label = { Text("History · ${history.size}") }) }
            item { FilterChip(section == 2, { section = 2 }, label = { Text("Playlists · ${playlists.size}") }) }
        }
        if (selected != null) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { vm.playback.play(tracks, 0) }, enabled = connected && tracks.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Text("Play all") }
                FilledTonalIconButton(onClick = { vm.playback.shuffle(tracks) }, enabled = connected && tracks.isNotEmpty(), modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle playlist")
                }
                Spacer(Modifier.weight(1f)); TextButton(onClick = onSearch) { Text("Find songs") }
            }
            Text("Hold and drag a handle to reorder. Song menus also offer move up/down.", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tracks.isEmpty()) EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "Make it yours", "Find a song and choose Add to playlist from its menu.")
            else ReorderablePlaylist(tracks, favoriteIds, connected,
                onPlay = { vm.playback.play(it.first, it.second) }, onFavorite = vm::favorite, onEnqueue = vm::enqueue,
                onRemove = { vm.removeFromPlaylist(selected.playlist.id, it.url) },
                onOrder = { order, done -> vm.reorderPlaylist(selected.playlist.id, order, done) })
        } else if (section == 2) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { create = true }) { Icon(Icons.Rounded.Add, null); Text("New playlist") }
                TextButton(onClick = onImport) { Text("Import playlists") }
            }
            LazyColumn(contentPadding = PaddingValues(vertical = 16.dp)) {
                if (playlists.isEmpty()) item { EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "Your own collections", "Create a playlist or import one from YouTube Music.") }
                items(playlists, key = { it.playlist.id }) { entry ->
                    Row(Modifier.fillMaxWidth().clickable { vm.selectPlaylist(entry.playlist.id) }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) { Text(entry.playlist.name, maxLines = 2); Text("${entry.trackCount} tracks", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = { delete = entry }) { Icon(Icons.Rounded.DeleteOutline, "Delete ${entry.playlist.name}") }
                    }
                }
            }
        } else {
            if (tracks.isNotEmpty()) Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { vm.playback.play(tracks, 0) }, enabled = connected) { Icon(Icons.Rounded.PlayArrow, null); Text("Play all") }
                Spacer(Modifier.weight(1f))
                if (section == 1) TextButton(onClick = { clearHistory = true }) { Text("Clear history") }
            }
            LazyColumn(contentPadding = PaddingValues(vertical = 16.dp)) {
                if (tracks.isEmpty()) item {
                    EmptyState(if (section == 1) Icons.Rounded.History else Icons.Rounded.FavoriteBorder,
                        if (section == 1) "Start listening" else "Keep the good ones",
                        if (section == 1) "Tracks appear here after playback begins." else "Favorite a song or import your likes from YouTube Music.")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { TextButton(onClick = onSearch) { Text("Discover music") }; TextButton(onClick = onImport) { Text("Import music") } }
                }
                itemsIndexed(tracks, key = { _, t -> t.url }) { i, track -> TrackRow(track, track.url in favoriteIds, connected, { vm.playback.play(tracks, i) }, { vm.favorite(track) }, { vm.enqueue(track) }) }
            }
        }
    }
    if (create) PlaylistNameDialog(title = "New playlist", onClose = { create = false }, onSave = { name -> vm.createPlaylist(name) { create = false; vm.selectPlaylist(it) } })
    if (rename && selected != null) PlaylistNameDialog(selected.playlist.name, "Rename playlist", { rename = false }, { name -> vm.renamePlaylist(selected.playlist.id, name); rename = false })
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text("Clear listening history?") }, text = { Text("Favorites and playlists will stay in your library.") },
        confirmButton = { TextButton(onClick = { vm.clearHistory(); clearHistory = false }) { Text("Clear") } }, dismissButton = { TextButton(onClick = { clearHistory = false }) { Text("Cancel") } })
    delete?.let { entry -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete ${entry.playlist.name}?") }, text = { Text("This removes the playlist from Sonora only. Favorites, history and your YouTube account are kept.") },
        confirmButton = { TextButton(onClick = { vm.deletePlaylist(entry.playlist.id); delete = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
}

@Composable private fun ReorderablePlaylist(tracks: List<Track>, favorites: Set<String>, connected: Boolean,
    onPlay: (Pair<List<Track>, Int>) -> Unit, onFavorite: (Track) -> Unit, onEnqueue: (Track) -> Unit, onRemove: (Track) -> Unit,
    onOrder: (List<String>, (Boolean) -> Unit) -> Unit) {
    var ordered by remember { mutableStateOf(tracks) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var center by remember { mutableFloatStateOf(0f) }
    val list = rememberLazyListState()
    LaunchedEffect(tracks) { if (dragged == null) ordered = tracks }
    fun move(from: Int, to: Int) { if (from in ordered.indices && to in ordered.indices && from != to) ordered = ordered.toMutableList().apply { add(to, removeAt(from)) } }
    fun moveAtPointer() {
        val from = ordered.indexOfFirst { it.url == dragged }
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull { center >= it.offset && center <= it.offset + it.size }?.index ?: return
        move(from, target)
    }
    fun commit() { onOrder(ordered.map { it.url }) { success -> if (!success) ordered = tracks } }
    LaunchedEffect(dragged) {
        while (dragged != null) {
            val info = list.layoutInfo
            val scroll = when { center < info.viewportStartOffset + 70 -> -12f; center > info.viewportEndOffset - 70 -> 12f; else -> 0f }
            if (scroll != 0f) { list.scrollBy(scroll); moveAtPointer() }
            delay(16)
        }
    }
    LazyColumn(state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
        itemsIndexed(ordered, key = { _, track -> track.url }) { index, track ->
            val dragging = dragged == track.url
            val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == track.url }
            TrackRow(track, track.url in favorites, connected, { onPlay(ordered to index) }, { onFavorite(track) }, { onEnqueue(track) },
                modifier = Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer { translationY = if (dragging && info != null) center - info.offset - info.size / 2 else 0f },
                leading = {
                    Icon(Icons.Rounded.DragHandle, "Drag ${track.title}", Modifier.size(40.dp).pointerInput(track.url) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { val visible = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == track.url }; if (visible != null) { dragged = track.url; center = visible.offset + visible.size / 2f } },
                            onDragCancel = { dragged = null; ordered = tracks },
                            onDragEnd = { dragged = null; commit() },
                            onDrag = { change, amount -> change.consume(); center += amount.y; moveAtPointer() })
                    })
                },
                onRemove = { onRemove(track) },
                onMoveUp = if (index > 0) ({ move(index, index - 1); commit() }) else null,
                onMoveDown = if (index < ordered.lastIndex) ({ move(index, index + 1); commit() }) else null)
        }
    }
}
