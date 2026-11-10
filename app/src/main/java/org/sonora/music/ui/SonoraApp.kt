package org.sonora.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import org.sonora.music.domain.Track
import org.sonora.music.domain.formatDuration
import org.sonora.music.playback.PlayerSnapshot

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable fun SonoraApp(vm: MusicViewModel) {
    val player by vm.playback.state.collectAsStateWithLifecycle()
    val search by vm.search.state.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val playerError by vm.playerError.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val favoriteIds = remember(favorites) { favorites.map { it.url }.toSet() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    LaunchedEffect(player.current) { if (player.current == null) showPlayer = false }
    BackHandler(showPlayer) { showPlayer = false }
    val onSearch: (String) -> Unit = { tab = 1; vm.search.search(it) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (showPlayer && player.current != null) {
            FullPlayer(player, playerError, favoriteIds.contains(player.current!!.url),
                onClose = { showPlayer = false }, onFavorite = { vm.favorite(player.current!!) },
                onToggle = vm.playback::toggle, onNext = vm.playback::next, onPrevious = vm.playback::previous,
                onSeek = vm.playback::seek, onQueue = { showQueue = true }, onRetry = vm.playback::retry)
        } else {
            Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbar) },
                topBar = { TopAppBar(title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                        Text("sonora", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                    }
                }, actions = { IconButton(onClick = { showAbout = true }) { Icon(Icons.Rounded.Info, "About Sonora") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
                bottomBar = {
                    Column {
                        playerError?.let { ErrorBanner(it, vm.playback::retry, player.current != null) }
                        player.current?.let { MiniPlayer(player, { showPlayer = true }, vm.playback::toggle, vm.playback::next) }
                        NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                            listOf("Home" to Icons.Rounded.Home, "Search" to Icons.Rounded.Search, "Library" to Icons.Rounded.LibraryMusic)
                                .forEachIndexed { i, (label, icon) ->
                                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, label) }, label = { Text(label) })
                                }
                        }
                    }
                }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    AnimatedContent(tab, label = "screen", modifier = Modifier.widthIn(max = 840.dp).fillMaxSize()) { selected ->
                        when (selected) {
                            0 -> HomeScreen(favorites.map { it.track() }, history.map { it.track() }, favoriteIds, player, onSearch,
                                onPlay = vm.playback::play, onFavorite = vm::favorite, onEnqueue = vm::enqueue, onLibrary = { tab = 2 })
                            1 -> SearchScreen(search, favoriteIds, player.connected, onSearch, vm.playback::play, vm::favorite, vm::enqueue)
                            else -> LibraryScreen(favorites.map { it.track() }, history.map { it.track() }, favoriteIds, player.connected,
                                vm.playback::play, vm::favorite, vm::enqueue, vm::clearHistory, { tab = 1 })
                        }
                    }
                }
            }
        }
        if (showQueue) {
            ModalBottomSheet(onDismissRequest = { showQueue = false }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Up next", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("${player.queue.size} tracks", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.playback.clear(); showQueue = false }, enabled = player.queue.isNotEmpty()) { Text("Clear queue") }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (player.queue.isEmpty()) item { EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "Your queue is empty", "Add a track from search or your library.") }
                    itemsIndexed(player.queue, key = { i, t -> "$i:${t.url}" }) { i, track ->
                        Row(Modifier.fillMaxWidth().clickable { vm.playback.jump(i) }.padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (i == player.index) "♫" else "${i + 1}", Modifier.width(28.dp), color = MaterialTheme.colorScheme.primary)
                            Artwork(track, Modifier.size(48.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { vm.playback.moveUp(i) }, enabled = i > 0) { Icon(Icons.Rounded.ArrowUpward, "Move ${track.title} up") }
                            IconButton(onClick = { vm.playback.remove(i) }) { Icon(Icons.Rounded.Close, "Remove ${track.title} from queue") }
                        }
                    }
                }
            }
        }
        if (showAbout) AboutDialog { showAbout = false }
    }
}

@Composable private fun HomeScreen(favorites: List<Track>, history: List<Track>, favoriteIds: Set<String>, player: PlayerSnapshot,
    onSearch: (String) -> Unit, onPlay: (List<Track>, Int) -> Unit, onFavorite: (Track) -> Unit, onEnqueue: (Track) -> Unit, onLibrary: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("YOUR MUSIC, YOUR MOMENT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(10.dp))
                Text("A little more\nsound in your day.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                Surface(onClick = { onSearch("") }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Find your next favorite", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text("Search songs, artists and live sessions", color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Icon(Icons.Rounded.Search, null, Modifier.size(32.dp))
                    }
                }
            }
        }
        item { SectionTitle("Set the mood") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("Slow mornings" to "acoustic chill music", "Deep focus" to "instrumental focus music", "After hours" to "jazz late night", "Fresh energy" to "upbeat indie music")) { (label, query) ->
                    SuggestionChip(onClick = { onSearch(query) }, label = { Text(label) })
                }
            }
        }
        item { SectionTitle("On repeat", if (favorites.isNotEmpty()) "See library" else null, onLibrary) }
        if (favorites.isEmpty()) item { EmptyState(Icons.Rounded.FavoriteBorder, "Make it yours", "Tap the heart on a track to keep it in your library.") }
        else item {
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(favorites.take(12), key = { _, t -> t.url }) { i, track ->
                    Column(Modifier.width(156.dp).clickable(enabled = player.connected) { onPlay(favorites, i) }) {
                        Artwork(track, Modifier.size(156.dp), large = true)
                        Spacer(Modifier.height(10.dp))
                        Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (history.isNotEmpty()) {
            item { SectionTitle("Recently played") }
            itemsIndexed(history.take(8), key = { _, t -> t.url }) { i, track ->
                TrackRow(track, track.url in favoriteIds, player.connected, { onPlay(history, i) }, { onFavorite(track) }, { onEnqueue(track) })
            }
        }
        item { Text("SONORA · BETA 0.1", Modifier.padding(24.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
    }
}

@Composable private fun SearchScreen(state: SearchState, favoriteIds: Set<String>, connected: Boolean,
    onSearch: (String) -> Unit, onPlay: (List<Track>, Int) -> Unit, onFavorite: (Track) -> Unit, onEnqueue: (Track) -> Unit) {
    var query by rememberSaveable { mutableStateOf(state.query) }
    LaunchedEffect(state.query) { query = state.query }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = { keyboard?.hide(); onSearch(query) }
    Column {
        Text("Search", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            placeholder = { Text("Songs, artists, something new…") }, singleLine = true, shape = RoundedCornerShape(18.dp),
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { IconButton(onClick = submit, enabled = query.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Search music") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }))
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            Text("Searching the source…", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let { ErrorBanner(it, { onSearch(state.query) }) }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            if (!state.searched && !state.loading) item { EmptyState(Icons.Rounded.TravelExplore, "Follow your curiosity", "Search public YouTube tracks. No account needed.") }
            if (state.searched && state.error == null && state.tracks.isEmpty()) item { EmptyState(Icons.Rounded.SearchOff, "No tracks found", "Try a different title or artist.") }
            if (state.tracks.isNotEmpty()) item { Text("${state.tracks.size} results", Modifier.padding(24.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            itemsIndexed(state.tracks, key = { _, t -> t.url }) { i, track ->
                TrackRow(track, track.url in favoriteIds, connected, { onPlay(state.tracks, i) }, { onFavorite(track) }, { onEnqueue(track) })
            }
        }
    }
}

@Composable private fun LibraryScreen(favorites: List<Track>, history: List<Track>, favoriteIds: Set<String>, connected: Boolean,
    onPlay: (List<Track>, Int) -> Unit, onFavorite: (Track) -> Unit, onEnqueue: (Track) -> Unit, onClear: () -> Unit, onSearch: () -> Unit) {
    var historyTab by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val tracks = if (historyTab) history else favorites
    Column {
        Text("Your library", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(!historyTab, { historyTab = false }, label = { Text("Favorites · ${favorites.size}") })
            FilterChip(historyTab, { historyTab = true }, label = { Text("History · ${history.size}") })
        }
        if (tracks.isNotEmpty()) Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { onPlay(tracks, 0) }, enabled = connected) { Icon(Icons.Rounded.PlayArrow, null); Text("Play all") }
            Spacer(Modifier.weight(1f))
            if (historyTab) TextButton(onClick = { confirm = true }) { Text("Clear history") }
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 16.dp)) {
            if (tracks.isEmpty()) item {
                EmptyState(if (historyTab) Icons.Rounded.History else Icons.Rounded.FavoriteBorder,
                    if (historyTab) "Start listening" else "Keep the good ones",
                    if (historyTab) "Tracks appear here after playback begins." else "Your favorite tracks will appear here.")
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton(onClick = onSearch) { Text("Discover music") } }
            }
            itemsIndexed(tracks, key = { _, t -> t.url }) { i, track -> TrackRow(track, track.url in favoriteIds, connected, { onPlay(tracks, i) }, { onFavorite(track) }, { onEnqueue(track) }) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Clear listening history?") },
        text = { Text("Favorites will stay in your library.") }, confirmButton = { TextButton(onClick = { onClear(); confirm = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable private fun TrackRow(track: Track, favorite: Boolean, connected: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit, onEnqueue: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = connected, onClick = onPlay).padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track, Modifier.size(60.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(3.dp))
            Text("${track.artist} · ${formatDuration(track.durationSeconds)}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (favorite) "Remove ${track.title} from favorites" else "Favorite ${track.title}", tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(onClick = onEnqueue, enabled = connected) { Icon(Icons.Rounded.PlaylistAdd, "Add ${track.title} to queue", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun Artwork(track: Track, modifier: Modifier, large: Boolean = false) {
    Box(modifier.clip(RoundedCornerShape(if (large) 24.dp else 12.dp)).background(Brush.linearGradient(listOf(Color(0xFF42325F), Color(0xFF223943)))), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, Modifier.fillMaxSize(0.35f), tint = Color(0xFFC5B4FF))
        if (track.artwork.isNotBlank()) AsyncImage(track.artwork, "Artwork for ${track.title}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable private fun MiniPlayer(player: PlayerSnapshot, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    val track = player.current ?: return
    Surface(onClick = onOpen, modifier = Modifier.padding(horizontal = 12.dp), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column {
            Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track, Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(if (player.buffering) "Loading audio…" else track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onToggle) { Icon(if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.playing) "Pause" else "Play") }
                IconButton(onClick = onNext, enabled = player.index < player.queue.lastIndex) { Icon(Icons.Rounded.SkipNext, "Next track") }
            }
            LinearProgressIndicator(progress = { if (player.durationMs > 0) (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@Composable private fun FullPlayer(player: PlayerSnapshot, error: String?, favorite: Boolean, onClose: () -> Unit, onFavorite: () -> Unit,
    onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit, onQueue: () -> Unit, onRetry: () -> Unit) {
    val track = player.current ?: return
    var drag by remember(track.url) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close player") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                Text("Your queue", style = MaterialTheme.typography.titleSmall)
            }
            IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Open queue") }
        }
        Column(Modifier.widthIn(max = 500.dp).fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp)) {
            Artwork(track, Modifier.fillMaxWidth().aspectRatio(1f), large = true)
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Text(track.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (favorite) "Remove from favorites" else "Add to favorites", tint = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.height(20.dp))
            Slider(value = drag ?: player.positionMs.toFloat().coerceIn(0f, player.durationMs.coerceAtLeast(1).toFloat()),
                onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { onSeek(it.toLong()) }; drag = null },
                valueRange = 0f..player.durationMs.coerceAtLeast(1).toFloat(), enabled = player.durationMs > 0)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration((drag?.toLong() ?: player.positionMs) / 1000), style = MaterialTheme.typography.labelMedium)
                Text(formatDuration(player.durationMs / 1000), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous track or restart", Modifier.size(36.dp)) }
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(80.dp)) { Icon(if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.playing) "Pause" else "Play", Modifier.size(42.dp)) }
                IconButton(onClick = onNext, enabled = player.index < player.queue.lastIndex, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Next track", Modifier.size(36.dp)) }
            }
            if (player.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { ErrorBanner(it, onRetry) }
            TextButton(onClick = onQueue, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, null); Spacer(Modifier.width(8.dp)); Text("Queue · ${player.index + 1} of ${player.queue.size}")
            }
        }
    }
}

@Composable private fun ErrorBanner(message: String, retry: () -> Unit, canRetry: Boolean = true) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            if (canRetry) TextButton(onClick = retry) { Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}
@Composable private fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
@Composable private fun SectionTitle(title: String, action: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (action != null) TextButton(onClick = onClick) { Text(action) }
    }
}
@Composable private fun AboutDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Sonora 0.1 beta") },
        text = { Column {
            Text("An independent, open-source music player. Built with NewPipeExtractor and Android Media3. Not affiliated with YouTube or Google.")
            Spacer(Modifier.height(12.dp))
            Text("GPL-3.0-or-later. No accounts, ads or analytics. Favorites and history stay on this device. Streaming sends requests to YouTube and image hosts.")
            TextButton(onClick = { uri.openUri("https://github.com/guardiola98/sonora-android") }) { Text("Source code & licenses") }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
