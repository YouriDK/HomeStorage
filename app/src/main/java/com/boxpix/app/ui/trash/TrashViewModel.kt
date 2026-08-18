package com.boxpix.app.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.db.TrashItemEntity
import com.boxpix.app.data.trash.TrashRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val trashRepository: TrashRepository,
) : ViewModel() {

    val items: StateFlow<List<TrashItemEntity>> = trashRepository.items
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<FreeboxError?>(null)
    val error: StateFlow<FreeboxError?> = _error.asStateFlow()

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
