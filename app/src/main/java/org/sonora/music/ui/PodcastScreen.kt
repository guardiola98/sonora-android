package org.sonora.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import org.sonora.music.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@UnstableApi
@Composable fun PodcastScreen(vm: MusicViewModel, connected: Boolean, favorites: Set<String>) {
    val presenter = vm.podcasts
    val state by presenter.state.collectAsStateWithLifecycle()
    val subscriptions by presenter.subscriptions.collectAsStateWithLifecycle()
    val progress by presenter.progress.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(state.query) }
    var rssDialog by rememberSaveable { mutableStateOf(false) }
    var rss by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(state.show != null) { presenter.close() }
    val show = state.show
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (show == null) {
            item {
                Text("Stories worth your time.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("PODCASTS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp))
                OutlinedTextField(query, { query = it.take(200) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search podcasts") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); presenter.search(query) }),
                    trailingIcon = { IconButton(onClick = { keyboard?.hide(); presenter.search(query) }, enabled = query.trim().length >= 2 && !state.searching) { Icon(Icons.Rounded.Search, "Find podcasts") } })
                TextButton(onClick = { rssDialog = true }) { Icon(Icons.Rounded.RssFeed, null); Spacer(Modifier.width(6.dp)); Text("Add RSS feed") }
                Text("Search directory: Apple Podcasts. Audio comes from each publisher's public RSS feed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { Text("Your subscriptions", style = MaterialTheme.typography.titleLarge) }
            if (subscriptions.isEmpty()) item { EmptyState(Icons.Rounded.Podcasts, "Follow a new voice", "Find a podcast or add an RSS feed, then subscribe to keep it here.") }
            else item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(subscriptions, key = { it.feedUrl }) { saved ->
                        ElevatedCard(onClick = { presenter.open(saved.feedUrl) }, modifier = Modifier.width(156.dp)) {
                            PodcastArtwork(saved.artwork, Modifier.fillMaxWidth().aspectRatio(1f))
                            Text(saved.title, Modifier.padding(12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
            if (state.searching) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.searchError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (state.searched) item { Text("Search results", style = MaterialTheme.typography.titleLarge) }
            if (state.searched && !state.searching && state.searchError == null && state.results.isEmpty()) item { Text("No podcasts found. Try another name or add the publisher's RSS feed.") }
            items(state.results, key = { it.feedUrl }) { result ->
                OutlinedCard(onClick = { presenter.open(result.feedUrl) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Podcasts, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(result.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(result.author, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Rounded.ChevronRight, "Open ${result.title}")
                    }
                }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = presenter::close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to podcasts") }
                    Text("Podcast", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { presenter.open(show.feedUrl, refresh = true) }, enabled = !state.loading) { Icon(Icons.Rounded.Refresh, "Refresh episodes") }
                }
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    PodcastArtwork(show.artwork, Modifier.size(112.dp))
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(show.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Text(show.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (show.description.isNotBlank()) ExpandableDescription(show.description)
                Button(onClick = presenter::subscribe, enabled = !state.loading && !state.saving && (show.subscribed || show.refreshedAt > 0)) {
                    Icon(if (show.subscribed) Icons.Rounded.Check else Icons.Rounded.Add, null)
                    Text(if (show.subscribed) "Subscribed · Unsubscribe" else "Subscribe")
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("${state.episodes.size} episodes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
            }
            if (!state.loading && state.error == null && state.episodes.isEmpty()) item { Text("This feed has no supported HTTPS audio episodes.") }
            items(state.episodes, key = { it.reference }) { episode ->
                val saved = progress[episode.reference]
                val track = episode.track(show)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PodcastArtwork(track.artwork, Modifier.size(56.dp))
                            Text(episode.title, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Text(listOfNotNull(episode.publishedAt.takeIf { it > 0 }?.let { DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(it)) },
                            episode.durationSeconds.takeIf { it > 0 }?.let(::formatDuration), if (saved?.completed == true) "Played" else saved?.positionMs?.takeIf { it > 0 }?.let { "At ${formatDuration(it / 1000)}" }).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        if (episode.description.isNotBlank()) ExpandableDescription(episode.description)
                        if (saved != null && saved.durationMs > 0) LinearProgressIndicator(progress = { (saved.positionMs.toFloat() / saved.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(onClick = { presenter.play(episode) }, enabled = connected, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.PlayArrow, null); Text(if ((saved?.resumeMs ?: 0) > 0) "Resume" else "Play episode")
                            }
                            IconButton(onClick = { vm.enqueue(track) }, enabled = connected) { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Queue ${episode.title}") }
                            IconButton(onClick = { vm.favorite(track) }) { Icon(if (track.url in favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorite ${episode.title}") }
                        }
                        TextButton(onClick = { presenter.markPlayed(episode) }) { Text(if (saved?.completed == true) "Mark unplayed" else "Mark played") }
                    }
                }
            }
            if (state.episodes.size >= 500) item { Text("Showing up to 500 playable episodes from this RSS feed.", style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (rssDialog) AlertDialog(onDismissRequest = { rssDialog = false }, title = { Text("Add a podcast RSS feed") }, text = {
        Column {
            Text("Paste the publisher's public HTTPS RSS URL. Private or paid feeds requiring login are not supported.")
            OutlinedTextField(rss, { rss = it.take(4096) }, label = { Text("https://…/feed.xml") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { rssDialog = false; keyboard?.hide(); presenter.open(rss) }, enabled = PodcastLinks.https(rss) != null) { Text("Open feed") } },
        dismissButton = { TextButton(onClick = { rssDialog = false }) { Text("Cancel") } })
}
@Composable private fun PodcastArtwork(url: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Podcasts, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        if (url.isNotBlank()) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
@Composable private fun ExpandableDescription(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    Text(text, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    if (text.length > 140) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(if (expanded) "Show less" else "Read more") }
}
