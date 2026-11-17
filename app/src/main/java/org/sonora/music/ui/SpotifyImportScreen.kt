package org.sonora.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.sonora.music.i18n.tr
import org.sonora.music.importing.*

@Composable fun SpotifyImportScreen(presenter: SpotifyImportPresenter, state: SpotifyImportState,
    onClose: () -> Unit, onImportMore: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            coil3.compose.AsyncImage(model = org.sonora.music.R.drawable.spotify_full_logo_white,
                contentDescription = tr("Metadata from Spotify"), modifier = Modifier.width(90.dp).height(28.dp))
            Text(tr("Spotify import"), style = MaterialTheme.typography.headlineSmall)
        }
        if (state.busy) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.progress, Modifier.padding(vertical = 16.dp))
            Text(tr("The best available YouTube versions are added automatically. No song review needed."))
            Text(tr("Keep Sonora open until this finishes. You can still listen to music."), style = MaterialTheme.typography.bodySmall)
            if (!state.committing) OutlinedButton(onClick = presenter::cancel) { Text(tr("Cancel import")) }
        }
        state.error?.let { message -> item {
            Text(message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onImportMore) { Text(tr("Choose another source")) }
        } }
        state.summary?.let { summary -> item {
            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(tr("Your music is ready"), style = MaterialTheme.typography.headlineSmall)
            Text(tr("%1\$s entries read · %2\$s matched · %3\$s skipped", state.entries, state.matched, state.skipped))
            Text(tr("%1\$s new playlists · %2\$s new favorites\n%3\$s tracks added to playlists · %4\$s new tracks", summary.newPlaylists, summary.newFavorites, summary.addedEntries, summary.newTracks))
            if (state.duplicates > 0) Text(tr("%1\$s repeated entries were kept only once.", state.duplicates), style = MaterialTheme.typography.bodySmall)
            if (state.skipped > 0) Text(tr("Entries with no available result were skipped. Import again to retry; existing songs are kept."), style = MaterialTheme.typography.bodySmall)
            if (state.failedSearches > 0) Text(tr("%1\$s searches could not finish. Check your connection and import again to add missing songs.", state.failedSearches), style = MaterialTheme.typography.bodySmall)
            if (state.skipped == 0 && summary == org.sonora.music.domain.ImportSummary()) Text(tr("Everything was already saved. No duplicates were added."))
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClose) { Text(tr("View library")) }
            TextButton(onClick = onImportMore) { Text(tr("Import more")) }
        } }
        if (state.publicLink) item {
            Text(tr("Spotify may expose only part of a playlist. To import missing entries, use a Spotify ZIP / JSON export."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
