package com.boxpix.app.ui.sortmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.ui.explorer.ExplorerViewModel.FolderRef
import com.boxpix.app.ui.explorer.ExplorerViewModel.MoveState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Folder browsing for "Pin another folder" — same UX as the move sheet. */
@HiltViewModel
class PinPickerViewModel @Inject constructor(
    private val provider: StorageProvider,
    private val rootLocator: RootLocator,
) : ViewModel() {

    private val _move = MutableStateFlow(MoveState())
    val move: StateFlow<MoveState> = _move.asStateFlow()

    fun start() {
        viewModelScope.launch {
            val rootB64 = rootLocator.rootPathB64() ?: return@launch
            val display = runCatching { PathCodec.decode(rootB64) }.getOrDefault("")
            val root = FolderRef(rootB64, display, display.substringAfterLast('/').ifEmpty { "Root" })
            _move.value = MoveState(visible = true, stack = listOf(root))
            browse()
        }
    }

    fun browseInto(entry: StorageEntry) {
        _move.update { it.copy(stack = it.stack + FolderRef(entry.pathB64, entry.displayPath, entry.name)) }
        browse()
    }

    fun browseUp() {
        _move.update { if (it.stack.size <= 1) it else it.copy(stack = it.stack.dropLast(1)) }
        browse()
    }

    fun currentEntry(): StorageEntry? = _move.value.current?.let {
        StorageEntry(
            pathB64 = it.pathB64,
            displayPath = it.displayPath,
            name = it.name,
            isDirectory = true,
            sizeBytes = 0,
            modifiedEpochSeconds = 0,
            mimeType = null,
            hidden = false,
        )
    }

    private fun browse() {
        val target = _move.value.current ?: return
        viewModelScope.launch {
            _move.update { it.copy(loading = true) }
            val folders = provider.list(target.pathB64, onlyFolders = true)
                .getOrNull()
                .orEmpty()
                .sortedBy { it.name.lowercase() }
            _move.update { it.copy(folders = folders, loading = false) }
        }
    }
}
