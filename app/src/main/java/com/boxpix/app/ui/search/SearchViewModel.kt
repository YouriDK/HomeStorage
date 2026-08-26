package com.boxpix.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpix.app.data.db.SearchDao
import com.boxpix.app.data.db.SearchQueryBuilder
import com.boxpix.app.data.db.TagDao
import com.boxpix.app.data.db.TagWithCount
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.media.MediaTypes
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.tags.TagRepository
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.data.vault.VaultIndexEntry
import com.boxpix.app.data.vault.VaultMetaRepository
import com.boxpix.app.data.vault.VaultPaths
import com.boxpix.app.data.vault.VaultSession
import com.boxpix.app.data.vault.VaultState
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
    private val vaultSession: VaultSession,
    private val vaultMeta: VaultMetaRepository,
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

    /**
     * Room tags plus, while the vault is unlocked, the vault's own tag names
     * (negative pseudo-ids). Locking removes them — and their results.
     */
    val allTags = kotlinx.coroutines.flow.combine(
        tagRepository.tags,
        vaultSession.state,
        vaultMeta.tags,
    ) { roomTags, vaultState, _ ->
        if (vaultState != VaultState.Unlocked) return@combine roomTags
        val roomNames = roomTags.map { it.name.lowercase() }.toSet()
        roomTags + vaultMeta.tagCounts()
            .filter { (name, _) -> name.lowercase() !in roomNames }
            .map { (name, count) ->
                TagWithCount(
                    id = VaultMetaRepository.syntheticTagId(name),
                    name = name,
                    pinned = false,
                    isSystem = false,
                    usageCount = count,
                )
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(UiState(folder = searchContext.folder))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        runSearch()
        viewModelScope.launch {
            // A lock while results are on screen pulls the vault rows out.
            vaultSession.state.collect { if (_state.value.searched) runSearch() }
        }
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
            // Negative ids are vault tag names; Room rows can never carry one,
            // so any vault-only tag empties the Room side of the results.
            val roomTagIds = current.selectedTagIds.filter { it > 0 }
            val vaultOnlySelected = current.selectedTagIds.any { it < 0 }
            val selectedTagNames = allTags.value
                .filter { it.id in current.selectedTagIds }
                .map { it.name }
                .toSet()

            val rows = if (vaultOnlySelected) {
                emptyList()
            } else {
                val tagPaths = roomTagIds.takeIf { it.isNotEmpty() }?.let { ids ->
                    tagDao.pathsWithAllTags(providerId, ids, ids.size)
                }
                searchDao.search(
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
            }
            val merged = rows.map { row -> row.toMediaRef() } + vaultResults(current, selectedTagNames)
            _state.update { it.copy(results = merged, searched = true) }
        }
    }

    /** Vault side of the search — in-memory index, only while unlocked. */
    private fun vaultResults(current: UiState, tagNames: Set<String>): List<MediaRef> {
        if (vaultSession.state.value != VaultState.Unlocked) return emptyList()
        val mount = vaultSession.mountDisplayPath ?: return emptyList()
        // Folder scope vs the vault: an ancestor of the mount (disk root)
        // includes the whole vault; a scope inside the vault narrows it; any
        // other folder excludes it.
        val folderScope = current.folder?.displayPath
        val vaultPrefix = when {
            folderScope == null -> null
            mount == folderScope || mount.startsWith("$folderScope/") -> null
            else -> VaultPaths.vaultRelative(folderScope, mount) ?: return emptyList()
        }
        return vaultMeta.search(
            nameContains = current.query.takeIf { it.isNotBlank() },
            types = current.selectedTypes,
            fromEpochSeconds = current.fromEpochSeconds,
            toEpochSeconds = current.toEpochSeconds,
            folderPrefix = vaultPrefix?.takeIf { it != "/" },
            tagNames = tagNames,
        ).map { it.toMediaRef(mount) }
    }

    private fun VaultIndexEntry.toMediaRef(mount: String): MediaRef {
        val display = "$mount$path"
        return MediaRef(
            pathB64 = PathCodec.encode(display),
            displayPath = display,
            name = name,
            mtime = mtime,
            sizeBytes = sizeBytes,
            mimeType = MediaTypes.mimeTypeFor(name),
            takenAtEpochSeconds = takenAtEpochSeconds,
            isVideo = isVideo,
            durationSeconds = durationSeconds,
        )
    }
}
