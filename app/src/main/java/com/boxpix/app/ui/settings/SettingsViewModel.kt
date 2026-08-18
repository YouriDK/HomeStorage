package com.boxpix.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpix.app.data.freebox.auth.AppTokenStore
import com.boxpix.app.data.freebox.auth.FreeboxSessionManager
import com.boxpix.app.data.net.EndpointResolver
import com.boxpix.app.data.prefs.SettingsStore
import com.boxpix.app.data.prefs.UiPrefsStore
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.trash.TrashRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val uiPrefs: UiPrefsStore,
    private val env: StorageEnv,
    trashRepository: TrashRepository,
    private val tokenStore: AppTokenStore,
    private val sessions: FreeboxSessionManager,
    private val resolver: EndpointResolver,
    private val settings: SettingsStore,
) : ViewModel() {

    data class UiState(
        val gridColumns: Int = UiPrefsStore.DEFAULT_COLUMNS,
        val trashCount: Int = 0,
        val useFake: Boolean = true,
        val hasFakeControls: Boolean = false,
    )

    val state: StateFlow<UiState> = combine(
        uiPrefs.gridColumns,
        trashRepository.count,
        env.useFakeProvider,
    ) { columns, trashCount, useFake ->
        UiState(
            gridColumns = columns,
            trashCount = trashCount,
            useFake = useFake,
            hasFakeControls = env.fakeControls != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun setGridColumns(columns: Int) {
        viewModelScope.launch { uiPrefs.setGridColumns(columns) }
    }

    fun setUseFake(useFake: Boolean) {
        viewModelScope.launch { uiPrefs.setUseFakeProvider(useFake) }
    }

    fun sleepDisk() {
        env.fakeControls?.sleepDisk()
    }

    fun resetFakeData() {
        env.fakeControls?.resetData()
    }

    /** Re-pick disk/root without re-pairing: onboarding reopens on the disk step. */
    fun changeRootFolder() {
        viewModelScope.launch { settings.clearRoot() }
    }

    /** Forgets the app token and the connection config; onboarding takes over. */
    fun resetPairing() {
        viewModelScope.launch(Dispatchers.IO) {
            tokenStore.clear()
            sessions.dropSession()
            resolver.invalidate()
            settings.clearAll()
        }
    }
}
