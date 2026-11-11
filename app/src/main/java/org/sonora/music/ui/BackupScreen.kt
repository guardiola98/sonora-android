package org.sonora.music.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import org.sonora.music.data.LibraryBackup

data class BackupState(val busy: Boolean = false, val preview: LibraryBackup? = null, val message: String? = null, val error: String? = null)

@UnstableApi
@Composable fun BackupScreen(vm: MusicViewModel, state: BackupState, onClose: () -> Unit) {
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportBackup) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::previewBackup) }
    LazyColumn(Modifier.fillMaxWidth().widthIn(max = 840.dp), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to library") }; Text("Backup & restore", style = MaterialTheme.typography.headlineSmall) }
            Text("Keep a copy of your favorites, playlists, podcast subscriptions, episode progress and listening history. No account required.")
        }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Working on your library… Keep Sonora open.") }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        item {
            ElevatedCard { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Save a backup", style = MaterialTheme.typography.titleLarge)
                Text("Choose a safe location outside Sonora before uninstalling or changing phones. The JSON file includes song titles and your listening history; it is not encrypted.")
                Button(onClick = { export.launch("sonora-library-${java.time.LocalDate.now()}.json") }, enabled = !state.busy) { Text("Export library") }
            } }
        }
        item {
            OutlinedCard { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Restore a backup", style = MaterialTheme.typography.titleLarge)
                Text("Preview a Sonora JSON backup, then merge it into this device. Existing favorites, history and playlist order are kept. Repeating a restore does not duplicate entries.")
                OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !state.busy) { Text("Choose Sonora backup") }
            } }
        }
        state.preview?.let { backup -> item {
            Text("Ready to restore", style = MaterialTheme.typography.titleLarge)
            Text("${backup.tracks.size} tracks · ${backup.favoriteCount} favorites · ${backup.playlists.size} playlists · ${backup.historyCount} history entries · ${backup.podcasts.size} podcasts · ${backup.progress.size} episode positions")
            Text("This adds missing data. It does not replace or delete your current library.")
            Button(onClick = vm::restoreBackup, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Merge backup into library") }
            TextButton(onClick = vm::dismissBackupPreview, enabled = !state.busy) { Text("Cancel restore") }
        } }
    }
}
