package com.boxpix.app.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.freebox.auth.FreeboxSessionManager
import com.boxpix.app.data.db.TagWithCount
import com.boxpix.app.data.download.DownloadRequester
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.storage.StorageProvider
import com.boxpix.app.data.tags.TagRepository
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.data.freebox.api.FreeboxApiClient
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.vault.VaultMetaRepository
import com.boxpix.app.data.vault.VaultPaths
import com.boxpix.app.data.vault.VaultSession
import com.boxpix.app.data.vault.VaultState
import com.boxpix.app.ui.explorer.ExplorerViewModel.FolderRef
import com.boxpix.app.ui.explorer.ExplorerViewModel.MoveState
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ViewerViewModel @Inject constructor(
    session: ViewerSession,
    private val provider: StorageProvider,
    private val trashRepository: TrashRepository,
    private val sessions: FreeboxSessionManager,
    private val env: StorageEnv,
    private val rootLocator: RootLocator,
    private val tagRepository: TagRepository,
    private val downloadRequester: DownloadRequester,
    private val vaultSession: VaultSession,
    private val vaultMeta: VaultMetaRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private fun vaultRelative(displayPath: String): String? =
        vaultSession.mountDisplayPath
            ?.takeIf { VaultPaths.isVaultPath(displayPath) }
            ?.let { VaultPaths.vaultRelative(displayPath, it) }

    private val _downloadConfirm = MutableStateFlow<DownloadRequester.Outcome.NeedsConfirmation?>(null)
    val downloadConfirm: StateFlow<DownloadRequester.Outcome.NeedsConfirmation?> = _downloadConfirm.asStateFlow()

    fun saveToDevice(item: MediaRef) {
        viewModelScope.launch {
            when (val outcome = downloadRequester.request(listOf(item))) {
                is DownloadRequester.Outcome.NeedsConfirmation -> _downloadConfirm.value = outcome
                DownloadRequester.Outcome.Enqueued -> Unit
            }
        }
    }

    fun confirmDownload() {
        val pending = _downloadConfirm.value ?: return
        _downloadConfirm.value = null
        viewModelScope.launch { downloadRequester.enqueue(pending.items) }
    }

    fun dismissDownloadConfirm() {
        _downloadConfirm.value = null
    }

    /** Streaming coordinates for ExoPlayer, null when the fake provider is active. */
    data class VideoAccess(val baseUrl: String, val headers: Map<String, String>)

    data class UiState(
        val items: List<MediaRef> = emptyList(),
        val startIndex: Int = 0,
        val chromeVisible: Boolean = true,
        val infoOpen: Boolean = false,
        val move: MoveState = MoveState(),
        val videoAccess: VideoAccess? = null,
        val error: FreeboxError? = null,
    )

    private val _state = MutableStateFlow(
        UiState(items = session.items, startIndex = session.startIndex),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _shareUri = MutableStateFlow<Uri?>(null)
    val shareUri: StateFlow<Uri?> = _shareUri.asStateFlow()

    val allTags = tagRepository.tags
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Vault tag names as picker rows (negative pseudo-ids), for vault media. */
    val vaultTags = kotlinx.coroutines.flow.combine(vaultMeta.tags, vaultSession.state) { _, _ ->
        vaultMeta.tagCounts().map { (name, count) ->
            TagWithCount(
                id = VaultMetaRepository.syntheticTagId(name),
                name = name,
                pinned = false,
                isSystem = false,
                usageCount = count,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoritePaths = kotlinx.coroutines.flow.combine(
        tagRepository.favoritePaths,
        vaultMeta.tags,
        vaultSession.state,
    ) { roomPaths, _, vaultState ->
        val mount = vaultSession.mountDisplayPath
        if (vaultState != VaultState.Unlocked || mount == null) {
            roomPaths
        } else {
            roomPaths + vaultMeta.favoriteRelativePaths().map { PathCodec.encode("$mount$it") }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<String>())

    fun tagIdsFlow(pathB64: String): kotlinx.coroutines.flow.Flow<List<Long>> {
        val display = runCatching { PathCodec.decode(pathB64) }.getOrDefault("")
        val relative = vaultRelative(display)
            ?: return tagRepository.tagIdsFor(pathB64)
        return vaultMeta.tags.map { file ->
            file.files[relative]?.tags.orEmpty().map { VaultMetaRepository.syntheticTagId(it) }
        }
    }

    fun toggleTag(item: MediaRef, tag: TagWithCount, currentlySelected: Boolean) {
        viewModelScope.launch {
            val relative = vaultRelative(item.displayPath)
            when {
                relative != null && currentlySelected -> vaultMeta.removeTag(relative, tag.name)
                relative != null -> vaultMeta.addTag(relative, tag.name)
                currentlySelected -> tagRepository.removeTag(item, tag.id)
                else -> tagRepository.addTag(item, tag.id)
            }
        }
    }

    fun createAndTag(item: MediaRef, name: String) {
        viewModelScope.launch {
            val relative = vaultRelative(item.displayPath)
            if (relative != null) {
                vaultMeta.createTag(name)?.let { vaultMeta.addTag(relative, it) }
            } else {
                tagRepository.createTag(name)?.let { tagRepository.addTag(item, it.id) }
            }
        }
    }

    fun toggleFavorite(item: MediaRef) {
        viewModelScope.launch {
            val relative = vaultRelative(item.displayPath)
            if (relative != null) {
                vaultMeta.toggleFavorite(relative)
            } else {
                tagRepository.toggleFavorite(item)
            }
        }
    }

    init {
        viewModelScope.launch {
            if (!env.useFakeProvider.first()) {
                when (val access = sessions.streamingAccess()) {
                    is FbxResult.Ok -> _state.update {
                        it.copy(
                            videoAccess = VideoAccess(
                                baseUrl = access.value.first,
                                headers = mapOf(FreeboxApiClient.X_FBX_APP_AUTH to access.value.second),
                            ),
                        )
                    }
                    is FbxResult.Err -> Unit // videos will show their placeholder
                }
            }
        }
    }

    fun streamingUrl(item: MediaRef): String? =
        _state.value.videoAccess?.let { "${it.baseUrl}/dl/${item.pathB64}" }

    /** M8 video: vault media stream through the decrypting DataSource instead. */
    fun vaultVideoFactory(): androidx.media3.datasource.DataSource.Factory =
        com.boxpix.app.data.vault.VaultVideoDataSource.Factory(vaultSession)

    fun vaultVideoUri(item: MediaRef): String? =
        vaultRelative(item.displayPath)
            ?.let { com.boxpix.app.data.vault.VaultVideoDataSource.uriFor(it).toString() }

    fun toggleChrome() = _state.update { it.copy(chromeVisible = !it.chromeVisible) }

    fun setInfoOpen(open: Boolean) = _state.update { it.copy(infoOpen = open) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun trash(item: MediaRef) {
        viewModelScope.launch {
            val relative = vaultRelative(item.displayPath)
            val trashed = if (relative != null) {
                // Vault media go to the in-vault trash, never Room's.
                vaultMeta.trashItems(
                    listOf(VaultMetaRepository.TrashRequest(relative, false, item.sizeBytes)),
                )
            } else {
                trashRepository.trash(listOf(item.toStorageEntry()))
            }
            when (trashed) {
                is FbxResult.Ok -> removeFromList(item)
                is FbxResult.Err -> _state.update { it.copy(error = trashed.error) }
            }
        }
    }

    fun rename(item: MediaRef, newName: String) {
        viewModelScope.launch {
            when (val renamed = provider.rename(item.pathB64, newName.trim())) {
                is FbxResult.Ok -> _state.update { current ->
                    current.copy(
                        items = current.items.map {
                            if (it.pathB64 == item.pathB64) {
                                it.copy(
                                    pathB64 = renamed.value.pathB64,
                                    displayPath = renamed.value.displayPath,
                                    name = renamed.value.name,
                                )
                            } else {
                                it
                            }
                        },
                    )
                }
                is FbxResult.Err -> _state.update { it.copy(error = renamed.error) }
            }
        }
    }

    fun share(item: MediaRef) {
        viewModelScope.launch {
            val bytes = provider.download(item.pathB64).getOrNull()
                ?: run {
                    _state.update { it.copy(error = FreeboxError.Api("download_failed")) }
                    return@launch
                }
            val uri = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "shared").apply { mkdirs() }
                val file = File(dir, item.name)
                file.writeBytes(bytes)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
            _shareUri.value = uri
        }
    }

    fun consumeShare() {
        _shareUri.value = null
    }

    // Move sheet (same interaction as the Explorer's)

    fun openMoveSheet() {
        viewModelScope.launch {
            val rootB64 = rootLocator.rootPathB64() ?: return@launch
            val display = runCatching { PathCodec.decode(rootB64) }.getOrDefault("")
            val root = FolderRef(rootB64, display, display.substringAfterLast('/').ifEmpty { "Root" })
            _state.update { it.copy(move = MoveState(visible = true, stack = listOf(root))) }
            browseMoveTarget()
        }
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

    fun confirmMove(item: MediaRef) {
        val dest = _state.value.move.current ?: return
        viewModelScope.launch {
            when (val moved = provider.move(listOf(item.pathB64), dest.pathB64)) {
                is FbxResult.Ok -> {
                    closeMoveSheet()
                    removeFromList(item)
                }
                is FbxResult.Err -> _state.update { it.copy(error = moved.error) }
            }
        }
    }

    private fun browseMoveTarget() {
        val target = _state.value.move.current ?: return
        viewModelScope.launch {
            _state.update { it.copy(move = it.move.copy(loading = true)) }
            val folders = provider.list(target.pathB64, onlyFolders = true)
                .getOrNull()
                .orEmpty()
                .sortedBy { it.name.lowercase() }
            _state.update { it.copy(move = it.move.copy(folders = folders, loading = false)) }
        }
    }

    private fun removeFromList(item: MediaRef) {
        _state.update { current ->
            current.copy(items = current.items.filterNot { it.pathB64 == item.pathB64 })
        }
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
