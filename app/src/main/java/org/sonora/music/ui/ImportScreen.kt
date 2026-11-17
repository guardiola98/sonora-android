package org.sonora.music.ui

import org.sonora.music.i18n.tr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import org.sonora.music.domain.YouTubeLinks

@UnstableApi
@Composable fun ImportScreen(vm: MusicViewModel, state: ImportState, onClose: () -> Unit) {
    val spotifyState by vm.spotifyImporter.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(vm, context) {
        onDispose {
            if ((context as? android.app.Activity)?.isChangingConfigurations != true) vm.spotifyImporter.cancel()
        }
    }
    if (spotifyState.active || spotifyState.error != null) {
        SpotifyImportScreen(vm.spotifyImporter, spotifyState, onClose) {
            vm.spotifyImporter.cancel(); vm.importer.reset()
        }
        return
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), vm::importFiles)
    val spotifyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), vm::importSpotifyFiles)
    var linkProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var help by rememberSaveable { mutableStateOf(false) }
    var browserError by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back to library")) }
                Text(tr("Import your music"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text(tr("Choose a playlist link or import a file."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let { message -> item {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        } }
        if (state.busy) {
            item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.progress, Modifier.padding(vertical = 16.dp))
                if (!state.committing) OutlinedButton(onClick = vm.importer::cancel) { Text(tr("Cancel import")) }
                Text(tr("Keep Sonora open until this finishes. You can still listen to music."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (state.summary != null) {
            item {
                val summary = state.summary
                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text(tr("Your music is ready"), style = MaterialTheme.typography.headlineSmall)
                Text(tr("%1\$s new playlists · %2\$s new favorites\n%3\$s tracks added to playlists · %4\$s new tracks", summary.newPlaylists, summary.newFavorites, summary.addedEntries, summary.newTracks))
                if (summary.newTracks + summary.newFavorites + summary.newPlaylists + summary.addedEntries == 0) Text(tr("Everything was already saved. No duplicates were added."))
                if (state.skipped > 0) Text(tr("%1\$s unavailable/invalid entries skipped", state.skipped), style = MaterialTheme.typography.bodySmall)
                if (summary.unresolved > 0) Text(tr("%1\$s tracks were saved by video ID because their titles were unavailable. Reimport with online lookup to retry. Unavailable videos may not play.", summary.unresolved), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onClose) { Text(tr("View library")) }
                TextButton(onClick = vm.importer::reset) { Text(tr("Import more")) }
            }
        } else if (state.preview != null) {
            item {
                Text(tr("Review before importing"), style = MaterialTheme.typography.titleLarge)
                Text(tr("%1\$s lists · %2\$s unique tracks", state.preview.collections.size, state.preview.trackCount))
                val skipped = state.preview.collections.sumOf { it.skipped }
                val duplicates = state.preview.collections.sumOf { it.duplicates }
                if (skipped + duplicates + state.preview.ignoredFiles > 0) Text(tr("%1\$s unavailable/invalid entries skipped · %2\$s duplicate entries · %3\$s unrelated files ignored", skipped, duplicates, state.preview.ignoredFiles), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(tr("Choose Playlists, Favorites, both, or neither for each list. Existing lists are merged; nothing in Sonora or YouTube is removed."), Modifier.padding(top = 12.dp))
            }
            items(state.preview.collections, key = { it.key }) { group ->
                val choice = state.choices.getValue(group.key)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(tr("%1\$s tracks", group.tracks.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilterChip(choice.playlist, { vm.importer.choice(group.key, !choice.playlist, choice.favorites) }, label = { Text(tr("Playlist")) })
                            FilterChip(choice.favorites, { vm.importer.choice(group.key, choice.playlist, !choice.favorites) }, label = { Text(tr("Favorites")) })
                        }
                        if (!choice.playlist && !choice.favorites) Text(tr("This list will be skipped."), style = MaterialTheme.typography.bodySmall)
                        group.tracks.take(3).forEach { track -> Text(track.title, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            item {
                val missing = state.preview.collections.flatMap { it.tracks }.distinctBy { it.url }.count(YouTubeLinks::needsDetails)
                if (missing > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state.enrich, vm.importer::enrich)
                        Column(Modifier.weight(1f)) {
                            Text(tr("Look up %1\$s missing titles online", missing))
                            Text(tr("Only video IDs are sent to YouTube. Disable to import offline using IDs."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Button(onClick = vm.importer::save, enabled = state.choices.values.any { it.playlist || it.favorites }, modifier = Modifier.fillMaxWidth()) { Text(tr("Import selected music")) }
                TextButton(onClick = vm.importer::reset) { Text(tr("Choose another source")) }
            }
        } else {
            item { PlaylistLinkOption(1, "YouTube Music", tr("Public or unlisted playlist link")) { linkProvider = "youtube" } }
            item { PlaylistLinkOption(2, "Spotify", tr("Public playlist link")) { linkProvider = "spotify" } }
            item { Text(tr("Or import a file"), style = MaterialTheme.typography.titleMedium) }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr("Import a Spotify export"), style = MaterialTheme.typography.titleLarge)
                        Text(tr("Choose your account-data ZIP, Playlist JSON or YourLibrary.json. All included playlists and saved songs are imported automatically using the best available YouTube versions."))
                        Button(onClick = { spotifyPicker.launch(arrayOf("*/*")) }) { Text(tr("Choose Spotify ZIP / JSON")) }
                        Text(tr("In Spotify account → Privacy settings, request Account data. When it is ready, download it and return here. Only playlist and saved-song files are read; profiles, payments and history are ignored."), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { browserError = runCatching { uriHandler.openUri("https://www.spotify.com/account/privacy/") }.isFailure }) { Text(tr("Open Spotify data export")) }
                        if (browserError) Text(tr("Open spotify.com/account/privacy in your browser."))
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                        Text(tr("Import a Takeout export"), style = MaterialTheme.typography.titleLarge)
                        Text(tr("Choose a ZIP or CSV from Google Takeout. Works with private playlists, library songs and exported likes."))
                        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text(tr("Choose ZIP / CSV files")) }
                    }
                }
            }
            item {
                TextButton(onClick = { help = !help }) { Text(if (help) tr("Hide export instructions") else tr("How do I export my playlists and likes?")) }
                if (help) {
                    Text(tr("1. Open Google Takeout in your browser and select YouTube and YouTube Music.\n2. Select playlists and music-library data; leave uploaded videos out. Choose a downloadable ZIP, not transfer to another service.\n3. Download the ZIP, return here, and choose it. You can also select individual CSV files.\n4. Review each list and select Favorites for your liked songs. Google may omit some likes; Sonora can only import tracks included in your files."))
                    TextButton(onClick = { browserError = runCatching { uriHandler.openUri("https://takeout.google.com/") }.isFailure }) { Text(tr("Open Google Takeout")) }
                    if (browserError) Text(tr("Open https://takeout.google.com in a browser on your phone or computer."))
                }
                Text(tr("Importing saves a copy in Sonora. After importing, use Download playlist to listen offline."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    linkProvider?.let { provider -> PlaylistLinkDialog(provider, { linkProvider = null }) { link ->
        linkProvider = null
        vm.importPublicPlaylist(link)
    } }

}

@Composable private fun PlaylistLinkOption(number: Int, name: String, detail: String, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(number.toString(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
        }
    }
}

@Composable private fun PlaylistLinkDialog(provider: String, onClose: () -> Unit, onImport: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val name = if (provider == "youtube") "YouTube Music" else "Spotify"
    var link by rememberSaveable(provider) { mutableStateOf("") }
    var error by rememberSaveable(provider) { mutableStateOf<String?>(null) }
    val submit = {
        val valid = if (provider == "youtube") YouTubeLinks.playlist(link) != null
            else org.sonora.music.importing.PublicSpotifyPlaylist.accepts(link)
        if (valid) onImport(link.trim()) else error = tr("Paste a %1\$s playlist link.", name)
    }
    AlertDialog(onDismissRequest = onClose, title = { Text(tr("%1\$s playlist", name)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("Open the playlist → Share → Copy link, then paste it here. No sign-in needed."))
            OutlinedTextField(link, { link = it; error = null }, Modifier.fillMaxWidth(),
                label = { Text(tr("Playlist link")) }, singleLine = true, isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }))
            TextButton(onClick = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if (text.isBlank()) error = tr("Copy a playlist link first.") else { link = text.trim(); error = null }
            }) { Icon(Icons.Rounded.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text(tr("Paste link")) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (provider == "spotify") Text(tr("The best available YouTube versions are added automatically. Spotify may expose only part of a long playlist."), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { submit() }, enabled = link.isNotBlank()) { Text(tr("Import playlist")) } },
        dismissButton = { TextButton(onClick = onClose) { Text(tr("Cancel")) } })
}
