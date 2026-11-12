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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import org.sonora.music.BuildConfig
import org.sonora.music.data.PlaylistSummary
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
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val selectedPlaylist by vm.selectedPlaylist.collectAsStateWithLifecycle()
    val playlistTracks by vm.playlistTracks.collectAsStateWithLifecycle()
    val importState by vm.importer.state.collectAsStateWithLifecycle()
    var showImport by rememberSaveable { mutableStateOf(false) }
    var showBackup by rememberSaveable { mutableStateOf(false) }
    var addTrack by remember { mutableStateOf<Track?>(null) }
    val backup by vm.backup.collectAsStateWithLifecycle()
    val playerError by vm.playerError.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val preparationEnabled by vm.preparationEnabled.collectAsStateWithLifecycle()
    val podcastState by vm.podcasts.state.collectAsStateWithLifecycle()
    val discoveryOptions by vm.discoveryOptions.collectAsStateWithLifecycle()
    val radio by vm.radio.collectAsStateWithLifecycle()
    val homeDiscovery by vm.homeDiscovery.state.collectAsStateWithLifecycle()
    val enrichedMetadata by vm.enrichedMetadata.collectAsStateWithLifecycle()
    val collectionState by vm.collection.state.collectAsStateWithLifecycle()
    var showDiscoverySettings by rememberSaveable { mutableStateOf(false) }
    var explainedTrack by remember { mutableStateOf<Track?>(null) }
    val favoriteIds = remember(favorites) { favorites.map { it.url }.toSet() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showUpdates by rememberSaveable { mutableStateOf(false) }
    val candidates = if (showPlayer || showImport || showBackup || !preparationEnabled) emptyList() else if (collectionState.entry != null) collectionState.tracks.take(2) else when (tab) {
        0 -> (history.ifEmpty { favorites }).take(2).map { it.track() }
        1 -> search.tracks.take(2)
        2 -> (if (selectedPlaylist == null) favorites else playlistTracks.second).take(2).map { it.track() }
        3 -> podcastState.show?.let { show -> podcastState.episodes.take(2).map { it.track(show) } }.orEmpty()
        else -> emptyList()
    }
    LifecycleResumeEffect(candidates) {
        vm.prepareBrowsing(candidates)
        onPauseOrDispose { vm.stopBrowsingPreparation() }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.dismissMessage() } }
    LaunchedEffect(player.current) { if (player.current == null) showPlayer = false }
    BackHandler(showBackup && !showPlayer) { showBackup = false }
    BackHandler(showImport && !showPlayer) { showImport = false }
    BackHandler(tab == 2 && selectedPlaylist != null && !showImport && !showBackup && !showPlayer) { vm.selectPlaylist(null) }
    BackHandler(showPlayer) { showPlayer = false }
    val onSearch: (String) -> Unit = { tab = 1; vm.search.search(it) }
    CompositionLocalProvider(LocalAddToPlaylist provides { track -> addTrack = track },
        LocalDiscoveryActions provides DiscoveryActions(radio = { vm.playback.playSingle(it, true) }, hide = { vm.notInterested(it) },
            more = { vm.moreLike(it) }, explain = { explainedTrack = it }, autoplay = discoveryOptions.autoplay,
            setAutoplay = { vm.discoveryOptions(discoveryOptions.copy(autoplay = it)) }, radioState = radio)) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (showPlayer && player.current != null) {
            FullPlayer(player, playerError, favoriteIds.contains(player.current!!.url),
                onClose = { showPlayer = false }, onFavorite = { vm.favorite(player.current!!) },
                onToggle = vm.playback::toggle, onNext = vm.playback::next, onPrevious = vm.playback::previous, onShuffle = vm.playback::shuffleUpcoming,
                onSeek = vm.playback::seek, onRewind = vm.playback::rewind, onForward = vm.playback::forward, onSpeed = vm.playback::speed, onQueue = { showQueue = true }, onRetry = vm.playback::retry)
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
                            listOf("Home" to Icons.Rounded.Home, "Search" to Icons.Rounded.Search, "Library" to Icons.Rounded.LibraryMusic, "Podcasts" to Icons.Rounded.Podcasts)
                                .forEachIndexed { i, (label, icon) ->
                                    NavigationBarItem(selected = tab == i, onClick = { tab = i; showImport = false; showBackup = false }, icon = { Icon(icon, label) }, label = { Text(label) })
                                }
                        }
                    }
                }) { padding ->
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    if (showBackup) BackupScreen(vm, backup) { showBackup = false; tab = 2 }
                    else if (showImport) ImportScreen(vm, importState, onClose = { showImport = false; tab = 2 })
                    else AnimatedContent(tab, label = "screen", modifier = Modifier.widthIn(max = 840.dp).fillMaxSize()) { selected ->
                        when (selected) {
                            0 -> DiscoveryHome(vm, player, onSearch, { tab = 2 }, { showPlayer = true }, { showDiscoverySettings = true })
                            1 -> DiscoverySearch(vm, search, favoriteIds, player.connected, onSearch)
                            3 -> PodcastScreen(vm, player.connected, favoriteIds)
                            else -> LibraryScreen(vm, favorites.map { it.track() }, history.map { it.track() }, favoriteIds, player.connected,
                                { tab = 1 }, playlists, playlists.firstOrNull { it.playlist.id == selectedPlaylist },
                                playlistTracks.second.takeIf { playlistTracks.first == selectedPlaylist }.orEmpty().map { it.track() },
                                { showImport = true }, { showBackup = true })
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
                Box(Modifier.padding(horizontal = 24.dp)) {
                    ShuffleUpcomingButton(player.canShuffleUpcoming, vm.playback::shuffleUpcoming)
                }
                QueueAutoplayControl()
                if (!radio.active) Text("Manual queue: no automatic tracks will be added.", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall)
                radio.notice?.let { Text(it, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall); TextButton(onClick = vm.playback::retryRadio) { Text("Retry radio") } }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (player.queue.isEmpty()) item { EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "Your queue is empty", "Add a track from search or your library.") }
                    itemsIndexed(player.queue, key = { i, t -> "$i:${t.url}" }) { i, track ->
                        Row(Modifier.fillMaxWidth().clickable { vm.playback.jump(i) }.padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (i == player.index) "♫" else "${i + 1}", Modifier.width(28.dp), color = MaterialTheme.colorScheme.primary)
                            Artwork(track, Modifier.size(48.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                radio.automatic[track.url]?.let { Text("Radio · $it", maxLines = 2, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                            }
                            IconButton(onClick = { vm.playback.moveUp(i) }, enabled = i > 0) { Icon(Icons.Rounded.ArrowUpward, "Move ${track.title} up") }
                            IconButton(onClick = { vm.removeQueue(i) }) { Icon(Icons.Rounded.Close, "Remove ${track.title} from queue") }
                        }
                    }
                }
            }
        }
        if (showAbout) AboutDialog(preparationEnabled, vm::setPreparationEnabled,
            onDiscovery = { showAbout = false; showDiscoverySettings = true },
            onUpdates = { showAbout = false; showUpdates = true },
        ) { showAbout = false }
        if (showUpdates) UpdatesDialog(vm.updates) { showUpdates = false; showAbout = true }
        if (showDiscoverySettings) DiscoverySettingsDialog(vm) { showDiscoverySettings = false }
        if (collectionState.entry != null) DiscoveryCollection(vm, collectionState, favoriteIds, player.connected)
        explainedTrack?.let { track -> RecommendationDetails(track, homeDiscovery.recommendations.firstOrNull { it.track.url == track.url }, enrichedMetadata[track.url], radio.automatic[track.url]) { explainedTrack = null } }
        addTrack?.let { track -> AddToPlaylistDialog(track, playlists, { addTrack = null },
            { id -> vm.addToPlaylist(id, track) { addTrack = null } },
            { name -> vm.createPlaylist(name, track) { addTrack = null } }) }
    }
    }
}

@Composable fun TrackRow(track: Track, favorite: Boolean, connected: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit, onEnqueue: () -> Unit,
    modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, onRemove: (() -> Unit)? = null, onMoveUp: (() -> Unit)? = null, onMoveDown: (() -> Unit)? = null) {
    val addToPlaylist = LocalAddToPlaylist.current
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().clickable(enabled = connected, onClick = onPlay).padding(start = if (leading == null) 24.dp else 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Artwork(track, Modifier.size(if (leading == null) 60.dp else 44.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(3.dp))
            Text("${track.artist} · ${if (track.durationSeconds > 0) formatDuration(track.durationSeconds) else "—"}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (favorite) "Remove ${track.title} from favorites" else "Favorite ${track.title}", tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Options for ${track.title}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DiscoveryTrackMenu(track) { menu = false }
                DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { menu = false; addToPlaylist(track) })
                DropdownMenuItem(text = { Text("Add to queue") }, enabled = connected, onClick = { menu = false; onEnqueue() })
                onMoveUp?.let { action -> DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; action() }) }
                onMoveDown?.let { action -> DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; action() }) }
                onRemove?.let { action -> DropdownMenuItem(text = { Text("Remove from playlist") }, onClick = { menu = false; action() }) }
            }
        }
    }
}

@Composable fun Artwork(track: Track, modifier: Modifier, large: Boolean = false) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun FullPlayer(player: PlayerSnapshot, error: String?, favorite: Boolean, onClose: () -> Unit, onFavorite: () -> Unit,
    onToggle: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onShuffle: () -> Unit, onSeek: (Long) -> Unit, onRewind: () -> Unit, onForward: () -> Unit, onSpeed: (Float) -> Unit, onQueue: () -> Unit, onRetry: () -> Unit) {
    val track = player.current ?: return
    val addToPlaylist = LocalAddToPlaylist.current
    var drag by remember(track.url) { mutableStateOf<Float?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val artworkSize = minOf(
            maxHeight * 0.42f,
            (maxHeight - 422.dp).coerceAtLeast(88.dp),
            (maxWidth - 40.dp).coerceAtLeast(1.dp),
            400.dp,
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close player") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    Text(if (LocalDiscoveryActions.current.radioState.active) "Sonora radio" else "Your queue", style = MaterialTheme.typography.titleSmall)
                }
                IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Open queue") }
            }
            Column(Modifier.widthIn(max = 500.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Artwork(track, Modifier.align(Alignment.CenterHorizontally).size(artworkSize), large = true)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Text(track.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (favorite) "Remove from favorites" else "Add to favorites", tint = MaterialTheme.colorScheme.primary) }
                }
                val actions = LocalDiscoveryActions.current
                val isMusic = !org.sonora.music.domain.PodcastLinks.isEpisode(track.url)
                FlowRow {
                    ShuffleUpcomingButton(player.canShuffleUpcoming, onShuffle)
                    if (isMusic) AutoplayControl(actions.autoplay, actions.setAutoplay)
                    TextButton(onClick = { addToPlaylist(track) }) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add to playlist")
                    }
                }
                if (isMusic && actions.radioState.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Slider(value = drag ?: player.positionMs.toFloat().coerceIn(0f, player.durationMs.coerceAtLeast(1).toFloat()),
                    onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { onSeek(it.toLong()) }; drag = null },
                    valueRange = 0f..player.durationMs.coerceAtLeast(1).toFloat(), enabled = player.durationMs > 0)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration((drag?.toLong() ?: player.positionMs) / 1000), style = MaterialTheme.typography.labelMedium)
                    Text(formatDuration(player.durationMs / 1000), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious, modifier = Modifier.size(if (isMusic) 56.dp else 48.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, if (isMusic) "Previous track or restart" else "Previous episode or restart", Modifier.size(36.dp))
                    }
                    if (!isMusic) FilledTonalIconButton(onClick = onRewind, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Replay10, "Rewind 10 seconds", Modifier.size(30.dp))
                    }
                    FilledIconButton(onClick = onToggle, modifier = Modifier.size(80.dp)) { Icon(if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.playing) "Pause" else "Play", Modifier.size(42.dp)) }
                    if (!isMusic) FilledTonalIconButton(onClick = onForward, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Forward10, "Forward 10 seconds", Modifier.size(30.dp))
                    }
                    IconButton(onClick = onNext, enabled = player.index < player.queue.lastIndex, modifier = Modifier.size(if (isMusic) 56.dp else 48.dp)) {
                        Icon(Icons.Rounded.SkipNext, if (isMusic) "Next track" else "Next episode", Modifier.size(36.dp))
                    }
                }
                if (isMusic) {
                    FlowRow {
                        TextButton(onClick = { actions.radio(track) }) { Text("Start radio") }
                        TextButton(onClick = { actions.explain(track) }) { Text("Music details") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        var speedMenu by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { speedMenu = true }) { Text("${player.speed}×") }
                            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                                listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                                    DropdownMenuItem(text = { Text("${speed}×") }, onClick = { onSpeed(speed); speedMenu = false })
                                }
                            }
                        }
                    }
                }
                if (player.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { ErrorBanner(it, onRetry) }
                TextButton(onClick = onQueue, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, null); Spacer(Modifier.width(8.dp)); Text("Queue · ${player.index + 1} of ${player.queue.size}")
                }
            }
        }
    }
}

@Composable private fun ShuffleUpcomingButton(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Rounded.Shuffle, null)
        Spacer(Modifier.width(4.dp))
        Text("Shuffle up next")
    }
}

@Composable fun ErrorBanner(message: String, retry: () -> Unit, canRetry: Boolean = true) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            if (canRetry) TextButton(onClick = retry) { Text("Retry", color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}
@Composable fun EmptyState(icon: ImageVector, title: String, body: String) {
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
@Composable private fun AboutDialog(preparationEnabled: Boolean, onPreparationChanged: (Boolean) -> Unit, onDiscovery: () -> Unit, onUpdates: () -> Unit, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Sonora ${BuildConfig.VERSION_NAME}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("An independent, open-source music and podcast player. Built with NewPipeExtractor and Android Media3. Not affiliated with YouTube or Google.")
            Spacer(Modifier.height(12.dp))
            Text("GPL-3.0-or-later. No Sonora account required. No ads or analytics. Favorites and history stay on this device. Streaming sends requests to YouTube, podcast publishers and image hosts. Podcast search uses Apple’s public directory.")
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Faster playback starts", style = MaterialTheme.typography.titleSmall)
                    Text("Prepare likely tracks and the next in your queue. Uses extra data.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = preparationEnabled, onCheckedChange = onPreparationChanged)
            }
            TextButton(onClick = onUpdates) { Text("Updates") }
            TextButton(onClick = onDiscovery) { Text("Discovery & autoplay settings") }
            TextButton(onClick = { uri.openUri("https://github.com/guardiola98/sonora-android") }) { Text("Source code & licenses") }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
