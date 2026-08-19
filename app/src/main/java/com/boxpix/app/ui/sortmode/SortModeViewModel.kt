package com.boxpix.app.ui.sortmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.prefs.PinnedDestination
import com.boxpix.app.data.prefs.UiPrefsStore
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.data.tags.TagRepository
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.ui.explorer.ExplorerViewModel.FolderRef
import com.boxpix.app.ui.viewer.MediaRef
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Screen 06 — one photo at a time, thumb-reachable pinned destinations + quick tags. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SortModeViewModel @Inject constructor(
    session: SortSession,
    private val provider: StorageProvider,
    private val trashRepository: TrashRepository,
    private val tagRepository: TagRepository,
    private val uiPrefs: UiPrefsStore,
    private val env: StorageEnv,
) : ViewModel() {

    sealed interface LastAction {
        data class Moved(val item: MediaRef, val fromParentB64: String, val newPathB64: String) : LastAction
        data class Skipped(val item: MediaRef) : LastAction
    }

    data class UiState(
        val folder: FolderRef? = null,
        val queue: List<MediaRef> = emptyList(),
        val index: Int = 0,
        val total: Int = 0,
        val lastAction: LastAction? = null,
        val destinations: List<PinnedDestination> = emptyList(),
        val quickTags: List<String> = emptyList(),
        val confirmation: String? = null,
        val shortcutsOpen: Boolean = false,
        val pickerOpen: Boolean = false,
        val error: FreeboxError? = null,
    ) {
        val current: MediaRef? get() = queue.getOrNull(index)
        val done: Boolean get() = index >= queue.size
        val progress: Float get() = if (total == 0) 0f else (total - queue.size + index).toFloat() / total
    }

    private val _state = MutableStateFlow(
        UiState(folder = session.folder, queue = session.items, total = session.items.size),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    val allTags = tagRepository.tags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun currentTagNamesFlow(pathB64: String) =
        env.useFakeProvider.flatMapLatest { useFake ->
            val pid = if (useFake) TrashRepository.PROVIDER_FAKE else TrashRepository.PROVIDER_FREEBOX
            kotlinx.coroutines.flow.flow {
                emit(tagRepository.keywordsForMedia(pid, pathB64))
                tagRepository.tagIdsFor(pathB64).collect {
                    emit(tagRepository.keywordsForMedia(pid, pathB64))
                }
            }
        }

    init {
        viewModelScope.launch {
            val pid = providerId()
            uiPrefs.pinnedDestinations(pid).collect { dests ->
                _state.update { it.copy(destinations = dests) }
            }
        }
        viewModelScope.launch {
            val pid = providerId()
            uiPrefs.quickTags(pid).collect { tags ->
                _state.update { it.copy(quickTags = tags) }
            }
        }
    }

    // Actions on the current photo

    fun moveTo(destination: PinnedDestination) {
        val item = _state.value.current ?: return
        val fromParentB64 = PathCodec.encode(parentOf(item.displayPath))
        viewModelScope.launch {
            when (val moved = provider.move(listOf(item.pathB64), destination.pathB64)) {
                is FbxResult.Ok -> {
                    val destDisplay = runCatching { PathCodec.decode(destination.pathB64) }.getOrDefault("")
                    val newDisplay = "$destDisplay/${item.name}"
                    val newB64 = PathCodec.encode(newDisplay)
                    tagRepository.remapPath(item.pathB64, newB64, newDisplay)
                    advance(
                        LastAction.Moved(item, fromParentB64, newB64),
                        confirmation = destination.name,
                    )
                }
                is FbxResult.Err -> _state.update { it.copy(error = moved.error) }
            }
        }
    }

    fun skip() {
        val item = _state.value.current ?: return
        advance(LastAction.Skipped(item), confirmation = null)
    }

    fun trashCurrent() {
        val item = _state.value.current ?: return
        viewModelScope.launch {
            when (val trashed = trashRepository.trash(listOf(item.toStorageEntry()))) {
                is FbxResult.Ok -> {
                    // Trash is not undoable here (it is recoverable from the trash screen).
                    _state.update {
                        it.copy(
                            queue = it.queue.filterIndexed { i, _ -> i != it.index },
                            lastAction = null,
                        )
                    }
                }
                is FbxResult.Err -> _state.update { it.copy(error = trashed.error) }
            }
        }
    }

    fun toggleQuickTag(name: String) {
        val item = _state.value.current ?: return
        viewModelScope.launch {
            val tag = tagRepository.createTag(name) ?: return@launch
            val pid = providerId()
            val has = tagRepository.keywordsForMedia(pid, item.pathB64)
                .any { it.equals(name, ignoreCase = true) }
            if (has) tagRepository.removeTag(item, tag.id) else tagRepository.addTag(item, tag.id)
        }
    }

    fun undo() {
        when (val action = _state.value.lastAction) {
            is LastAction.Moved -> viewModelScope.launch {
                val movedBack = provider.move(listOf(action.newPathB64), action.fromParentB64)
                if (movedBack is FbxResult.Ok) {
                    tagRepository.remapPath(action.newPathB64, action.item.pathB64, action.item.displayPath)
                    stepBack(action.item)
                } else if (movedBack is FbxResult.Err) {
                    _state.update { it.copy(error = movedBack.error) }
                }
            }
            is LastAction.Skipped -> stepBack(action.item)
            null -> Unit
        }
    }

    // Shortcuts management

    fun setShortcutsOpen(open: Boolean) = _state.update { it.copy(shortcutsOpen = open) }
    fun setPickerOpen(open: Boolean) = _state.update { it.copy(pickerOpen = open) }

    fun pinDestination(entry: StorageEntry) {
        viewModelScope.launch {
            val current = _state.value.destinations
            if (current.none { it.pathB64 == entry.pathB64 }) {
                uiPrefs.setPinnedDestinations(
                    providerId(),
                    current + PinnedDestination(entry.pathB64, entry.name),
                )
            }
            _state.update { it.copy(pickerOpen = false) }
        }
    }

    fun unpinDestination(destination: PinnedDestination) {
        viewModelScope.launch {
            uiPrefs.setPinnedDestinations(
                providerId(),
                _state.value.destinations.filterNot { it.pathB64 == destination.pathB64 },
            )
        }
    }

    fun moveDestination(destination: PinnedDestination, up: Boolean) {
        viewModelScope.launch {
            val list = _state.value.destinations.toMutableList()
            val i = list.indexOfFirst { it.pathB64 == destination.pathB64 }
            val j = if (up) i - 1 else i + 1
            if (i < 0 || j < 0 || j >= list.size) return@launch
            list[i] = list[j].also { list[j] = list[i] }
            uiPrefs.setPinnedDestinations(providerId(), list)
        }
    }

    fun addQuickTag(name: String) {
        viewModelScope.launch {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return@launch
            tagRepository.createTag(trimmed)
            val current = _state.value.quickTags
            if (current.none { it.equals(trimmed, ignoreCase = true) }) {
                uiPrefs.setQuickTags(providerId(), current + trimmed)
            }
        }
    }

    fun removeQuickTag(name: String) {
        viewModelScope.launch {
            uiPrefs.setQuickTags(providerId(), _state.value.quickTags.filterNot { it == name })
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
    fun dismissConfirmation() = _state.update { it.copy(confirmation = null) }

    // Internals

    private fun advance(action: LastAction, confirmation: String?) {
        _state.update { current ->
            when (action) {
                is LastAction.Moved -> current.copy(
                    queue = current.queue.filterIndexed { i, _ -> i != current.index },
                    lastAction = action,
                    confirmation = confirmation,
                )
                is LastAction.Skipped -> current.copy(
                    index = current.index + 1,
                    lastAction = action,
                    confirmation = null,
                )
            }
        }
    }

    private fun stepBack(item: MediaRef) {
        _state.update { current ->
            when (current.lastAction) {
                is LastAction.Moved -> current.copy(
                    queue = current.queue.toMutableList().apply { add(current.index, item) },
                    lastAction = null,
                    confirmation = null,
                )
                is LastAction.Skipped -> current.copy(
                    index = (current.index - 1).coerceAtLeast(0),
                    lastAction = null,
                    confirmation = null,
                )
                null -> current
            }
        }
    }

    private suspend fun providerId(): String =
        if (env.useFakeProvider.first()) TrashRepository.PROVIDER_FAKE else TrashRepository.PROVIDER_FREEBOX

    private fun parentOf(displayPath: String): String {
        val cut = displayPath.trimEnd('/').substringBeforeLast('/', "")
        return cut.ifEmpty { "/" }
    }

    private fun MediaRef.toStorageEntry() = StorageEntry(
        pathB64 = pathB64,
        displayPath = displayPath,
        name = name,
        isDirectory = false,
        sizeBytes = sizeBytes,
        modifiedEpochSeconds = mtime,
        mimeType = mimeType,
        hidden = false,
        durationSeconds = durationSeconds,
    )
}
