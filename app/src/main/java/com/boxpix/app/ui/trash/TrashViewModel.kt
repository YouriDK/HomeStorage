package com.boxpix.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.db.TrashItemEntity
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.data.vault.VaultMetaRepository
import com.boxpix.app.data.vault.VaultSession
import com.boxpix.app.data.vault.VaultState
import com.boxpix.app.data.vault.VaultTrashRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trashRepository: TrashRepository,
    private val vaultMeta: VaultMetaRepository,
    vaultSession: VaultSession,
) : ViewModel() {

    val items: StateFlow<List<TrashItemEntity>> = trashRepository.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The vault's own trash — visible only while the vault is unlocked. */
    val vaultItems: StateFlow<List<VaultTrashRecord>> =
        combine(vaultMeta.trash, vaultSession.state) { records, state ->
            if (state == VaultState.Unlocked) records else emptyList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<FreeboxError?>(null)
    val error: StateFlow<FreeboxError?> = _error.asStateFlow()

    fun restoreVaultItem(record: VaultTrashRecord) {
        viewModelScope.launch {
            val restored = vaultMeta.restore(record)
            if (restored is FbxResult.Err) _error.value = restored.error
        }
    }

    fun purgeVaultItem(record: VaultTrashRecord) {
        viewModelScope.launch {
            val purged = vaultMeta.deleteForever(record)
            if (purged is FbxResult.Err) _error.value = purged.error
        }
    }

    fun restore(item: TrashItemEntity) {
        viewModelScope.launch {
            val restored = trashRepository.restore(item)
            if (restored is FbxResult.Err) _error.value = restored.error
        }
    }

    fun purge(item: TrashItemEntity) {
        viewModelScope.launch {
            val purged = trashRepository.purge(item)
            if (purged is FbxResult.Err) _error.value = purged.error
        }
    }

    fun emptyTrash() {
        viewModelScope.launch { trashRepository.purgeAll() }
    }

    fun dismissError() {
        _error.value = null
    }
}
