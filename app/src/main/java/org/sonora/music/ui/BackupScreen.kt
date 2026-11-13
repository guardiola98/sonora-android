package org.sonora.music.ui

import org.sonora.music.i18n.tr

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
            Row { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back to library")) }; Text(tr("Backup & restore"), style = MaterialTheme.typography.headlineSmall) }
            Text(tr("Keep a copy of your favorites, playlists, podcast subscriptions, episode progress and listening history. No account required."))
        }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr("Working on your library… Keep Sonora open.")) }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        state.message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        item {
            ElevatedCard { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Save a backup"), style = MaterialTheme.typography.titleLarge)
                Text(tr("Choose a safe location outside Sonora before uninstalling or changing phones. The JSON file includes song titles and your listening history; it is not encrypted."))
                Button(onClick = { export.launch("sonora-library-${java.time.LocalDate.now()}.json") }, enabled = !state.busy) { Text(tr("Export library")) }
            } }
        }
        item {
            OutlinedCard { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Restore a backup"), style = MaterialTheme.typography.titleLarge)
                Text(tr("Preview a Sonora JSON backup, then merge it into this device. Existing favorites, history and playlist order are kept. Repeating a restore does not duplicate entries."))
                OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !state.busy) { Text(tr("Choose Sonora backup")) }
            } }
        }
        state.preview?.let { backup -> item {
            Text(tr("Ready to restore"), style = MaterialTheme.typography.titleLarge)
            Text(tr("%1\$s tracks · %2\$s favorites · %3\$s playlists · %4\$s history entries · %5\$s podcasts · %6\$s episode positions", backup.tracks.size, backup.favoriteCount, backup.playlists.size, backup.historyCount, backup.podcasts.size, backup.progress.size))
            Text(tr("This adds missing data. It does not replace or delete your current library."))
            Button(onClick = vm::restoreBackup, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(tr("Merge backup into library")) }
            TextButton(onClick = vm::dismissBackupPreview, enabled = !state.busy) { Text(tr("Cancel restore")) }
        } }
    }
}
