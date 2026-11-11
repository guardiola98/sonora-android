package org.sonora.music.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.sonora.music.data.ImportRepository
import org.sonora.music.domain.*

data class ImportState(
    val preview: ImportPreview? = null, val choices: Map<String, ImportChoice> = emptyMap(),
    val busy: Boolean = false, val committing: Boolean = false, val progress: String = "",
    val enrich: Boolean = true, val error: String? = null, val summary: ImportSummary? = null
)
class ImportPresenter(private val repository: ImportRepository, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(ImportState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun load(loader: suspend () -> ImportPreview) {
        if (mutable.value.busy) return
        mutable.value = ImportState(busy = true, progress = "Reading your library…")
        job = scope.launch {
            try {
                val preview = loader()
                ensureActive()
                // Same-name files represent the same destination; merge before previewing.
                val groups = preview.collections.groupBy { it.key }.values.map { parts ->
                    parts.first().copy(tracks = parts.flatMap { it.tracks }.distinctBy { it.url }, skipped = parts.sumOf { it.skipped }, duplicates = parts.sumOf { it.duplicates })
                }.filter { it.tracks.isNotEmpty() || preview.allowEmptyCollections }
                require(groups.isNotEmpty()) { "No supported tracks found." }
                require(groups.sumOf { it.tracks.size } <= 10_000) { "Import up to 10,000 track entries at a time." }
                mutable.value = ImportState(preview = preview.copy(collections = groups), choices = groups.associate {
                    it.key to ImportChoice(it.key, playlist = !it.favoritesHint, favorites = it.favoritesHint)
                })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ensureActive(); mutable.value = ImportState(error = e.message ?: "Could not read this export.") }
        }
    }
    fun loadPlaylist(url: String) = load { repository.playlist(url) { count -> mutable.update { it.copy(progress = "Reading playlist · $count tracks…") } } }
    fun choice(key: String, playlist: Boolean, favorites: Boolean) {
        if (!mutable.value.busy) mutable.update { it.copy(choices = it.choices + (key to ImportChoice(key, playlist, favorites))) }
    }
    fun enrich(value: Boolean) { if (!mutable.value.busy) mutable.update { it.copy(enrich = value) } }
    fun cancel() {
        if (mutable.value.committing) return
        job?.cancel(); job = null
        mutable.update { it.copy(busy = false, progress = "", error = if (it.preview != null) "Import cancelled. Nothing was saved." else null) }
    }
    fun reset() { if (!mutable.value.busy) mutable.value = ImportState() }
    fun save() {
        val initial = mutable.value
        if (initial.busy) return
        val selected = initial.preview?.collections.orEmpty().mapNotNull { collection ->
            val choice = initial.choices[collection.key] ?: return@mapNotNull null
            if (choice.playlist || choice.favorites) ImportSelection(collection, choice.playlist, choice.favorites) else null
        }
        if (selected.isEmpty()) return
        mutable.update { it.copy(busy = true, error = null, progress = "Preparing import…") }
        job = scope.launch {
            try {
                val (ready, unresolved) = repository.prepare(selected, initial.enrich) { done, total ->
                    mutable.update { it.copy(progress = "Preparing tracks · $done / $total") }
                }
                ensureActive()
                mutable.update { it.copy(committing = true, progress = "Saving your library…") }
                val summary = repository.save(ready, unresolved)
                mutable.value = ImportState(summary = summary)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { ensureActive(); mutable.update { it.copy(busy = false, committing = false, error = "Could not save the import. Your library was not changed. Try again.") } }
        }
    }
}
