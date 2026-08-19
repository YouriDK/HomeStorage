package com.boxpix.app.ui.explorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.prefs.SettingsStore
import com.boxpix.app.data.prefs.SortOrder
import com.boxpix.app.data.prefs.UiPrefsStore
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.data.db.TagWithCount
import com.boxpix.app.data.storage.ProtectionRepository
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.data.tags.TagRepository
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.ui.search.SearchContext
import com.boxpix.app.ui.sortmode.SortSession
import com.boxpix.app.ui.viewer.ViewerSession
import com.boxpix.app.ui.viewer.toMediaRef
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
class ExplorerViewModel @Inject constructor(
    private val provider: StorageProvider,
    private val trashRepository: TrashRepository,
    private val settings: SettingsStore,
    private val env: StorageEnv,
    private val uiPrefs: UiPrefsStore,
    private val viewerSession: ViewerSession,
    private val protection: ProtectionRepository,
    private val tagRepository: TagRepository,
    private val sortSession: SortSession,
    private val searchContext: SearchContext,
) : ViewModel() {

    data class FolderRef(val pathB64: String, val displayPath: String, val name: String)

    data class AlbumUi(val entry: StorageEntry, val mediaCount: Int, val cover: StorageEntry?)

    data class MoveState(
        val visible: Boolean = false,
        val stack: List<FolderRef> = emptyList(),
        val folders: List<StorageEntry> = emptyList(),
        val loading: Boolean = false,
    ) {
        val current: FolderRef? get() = stack.lastOrNull()
    }

    data class UiState(
        val root: FolderRef? = null,
        val stack: List<FolderRef> = emptyList(),
        val folders: List<StorageEntry> = emptyList(),
        val media: List<StorageEntry> = emptyList(),
        val albums: List<AlbumUi> = emptyList(),
        val loading: Boolean = true,
        val initialLoad: Boolean = true,
        val wakingDisk: Boolean = false,
        val error: FreeboxError? = null,
        val sort: SortOrder = SortOrder.NAME,
        val albumColumns: Int = UiPrefsStore.DEFAULT_COLUMNS,
        val selection: Set<String> = emptySet(),
        val isFake: Boolean = false,
        val move: MoveState = MoveState(),
        val protectedPaths: List<String> = emptyList(),
        val favoritePaths: List<String> = emptyList(),
    ) {
        val current: FolderRef? get() = stack.lastOrNull() ?: root
        val depth: Int get() = stack.size
        val selectionMode: Boolean get() = selection.isNotEmpty()
        val photoColumns: Int get() = (albumColumns + 1).coerceAtMost(UiPrefsStore.MAX_COLUMNS + 1)
        val breadcrumb: String
            get() = (listOfNotNull(root?.name) + stack.map { it.name }).joinToString(" › ")
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var wakeHintJob: Job? = null

    init {
        viewModelScope.launch {
            val isFake = env.useFakeProvider.first()
            val root = resolveRoot(isFake)
            _state.update { it.copy(root = root, isFake = isFake) }
            if (root != null) load(initial = true)
        }
        viewModelScope.launch {
            uiPrefs.gridColumns.collect { columns ->
                _state.update { it.copy(albumColumns = columns) }
            }
        }
        viewModelScope.launch {
            protection.protectedFolders.collect { folders ->
                _state.update { it.copy(protectedPaths = folders.map { f -> f.displayPath }) }
            }
        }
        viewModelScope.launch {
            tagRepository.favoritePaths.collect { paths ->
                _state.update { it.copy(favoritePaths = paths) }
            }
        }
    }

    val allTags = tagRepository.tags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val exportConflict = tagRepository.exportConflict

    fun confirmConflictedExport() = tagRepository.confirmConflictedExport()
    fun dismissConflict() = tagRepository.dismissConflict()

    /** Applies a tag to every selected media (folders skipped). */
    fun applyTagToSelection(tag: TagWithCount) {
        val medias = selectedEntries().filterNot { it.isDirectory }
        viewModelScope.launch {
            medias.forEach { tagRepository.addTag(it.toMediaRef(), tag.id) }
        }
    }

    fun createTagAndApply(name: String) {
        viewModelScope.launch {
            tagRepository.createTag(name)?.let { tag ->
                selectedEntries().filterNot { it.isDirectory }
                    .forEach { tagRepository.addTag(it.toMediaRef(), tag.id) }
            }
        }
    }

    /** True when the current folder was staged for sort mode. */
    fun stageSortMode(): Boolean {
        val folder = _state.value.current ?: return false
        val medias = _state.value.media.map { it.toMediaRef() }
        if (medias.isEmpty()) return false
        sortSession.open(folder, medias)
        return true
    }

    fun stageSearch() {
        searchContext.folder = _state.value.current
    }

    // Navigation

    fun openFolder(entry: StorageEntry) {
        if (_state.value.selectionMode) {
            toggleSelection(entry.pathB64)
            return
        }
        _state.update {
            it.copy(stack = it.stack + FolderRef(entry.pathB64, entry.displayPath, entry.name))
        }
        viewModelScope.launch { load(initial = true) }
    }

    /** Returns true when the back press was consumed (selection or folder pop). */
    fun onBack(): Boolean {
        val current = _state.value
        return when {
            current.selectionMode -> {
                clearSelection()
                true
            }
            current.stack.isNotEmpty() -> {
                _state.update { it.copy(stack = it.stack.dropLast(1)) }
                viewModelScope.launch { load(initial = true) }
                true
            }
            else -> false
        }
    }

    fun reload() {
        viewModelScope.launch { load(initial = false) }
    }

    // Selection

    fun toggleSelection(pathB64: String) {
        _state.update {
            val selection = it.selection.toMutableSet()
            if (!selection.remove(pathB64)) selection += pathB64
            it.copy(selection = selection)
        }
    }

    fun startSelection(pathB64: String) {
        _state.update { it.copy(selection = it.selection + pathB64) }
    }

    fun selectAll() {
        _state.update { current ->
            current.copy(
                selection = (current.folders + current.media).map { it.pathB64 }.toSet(),
            )
        }
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    // Operations

    fun createFolder(name: String) {
        val parent = _state.value.current ?: return
        viewModelScope.launch {
            when (val made = provider.mkdir(parent.pathB64, name.trim())) {
                is FbxResult.Ok -> load(initial = false)
                is FbxResult.Err -> _state.update { it.copy(error = made.error) }
            }
        }
    }

    fun renameSelected(newName: String) {
        val target = _state.value.selection.singleOrNull() ?: return
        if (guardViolation(selectedEntries())) return
        viewModelScope.launch {
            when (val renamed = provider.rename(target, newName.trim())) {
                is FbxResult.Ok -> {
                    clearSelection()
                    load(initial = false)
                }
                is FbxResult.Err -> _state.update { it.copy(error = renamed.error) }
            }
        }
    }

    /** The single selected folder, when the selection is exactly one folder. */
    fun singleSelectedFolder(): StorageEntry? {
        val path = _state.value.selection.singleOrNull() ?: return null
        return _state.value.folders.firstOrNull { it.pathB64 == path }
    }

    fun toggleProtection() {
        val folder = singleSelectedFolder() ?: return
        viewModelScope.launch {
            if (folder.displayPath in _state.value.protectedPaths) {
                protection.unprotect(folder.pathB64)
            } else {
                protection.protect(folder)
            }
            clearSelection()
        }
    }

    private fun guardViolation(entries: List<StorageEntry>): Boolean {
        val protectedPaths = _state.value.protectedPaths
        val violation = entries.any { protection.isGuarded(it.displayPath, protectedPaths) }
        if (violation) {
            _state.update { it.copy(error = FreeboxError.Api(ERROR_PROTECTED)) }
        }
        return violation
    }

    fun trashSelected() {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        if (guardViolation(entries)) return
        viewModelScope.launch {
            when (val trashed = trashRepository.trash(entries)) {
                is FbxResult.Ok -> {
                    clearSelection()
                    load(initial = false)
                }
                is FbxResult.Err -> _state.update { it.copy(error = trashed.error) }
            }
        }
    }

    fun setSort(order: SortOrder) {
        val folder = _state.value.current ?: return
        viewModelScope.launch {
            uiPrefs.setSortFor(folder.pathB64, order)
            _state.update { it.copy(sort = order, media = it.media.sortedWith(comparator(order))) }
        }
    }

    fun adjustColumns(delta: Int) {
        viewModelScope.launch {
            uiPrefs.setGridColumns(_state.value.albumColumns + delta)
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Stages the viewer on the current folder's medias, starting at the tapped one. */
    fun stageViewer(entry: StorageEntry) {
        val medias = _state.value.media
        viewerSession.open(
            medias.map { it.toMediaRef() },
            medias.indexOfFirst { it.pathB64 == entry.pathB64 },
        )
    }

    // Move sheet

    fun openMoveSheet() {
        val root = _state.value.root ?: return
        _state.update { it.copy(move = MoveState(visible = true, stack = listOf(root))) }
        browseMoveTarget()
    }

    fun closeMoveSheet() = _state.update { it.copy(move = MoveState()) }

    fun moveBrowseInto(entry: StorageEntry) {
        _state.update {
            val ref = FolderRef(entry.pathB64, entry.displayPath, entry.name)
            it.copy(move = it.move.copy(stack = it.move.stack + ref))
        }
        browseMoveTarget()
    }

    fun moveBrowseUp() {
        _state.update {
            if (it.move.stack.size <= 1) it
            else it.copy(move = it.move.copy(stack = it.move.stack.dropLast(1)))
        }
        browseMoveTarget()
    }

    fun confirmMove() {
        val dest = _state.value.move.current ?: return
        val selection = _state.value.selection.toList()
        if (selection.isEmpty()) return
        if (guardViolation(selectedEntries())) return
        viewModelScope.launch {
            when (val moved = provider.move(selection, dest.pathB64)) {
                is FbxResult.Ok -> {
                    closeMoveSheet()
                    clearSelection()
                    load(initial = false)
                }
                is FbxResult.Err -> _state.update { it.copy(error = moved.error) }
            }
        }
    }

    private fun browseMoveTarget() {
        val target = _state.value.move.current ?: return
        viewModelScope.launch {
            _state.update { it.copy(move = it.move.copy(loading = true)) }
            val selection = _state.value.selection
            val folders = provider.list(target.pathB64, onlyFolders = true)
                .getOrNull()
                .orEmpty()
                .filterNot { it.pathB64 in selection }
                .sortedBy { it.name.lowercase() }
            _state.update { it.copy(move = it.move.copy(folders = folders, loading = false)) }
        }
    }

    // Loading

    private suspend fun load(initial: Boolean) {
        val current = _state.value.current ?: return
        _state.update { it.copy(loading = true, initialLoad = initial, error = null, wakingDisk = false) }
        wakeHintJob?.cancel()
        wakeHintJob = viewModelScope.launch {
            delay(WAKE_HINT_DELAY_MS)
            _state.update { it.copy(wakingDisk = true) }
        }

        val sort = uiPrefs.sortFor(current.pathB64).first()
        val listed = provider.list(current.pathB64)
        wakeHintJob?.cancel()

        when (listed) {
            is FbxResult.Ok -> {
                val folders = listed.value.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                val media = listed.value.filterNot { it.isDirectory }.sortedWith(comparator(sort))
                val albums = loadAlbums(folders)
                _state.update {
                    it.copy(
                        folders = folders,
                        media = media,
                        albums = albums,
                        sort = sort,
                        loading = false,
                        initialLoad = false,
                        wakingDisk = false,
                    )
                }
            }
            is FbxResult.Err -> _state.update {
                it.copy(loading = false, initialLoad = false, wakingDisk = false, error = listed.error)
            }
        }
    }

    /** Counts and covers need one listing per subfolder; cheap on the fake, M3 indexes it. */
    private suspend fun loadAlbums(folders: List<StorageEntry>): List<AlbumUi> = coroutineScope {
        folders.map { folder ->
            async {
                val children = provider.list(folder.pathB64).getOrNull().orEmpty()
                val medias = children.filterNot { it.isDirectory }.sortedBy { it.name.lowercase() }
                AlbumUi(folder, medias.size, medias.firstOrNull { !it.isVideo() } ?: medias.firstOrNull())
            }
        }.awaitAll()
    }

    private fun selectedEntries(): List<StorageEntry> {
        val current = _state.value
        val byPath = (current.folders + current.media).associateBy { it.pathB64 }
        return current.selection.mapNotNull { byPath[it] }
    }

    private suspend fun resolveRoot(isFake: Boolean): FolderRef? {
        if (isFake) {
            return FolderRef(PathCodec.encode(FAKE_ROOT), FAKE_ROOT, FAKE_ROOT.trimStart('/'))
        }
        val snapshot = settings.current()
        val pathB64 = snapshot.rootPathB64 ?: return null
        val display = snapshot.rootDisplayPath.orEmpty()
        return FolderRef(pathB64, display, display.substringAfterLast('/').ifEmpty { "Root" })
    }

    private fun StorageEntry.isVideo(): Boolean = mimeType?.startsWith("video/") == true

    private fun comparator(order: SortOrder): Comparator<StorageEntry> = when (order) {
        SortOrder.NAME -> compareBy { it.name.lowercase() }
        SortOrder.DATE -> compareByDescending { it.modifiedEpochSeconds }
        SortOrder.SIZE -> compareByDescending { it.sizeBytes }
    }

    companion object {
        const val ERROR_PROTECTED = "protected_folder"
        private const val FAKE_ROOT = "/Photos"
        private const val WAKE_HINT_DELAY_MS = 2_500L
    }
}
