package org.sonora.music.ui

import org.sonora.music.i18n.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.sonora.music.data.PlaylistSummary
import org.sonora.music.domain.Track

val LocalAddToPlaylist = staticCompositionLocalOf<(Track) -> Unit> { error(tr("Playlist action is not provided")) }

@Composable fun PlaylistNameDialog(initial: String = "", title: String, onClose: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onClose, title = { Text(title) }, text = {
        OutlinedTextField(name, { if (it.length <= 180) name = it }, label = { Text(tr("Playlist name")) }, singleLine = true)
    }, confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text(tr("Save playlist")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } })
}
@Composable fun AddToPlaylistDialog(track: Track, playlists: List<PlaylistSummary>, onClose: () -> Unit,
    onChoose: (Long) -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text(tr("Add to playlist")) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(track.title, maxLines = 2)
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(playlists, key = { it.playlist.id }) { entry ->
                    Column(Modifier.fillMaxWidth().clickable { onChoose(entry.playlist.id) }.padding(vertical = 12.dp)) {
                        Text(entry.playlist.name); Text(tr("%1\$s tracks", entry.trackCount), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedTextField(name, { if (it.length <= 180) name = it }, label = { Text(tr("New playlist name")) }, singleLine = true)
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text(tr("Create and add")) }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(tr("Close")) } })
}
