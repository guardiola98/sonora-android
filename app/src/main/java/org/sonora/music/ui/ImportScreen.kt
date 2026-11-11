package org.sonora.music.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
    val accountState by vm.accountImporter.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(vm, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && !vm.accountImporter.state.value.authorizing && !vm.accountImporter.state.value.disconnected) vm.accountImporter.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if ((context as? android.app.Activity)?.isChangingConfigurations != true) vm.accountImporter.cancel()
        }
    }
    if (accountState.provider != null || accountState.error != null) {
        AccountImportReview(vm.accountImporter, accountState)
        return
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), vm::importFiles)
    val spotifyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), vm::importSpotifyFiles)
    var url by rememberSaveable { mutableStateOf("") }
    var help by rememberSaveable { mutableStateOf(false) }
    var browserError by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to library") }
                Text("Import your music", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text("Copy playlists and exported likes into your local Sonora library.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                if (!state.committing) OutlinedButton(onClick = vm.importer::cancel) { Text("Cancel import") }
                Text("Keep Sonora open until this finishes. You can still listen to music.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (state.summary != null) {
            item {
                val summary = state.summary
                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Your music is ready", style = MaterialTheme.typography.headlineSmall)
                Text("${summary.newPlaylists} new playlists · ${summary.newFavorites} new favorites\n${summary.addedEntries} tracks added to playlists · ${summary.newTracks} new tracks")
                if (summary.newTracks + summary.newFavorites + summary.newPlaylists + summary.addedEntries == 0) Text("Everything was already saved. No duplicates were added.")
                if (summary.unresolved > 0) Text("${summary.unresolved} tracks were saved by video ID because their titles were unavailable. Reimport with online lookup to retry. Unavailable videos may not play.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onClose) { Text("View library") }
                TextButton(onClick = vm.importer::reset) { Text("Import more") }
            }
        } else if (state.preview != null) {
            item {
                Text("Review before importing", style = MaterialTheme.typography.titleLarge)
                Text("${state.preview.collections.size} lists · ${state.preview.trackCount} unique tracks")
                val skipped = state.preview.collections.sumOf { it.skipped }
                val duplicates = state.preview.collections.sumOf { it.duplicates }
                if (skipped + duplicates + state.preview.ignoredFiles > 0) Text("$skipped unavailable/invalid entries skipped · $duplicates duplicate entries · ${state.preview.ignoredFiles} unrelated files ignored", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Choose Playlists, Favorites, both, or neither for each list. Existing lists are merged; nothing in Sonora or YouTube is removed.", Modifier.padding(top = 12.dp))
            }
            items(state.preview.collections, key = { it.key }) { group ->
                val choice = state.choices.getValue(group.key)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${group.tracks.size} tracks", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilterChip(choice.playlist, { vm.importer.choice(group.key, !choice.playlist, choice.favorites) }, label = { Text("Playlist") })
                            FilterChip(choice.favorites, { vm.importer.choice(group.key, choice.playlist, !choice.favorites) }, label = { Text("Favorites") })
                        }
                        if (!choice.playlist && !choice.favorites) Text("This list will be skipped.", style = MaterialTheme.typography.bodySmall)
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
                            Text("Look up $missing missing titles online")
                            Text("Only video IDs are sent to YouTube. Disable to import offline using IDs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Button(onClick = vm.importer::save, enabled = state.choices.values.any { it.playlist || it.favorites }, modifier = Modifier.fillMaxWidth()) { Text("Import selected music") }
                TextButton(onClick = vm.importer::reset) { Text("Choose another source") }
            }
        } else {
            item { AccountImportButtons(vm) }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Import a Spotify export", style = MaterialTheme.typography.titleLarge)
                        Text("Choose your account-data ZIP, Playlist JSON or YourLibrary.json. No developer account or OAuth setup needed. Select lists first, then review playable versions.")
                        Button(onClick = { spotifyPicker.launch(arrayOf("*/*")) }) { Text("Choose Spotify ZIP / JSON") }
                        Text("In Spotify account → Privacy settings, request Account data. When it is ready, download it and return here. Only playlist and saved-song files are read; profiles, payments and history are ignored.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { browserError = runCatching { uriHandler.openUri("https://www.spotify.com/account/privacy/") }.isFailure }) { Text("Open Spotify data export") }
                        if (browserError) Text("Open spotify.com/account/privacy in your browser.")
                    }
                }
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                        Text("Import a Takeout export", style = MaterialTheme.typography.titleLarge)
                        Text("Choose a ZIP or CSV from Google Takeout. Works with private playlists, library songs and exported likes.")
                        Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Choose ZIP / CSV files") }
                    }
                }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Import a playlist link", style = MaterialTheme.typography.titleLarge)
                        Text("For public or unlisted YouTube / YouTube Music playlists.")
                        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Playlist link") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                        Button(onClick = { vm.importer.loadPlaylist(url) }, enabled = url.isNotBlank()) { Text("Preview playlist") }
                    }
                }
            }
            item {
                TextButton(onClick = { help = !help }) { Text(if (help) "Hide export instructions" else "How do I export my playlists and likes?") }
                if (help) {
                    Text("1. Open Google Takeout in your browser and select YouTube and YouTube Music.\n2. Select playlists and music-library data; leave uploaded videos out. Choose a downloadable ZIP, not transfer to another service.\n3. Download the ZIP, return here, and choose it. You can also select individual CSV files.\n4. Review each list and select Favorites for your liked songs. Google may omit some likes; Sonora can only import tracks included in your files.")
                    TextButton(onClick = { browserError = runCatching { uriHandler.openUri("https://takeout.google.com/") }.isFailure }) { Text("Open Google Takeout") }
                    if (browserError) Text("Open https://takeout.google.com in a browser on your phone or computer.")
                }
                Text("This is a one-time copy. Temporary authorization ends after reading your selection; account synchronization and audio downloads are not included.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
