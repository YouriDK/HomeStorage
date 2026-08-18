package com.boxpix.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.freebox.auth.AppTokenStore
import com.boxpix.app.data.freebox.auth.FreeboxPairingManager
import com.boxpix.app.data.freebox.auth.FreeboxPairingManager.PairingEvent
import com.boxpix.app.data.prefs.SettingsStore
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.data.storage.StorageProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val pairingManager: FreeboxPairingManager,
    private val provider: StorageProvider,
    private val settings: SettingsStore,
    private val tokenStore: AppTokenStore,
) : ViewModel() {

    sealed interface Step {
        data object Start : Step
        data object Pairing : Step

        /**
         * Disk selection plus a light folder browser to pick the root below the disk.
         * [folderStack] is the descent from the disk; the root candidate is its last
         * element, or the disk itself when empty.
         */
        data class ChooseDisk(
            val disks: List<StorageEntry>,
            val selectedDisk: StorageEntry? = null,
            val folderStack: List<StorageEntry> = emptyList(),
            val subFolders: List<StorageEntry> = emptyList(),
            val listing: Boolean = false,
        ) : Step {
            val rootCandidate: StorageEntry? get() = folderStack.lastOrNull() ?: selectedDisk
        }
    }

    data class UiState(
        val step: Step = Step.Start,
        val advancedOpen: Boolean = false,
        val host: String = "",
        val hasStoredToken: Boolean = false,
        val busy: Boolean = false,
        val error: FreeboxError? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pairingJob: Job? = null

    init {
        viewModelScope.launch {
            val hasToken = withContext(Dispatchers.IO) { tokenStore.appToken != null }
            val manualHost = settings.current().manualHost
            _state.update { it.copy(hasStoredToken = hasToken, host = manualHost.orEmpty()) }
        }
    }

    fun toggleAdvanced() = _state.update { it.copy(advancedOpen = !it.advancedOpen) }

    fun setHost(value: String) = _state.update { it.copy(host = value) }

    fun connect() {
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            val hasToken = withContext(Dispatchers.IO) { tokenStore.appToken != null }
            if (hasToken) loadDisks() else startPairing()
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairingJob = null
        _state.update { it.copy(step = Step.Start) }
    }

    fun selectDisk(disk: StorageEntry) {
        updateChooseDisk { it.copy(selectedDisk = disk, folderStack = emptyList(), subFolders = emptyList()) }
        browse(disk)
    }

    fun openFolder(folder: StorageEntry) {
        updateChooseDisk { it.copy(folderStack = it.folderStack + folder) }
        browse(folder)
    }

    fun upOneLevel() {
        val step = _state.value.step as? Step.ChooseDisk ?: return
        val stack = step.folderStack.dropLast(1)
        val target = stack.lastOrNull() ?: step.selectedDisk ?: return
        updateChooseDisk { it.copy(folderStack = stack) }
        browse(target)
    }

    fun confirmRoot() {
        val step = _state.value.step as? Step.ChooseDisk ?: return
        val disk = step.selectedDisk ?: return
        val root = step.rootCandidate ?: return
        viewModelScope.launch {
            settings.saveRoot(disk.name, root.pathB64, root.displayPath)
            // RootViewModel observes the settings flow and switches to the debug listing.
        }
    }

    private fun startPairing() {
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            val host = _state.value.host.trim().ifBlank { null }
            pairingManager.pair(host).collect { event ->
                when (event) {
                    PairingEvent.Discovering,
                    PairingEvent.AwaitingValidation,
                    -> _state.update { it.copy(step = Step.Pairing) }

                    PairingEvent.Granted -> {
                        _state.update { it.copy(hasStoredToken = true) }
                        loadDisks()
                    }

                    is PairingEvent.Failed ->
                        _state.update { it.copy(step = Step.Start, error = event.error) }
                }
            }
        }
    }

    private suspend fun loadDisks() {
        _state.update { it.copy(busy = true) }
        when (val outcome = provider.list(pathB64 = null, onlyFolders = true)) {
            is FbxResult.Ok -> _state.update {
                it.copy(busy = false, step = Step.ChooseDisk(disks = outcome.value.filter(StorageEntry::isDirectory)))
            }

            is FbxResult.Err -> _state.update {
                it.copy(busy = false, step = Step.Start, error = outcome.error)
            }
        }
    }

    private fun browse(folder: StorageEntry) {
        viewModelScope.launch {
            updateChooseDisk { it.copy(listing = true) }
            when (val outcome = provider.list(folder.pathB64, onlyFolders = true)) {
                is FbxResult.Ok ->
                    updateChooseDisk { it.copy(subFolders = outcome.value, listing = false) }

                is FbxResult.Err -> {
                    updateChooseDisk { it.copy(listing = false) }
                    _state.update { it.copy(error = outcome.error) }
                }
            }
        }
    }

    private inline fun updateChooseDisk(crossinline transform: (Step.ChooseDisk) -> Step.ChooseDisk) {
        _state.update { current ->
            (current.step as? Step.ChooseDisk)
                ?.let { current.copy(step = transform(it)) }
                ?: current
        }
    }
}
