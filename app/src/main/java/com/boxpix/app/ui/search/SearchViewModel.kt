package com.boxpix.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpix.app.data.db.SearchDao
import com.boxpix.app.data.db.SearchQueryBuilder
import com.boxpix.app.data.db.TagDao
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.tags.TagRepository
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.ui.explorer.ExplorerViewModel.FolderRef
import com.boxpix.app.ui.viewer.MediaRef
import com.boxpix.app.ui.viewer.ViewerSession
import com.boxpix.app.ui.viewer.toMediaRef
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchDao: SearchDao,
    private val tagDao: TagDao,
    tagRepository: TagRepository,
    private val env: StorageEnv,
    private val rootLocator: RootLocator,
    searchContext: SearchContext,
    private val viewerSession: ViewerSession,
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val selectedTagIds: Set<Long> = emptySet(),
        val selectedTypes: Set<SearchQueryBuilder.TypeFilter> = emptySet(),
        val fromEpochSeconds: Long? = null,
        val toEpochSeconds: Long? = null,
        val folder: FolderRef? = null,
        val results: List<MediaRef> = emptyList(),
        val searched: Boolean = false,
    ) {
        val hasFilters: Boolean
            get() = query.isNotBlank() || selectedTagIds.isNotEmpty() ||
                selectedTypes.isNotEmpty() || fromEpochSeconds != null || folder != null
    }

    val allTags = tagRepository.tags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(UiState(folder = searchContext.folder))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        runSearch()
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value) }
        runSearch(debounceMs = 300)
    }

    fun toggleTag(tagId: Long) {
        _state.update {
            val ids = it.selectedTagIds.toMutableSet()
            if (!ids.remove(tagId)) ids += tagId
            it.copy(selectedTagIds = ids)
        }
        runSearch()
    }

    fun toggleType(type: SearchQueryBuilder.TypeFilter) {
        _state.update {
            val types = it.selectedTypes.toMutableSet()
            if (!types.remove(type)) types += type
            it.copy(selectedTypes = types)
        }
        runSearch()
    }

    fun setDateRange(fromEpochSeconds: Long?, toEpochSeconds: Long?) {
        _state.update { it.copy(fromEpochSeconds = fromEpochSeconds, toEpochSeconds = toEpochSeconds) }
        runSearch()
    }

    fun clearFolder() {
        _state.update { it.copy(folder = null) }
        runSearch()
    }

    fun clearFilters() {
        _state.update {
            it.copy(
                query = "",
                selectedTagIds = emptySet(),
                selectedTypes = emptySet(),
                fromEpochSeconds = null,
                toEpochSeconds = null,
                folder = null,
            )
        }
        runSearch()
    }

    fun stageViewer(item: MediaRef) {
        val results = _state.value.results
        viewerSession.open(results, results.indexOfFirst { it.pathB64 == item.pathB64 })
    }

    private fun runSearch(debounceMs: Long = 0) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val current = _state.value
            val providerId = if (env.useFakeProvider.first()) {
                TrashRepository.PROVIDER_FAKE
            } else {
                TrashRepository.PROVIDER_FREEBOX
            }
            // The index spans every disk ever browsed; a search is always scoped
            // to the current one so a swap never surfaces the other disk's files.
            val rootDisplayPath = rootLocator.rootPathB64()
                ?.let { runCatching { PathCodec.decode(it) }.getOrNull() }
            if (rootDisplayPath == null) {
                _state.update { it.copy(results = emptyList(), searched = true) }
                return@launch
            }
            val tagPaths = current.selectedTagIds.takeIf { it.isNotEmpty() }?.let { ids ->
                tagDao.pathsWithAllTags(providerId, ids.toList(), ids.size)
            }
            val rows = searchDao.search(
                SearchQueryBuilder.build(
                    providerId = providerId,
                    rootDisplayPath = rootDisplayPath,
                    nameContains = current.query.takeIf { it.isNotBlank() },
                    fromEpochSeconds = current.fromEpochSeconds,
                    toEpochSeconds = current.toEpochSeconds,
                    folderPrefix = current.folder?.displayPath,
                    pathsWithAllTags = tagPaths,
                    types = current.selectedTypes,
                ),
            )
            _state.update { it.copy(results = rows.map { row -> row.toMediaRef() }, searched = true) }
        }
    }
}
