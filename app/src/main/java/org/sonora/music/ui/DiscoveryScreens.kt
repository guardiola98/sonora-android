package org.sonora.music.ui

import org.sonora.music.i18n.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.util.UnstableApi
import org.sonora.music.discovery.*
import org.sonora.music.domain.*
import org.sonora.music.playback.PlayerSnapshot

class DiscoveryActions(val radio: (Track) -> Unit = {}, val hide: (Track) -> Unit = {}, val more: (Track) -> Unit = {},
    val explain: (Track) -> Unit = {}, val autoplay: Boolean = true, val setAutoplay: (Boolean) -> Unit = {}, val radioState: RadioSnapshot = RadioSnapshot())
val LocalDiscoveryActions = staticCompositionLocalOf { DiscoveryActions() }

@Composable fun DiscoveryTrackMenu(track: Track, close: () -> Unit) {
    val actions = LocalDiscoveryActions.current
    if (YouTubeLinks.track(track.url) != null) {
        DropdownMenuItem(text = { Text(tr("Start radio")) }, onClick = { close(); actions.radio(track) }, leadingIcon = { Icon(Icons.Rounded.Radio, null) })
        DropdownMenuItem(text = { Text(tr("More music like this")) }, onClick = { close(); actions.more(track) })
        DropdownMenuItem(text = { Text(tr("Not interested")) }, onClick = { close(); actions.hide(track) })
        DropdownMenuItem(text = { Text(tr("Why this / music details")) }, onClick = { close(); actions.explain(track) })
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoplayControl(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val description = if (checked) tr("Autoplay on") else tr("Autoplay off")
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        IconToggleButton(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(48.dp).semantics { contentDescription = tr("Autoplay") },
            colors = IconButtonDefaults.iconToggleButtonColors(
                contentColor = colors.onSurfaceVariant,
                checkedContainerColor = colors.primary.copy(alpha = 0.14f),
                checkedContentColor = colors.primary,
            ),
        ) {
            Icon(Icons.Rounded.AllInclusive, contentDescription = null)
        }
    }
}
@Composable internal fun QueueAutoplayControl() {
    val actions = LocalDiscoveryActions.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Autoplay"), style = MaterialTheme.typography.titleSmall)
            Text(if (actions.radioState.active) tr("Related music continues after this track") else tr("Applies when you play a single song or start radio"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(actions.autoplay, actions.setAutoplay, Modifier.semantics { contentDescription = tr("Autoplay related music") })
    }
    if (actions.radioState.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
}
@Composable private fun MusicCard(track: Track, connected: Boolean, favorite: Boolean, play: () -> Unit, favoriteAction: () -> Unit, subtitle: String? = null) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.width(168.dp)) {
        Box {
            Artwork(track, Modifier.size(168.dp).clickable(enabled = connected, onClick = play), large = true)
            FilledTonalIconButton(onClick = { menu = true }, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp)) { Icon(Icons.Rounded.MoreVert, tr("Options for %1\$s", track.title)) }
            DropdownMenu(menu, { menu = false }) {
                DiscoveryTrackMenu(track) { menu = false }
                DropdownMenuItem(text = { Text(if (favorite) tr("Remove favorite") else tr("Favorite")) }, onClick = { menu = false; favoriteAction() })
            }
        }
        Text(track.title, Modifier.padding(top = 8.dp).clickable(enabled = connected, onClick = play), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        Text(subtitle ?: tr("Source: %1\$s", track.artist), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun ShelfTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@UnstableApi
@Composable fun DiscoveryHome(vm: MusicViewModel, player: PlayerSnapshot, onSearch: (String) -> Unit,
    onLibrary: () -> Unit, onOpenPlayer: () -> Unit, onSettings: () -> Unit) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val options by vm.discoveryOptions.collectAsStateWithLifecycle()
    val discovery by vm.homeDiscovery.state.collectAsStateWithLifecycle()
    val metadata by vm.enrichedMetadata.collectAsStateWithLifecycle()
    val favoriteIds = remember(favorites) { favorites.map { it.url }.toSet() }
    val seed = if (options.personalized) (history.firstOrNull { !PodcastLinks.isEpisode(it.url) } ?: favorites.firstOrNull { !PodcastLinks.isEpisode(it.url) })?.track() else null
    LifecycleResumeEffect(seed?.url, options) { vm.refreshHome(seed); onPauseOrDispose { vm.homeDiscovery.cancel() } }
    val library = remember(history, favorites) { (history + favorites).distinctBy { it.url } }
    val artists = remember(library, metadata) { library.groupBy { metadata[it.url]?.artistId?.value }.filterKeys { it != null }.values.sortedByDescending { rows -> rows.sumOf { it.playCount } }.take(8) }
    val albums = remember(library, metadata) { library.filter { metadata[it.url]?.album?.confidence?.let { it >= .85 } == true }.groupBy { metadata[it.url]?.albumId?.value }.filterKeys { it != null }.values.take(8) }
    val channels = remember(history) { history.filterNot { PodcastLinks.isEpisode(it.url) }.groupBy { it.artist }.filterKeys { it.isNotBlank() }.values.sortedByDescending { rows -> rows.sumOf { it.playCount } }.take(8) }
    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Your next discovery"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(tr("Music that grows with you"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Tune, tr("Discovery settings")) }
            }
        }
        player.current?.let { current -> item {
            ElevatedCard(onClick = onOpenPlayer, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(current, Modifier.size(72.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(tr("Continue listening"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(current.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(tr("%1\$s queued · %2\$s", player.queue.size, formatDuration(player.positionMs / 1000)), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { if (player.playing) onOpenPlayer() else vm.playback.toggle() }) { Icon(if (player.playing) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow, tr("Resume saved queue")) }
                }
            }
        } }
        if (history.isNotEmpty()) {
            item { ShelfTitle(tr("Listen again"), tr("Recently played on this device")) }
            item { LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(history.take(15), key = { it.url }) { row -> MusicCard(row.track(), player.connected, row.favorite, { vm.playback.playSingle(row.track()) }, { vm.favorite(row.track()) }) }
            } }
        }
        if (discovery.seed != null) {
            item { ShelfTitle(tr("Because you listened…"), discovery.seed?.title) }
            if (discovery.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) }
            if (discovery.recommendations.isNotEmpty()) item { LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(discovery.recommendations, key = { it.track.url }) { result -> MusicCard(result.track, player.connected, result.track.url in favoriteIds, { vm.playback.playSingle(result.track) }, { vm.favorite(result.track) }, result.explanation) }
            } }
            discovery.notice?.let { notice -> item { Text(notice, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall) } }
            item { TextButton(onClick = { vm.refreshHome(seed, true) }, modifier = Modifier.padding(horizontal = 16.dp), enabled = !discovery.loading) { Text(tr("Refresh discoveries")) } }
        }
        if (favorites.isNotEmpty()) {
            item { ShelfTitle(tr("Your favorites"), tr("A familiar place to start a new radio")) }
            item { LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(favorites.take(15), key = { it.url }) { row -> MusicCard(row.track(), player.connected, true, { vm.playback.playSingle(row.track()) }, { vm.favorite(row.track()) }) }
            } }
        }
        if (albums.isNotEmpty()) {
            item { ShelfTitle(tr("Albums in your listening"), tr("Only unambiguous MusicBrainz matches")) }
            item { LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(albums) { rows -> val album = metadata.getValue(rows.first().url)
                    Column(Modifier.width(168.dp).clickable { vm.collection.local(album.album!!.value, rows.map { it.track() }) }) {
                        Artwork(rows.first().track(), Modifier.size(168.dp), large = true)
                        Text(album.album!!.value, Modifier.padding(top = 8.dp), maxLines = 2, style = MaterialTheme.typography.titleSmall)
                        Text(tr("%1\$s · %2\$s saved recordings", album.artist?.value.orEmpty(), rows.size), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } }
        }
        if (artists.isNotEmpty()) {
            item { ShelfTitle(tr("Artists you like"), tr("MusicBrainz-matched credits")) }
            item { LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(artists) { rows -> val artist = metadata[rows.first().url]?.artist?.value.orEmpty(); ElevatedCard(onClick = { onSearch(artist) }, modifier = Modifier.width(164.dp)) {
                    Column(Modifier.padding(16.dp)) { Icon(Icons.Rounded.Person, null); Text(artist, maxLines = 2); Text(tr("%1\$s plays", rows.sumOf { it.playCount }), style = MaterialTheme.typography.bodySmall) }
                } }
            } }
        } else if (channels.isNotEmpty()) {
            item { ShelfTitle(tr("Credits you return to"), tr("Source names may be upload channels, not artists")) }
            item { LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(channels) { rows -> SuggestionChip(onClick = { onSearch(rows.first().artist) }, label = { Text(rows.first().artist, maxLines = 1) }) }
            } }
        }
        val discoveries = discovery.recommendations.filter { result -> library.none { it.url == result.track.url } }
        if (discoveries.isNotEmpty()) {
            item { ShelfTitle(tr("More to explore"), tr("Beyond your recent tracks and favorites")) }
            item { LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(discoveries, key = { it.track.url }) { result -> MusicCard(result.track, player.connected, false, { vm.playback.playSingle(result.track) }, { vm.favorite(result.track) }, result.explanation) }
            } }
        }
        item { ShelfTitle(tr("Discover music"), if (options.interests.isEmpty()) tr("Choose a starting point or add your own interests") else tr("Searches based on interests you chose")) }
        item { LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options.interests.ifEmpty { listOf(tr("Rock en valencià"), tr("Indie pop"), tr("Jazz"), tr("Electronic music"), tr("Acoustic music")) }) { query -> SuggestionChip(onClick = { onSearch(query) }, label = { Text(query) }) }
        } }
        if (history.isEmpty() && favorites.isEmpty()) item {
            Column(Modifier.padding(24.dp)) {
                Text(tr("Start with music you love"), style = MaterialTheme.typography.titleLarge)
                Text(tr("Search for a song, play it or save a favorite. Sonora builds discoveries from real listening and public sources."), Modifier.padding(vertical = 8.dp))
                Button(onClick = { onSearch("") }) { Icon(Icons.Rounded.Search, null); Text(tr("Find music")) }
            }
        }
        if (playlists.isNotEmpty()) {
            item { ShelfTitle(tr("Your playlists"), tr("Created and imported collections")) }
            item { LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(playlists, key = { it.playlist.id }) { row -> ElevatedCard(onClick = { vm.selectPlaylist(row.playlist.id); onLibrary() }, modifier = Modifier.width(172.dp)) {
                    Column(Modifier.padding(20.dp)) { Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(44.dp)); Spacer(Modifier.height(18.dp)); Text(row.playlist.name, maxLines = 2); Text(tr("%1\$s tracks", row.trackCount), style = MaterialTheme.typography.bodySmall) }
                } }
            } }
        }
    }
}

@UnstableApi
@Composable fun DiscoverySearch(vm: MusicViewModel, state: SearchState, favorites: Set<String>, connected: Boolean, onSearch: (String) -> Unit) {
    val history by vm.searchHistory.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    Column {
        Text(tr("Search"), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        OutlinedTextField(state.query, vm.search::edit, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), singleLine = true,
            placeholder = { Text(tr("Songs, artists, albums…")) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { IconButton(onClick = { keyboard?.hide(); onSearch(state.query) }, enabled = state.query.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, tr("Search music")) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSearch(state.query) }))
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CatalogKind.entries) { kind -> FilterChip(state.kind == kind, { vm.search.select(kind) }, label = { Text(kind.label) }) }
        }
        if (state.loading || state.loadingMore) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
            val suggestions = (history.map { it.query }.filter { state.query.isBlank() || MusicText.normalize(it).startsWith(MusicText.normalize(state.query)) } + state.suggestions).distinct().take(6)
            if (suggestions.isNotEmpty()) item { LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions) { suggestion -> SuggestionChip(onClick = { keyboard?.hide(); onSearch(suggestion) }, label = { Text(suggestion) }) }
            } }
            state.notice?.let { item { Text(it, Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall) } }
            state.error?.let { item { ErrorBanner(it, { if (state.next != null && state.searched) vm.search.loadMore() else onSearch(state.query) }) } }
            if (state.kind == CatalogKind.SONGS && state.ranked.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("%1\$s results", state.ranked.size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    FilterChip(state.allVersions, { vm.search.showVersions(!state.allVersions) }, label = { Text(if (state.hiddenVersions > 0) tr("All versions (+%1\$s)", state.hiddenVersions) else tr("All versions")) })
                }
            }
            if (!state.searched && !state.loading && state.query.isBlank()) item { Text(tr("Search your library and public music sources. Artists from the music catalog and verified local credits are separate from YouTube channels. Tap an artist to find songs."), Modifier.padding(24.dp)) }
            if (state.searched && !state.loading && state.ranked.isEmpty()) item { Text(if (state.kind == CatalogKind.ARTISTS) tr("No artists found. Try another name. Artists are searched in the music catalog and your verified local credits.") else tr("No results yet. Try another query or category."), Modifier.padding(24.dp)) }
            items(state.ranked, key = { it.entry.url }) { result ->
                val entry = result.entry; val track = entry.track
                if (track != null) Column {
                    TrackRow(track, track.url in favorites, connected, { vm.playback.playSingle(track) }, { vm.favorite(track) }, { vm.enqueue(track) })
                    Text((result.reasons.take(2) + result.versions.map { org.sonora.music.i18n.versionLabel(it) }).distinct().joinToString(" · "), Modifier.padding(start = 24.dp, bottom = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                } else ListItem(headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text("${entry.kind.label} · ${entry.credit}") },
                    leadingContent = { Artwork(Track(entry.url, entry.title, entry.credit, entry.artwork, 0), Modifier.size(64.dp)) },
                    modifier = Modifier.clickable {
                        if (entry.kind in setOf(CatalogKind.ARTISTS, CatalogKind.CHANNELS) || entry.url.startsWith("https://musicbrainz.org/")) { keyboard?.hide(); vm.search.songsBy(entry.title) }
                        else vm.collection.open(entry)
                    })
            }
            if (state.next != null) item { TextButton(onClick = vm.search::loadMore, enabled = !state.loadingMore, modifier = Modifier.fillMaxWidth()) { Text(tr("Load more results")) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable fun DiscoveryCollection(vm: MusicViewModel, state: CollectionState, favoriteIds: Set<String>, connected: Boolean) {
    ModalBottomSheet(onDismissRequest = vm.collection::close) {
        Column(Modifier.fillMaxHeight(.9f)) {
            Text(state.entry?.title.orEmpty(), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall)
            Text(tr("%1\$s loaded tracks", state.tracks.size), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall)
            FlowRow(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
                Button(onClick = { vm.playback.play(state.tracks, 0) }, enabled = connected && state.tracks.isNotEmpty()) { Text(tr("Play loaded tracks")) }
                IconButton(onClick = { vm.playback.shuffle(state.tracks) }, enabled = connected && state.tracks.isNotEmpty()) {
                    Icon(Icons.Rounded.Shuffle, tr("Shuffle loaded tracks"))
                }
                TextButton(onClick = { vm.saveCollection() }, enabled = state.tracks.isNotEmpty()) { Text(tr("Save loaded")) }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { ErrorBanner(it, vm.collection::load) }
            LazyColumn {
                items(state.tracks, key = { it.url }) { track -> TrackRow(track, track.url in favoriteIds, connected,
                    { vm.playback.play(state.tracks, state.tracks.indexOf(track)) }, { vm.favorite(track) }, { vm.enqueue(track) }) }
                if (state.next != null) item { TextButton(onClick = vm.collection::load, enabled = !state.loading) { Text(tr("Load more tracks")) } }
            }
        }
    }
}

@UnstableApi
@Composable fun DiscoverySettingsDialog(vm: MusicViewModel, onDismiss: () -> Unit) {
    val options by vm.discoveryOptions.collectAsStateWithLifecycle()
    var interests by rememberSaveable { mutableStateOf(options.interests.joinToString(", ")) }
    var confirmReset by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("Discovery settings")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            fun update(value: DiscoveryOptions) = vm.discoveryOptions(value)
            SettingToggle(tr("Autoplay"), tr("Single songs start a radio. Manual playlists and edited queues stay yours."), options.autoplay) { update(options.copy(autoplay = it)) }
            SettingToggle(tr("Personalize on this device"), tr("Use favorites, listening and gentle skip signals. Listening history is never uploaded as a profile."), options.personalized) { update(options.copy(personalized = it)) }
            SettingToggle(tr("MusicBrainz & ListenBrainz"), tr("Optional: send selected titles/credits and artist IDs for metadata and similar artists. No account. Community tags can be incomplete."), options.enrichment) { update(options.copy(enrichment = it)) }
            SettingToggle(tr("Online search suggestions"), tr("Send partial queries after a short pause. Local search history is always available."), options.onlineSuggestions) { update(options.copy(onlineSuggestions = it)) }
            OutlinedTextField(interests, { interests = it.take(500) }, label = { Text(tr("Your interests, separated by commas")) }, placeholder = { Text(tr("Rock valencià, soul, jazz…")) }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { update(options.copy(interests = interests.split(','))) }) { Text(tr("Save interests")) }
            TextButton(onClick = { vm.clearSearches() }) { Text(tr("Clear search history")) }
            TextButton(onClick = { confirmReset = true }) { Text(tr("Reset learned preferences")) }
            Text(tr("MusicBrainz core data: CC0. Community tags: CC BY-NC-SA 3.0. Similar artists: ListenBrainz public dataset. No genres, language or albums are guessed from channel names."), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri("https://github.com/guardiola98/sonora-android/blob/main/docs/DISCOVERY.md") }) { Text(tr("Algorithm, sources & privacy")) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Done")) } })
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text(tr("Reset preferences?")) },
        text = { Text(tr("Clear learned signals and hidden tracks. Favorites, playlists, podcasts and listening history are kept. Future learning starts from now; your explicit favorites still count.")) },
        confirmButton = { TextButton(onClick = { vm.resetPreferences(); confirmReset = false }) { Text(tr("Reset")) } }, dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(tr("Cancel")) } })
}
@Composable private fun SettingToggle(title: String, text: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(text, style = MaterialTheme.typography.bodySmall) }
        Switch(checked, changed, Modifier.semantics { contentDescription = title })
    }
}
@Composable fun RecommendationDetails(track: Track, recommendation: Recommendation?, metadata: MusicMetadata?, automaticReason: String?, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(tr("Why this music?")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(track.title, style = MaterialTheme.typography.titleMedium)
            Text(recommendation?.explanation ?: automaticReason ?: tr("You chose this track. No recommendation score is available."), Modifier.padding(vertical = 12.dp))
            recommendation?.let { result -> Text(tr("Candidate source: %1\$s", result.origin), style = MaterialTheme.typography.bodySmall)
                result.reasons.forEach { Text(tr("%1\$s: %2\$s%% · weight %3\$s", it.label, (it.value * 100).toInt(), String.format(org.sonora.music.i18n.AppLanguage.locale, "%.1f", it.weight)), style = MaterialTheme.typography.bodySmall) }
            }
            val facts = listOfNotNull(metadata?.artist, metadata?.album, metadata?.year) + metadata?.genres.orEmpty()
            if (facts.isEmpty()) Text(tr("Source credit / channel: %1\$s. Artist, album, genre and language are not verified.", track.artist), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
            facts.forEach { Text(tr("%1\$s (%2\$s)\n%3\$s\nConfidence %4\$s%% · %5\$s · %6\$s", it.value, org.sonora.music.i18n.evidenceScope(it.scope), it.source, (it.confidence * 100).toInt(), java.text.DateFormat.getDateInstance(java.text.DateFormat.DEFAULT, org.sonora.music.i18n.AppLanguage.locale).format(java.util.Date(it.fetchedAt)), it.license), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = close) { Text(tr("Done")) } })
}
