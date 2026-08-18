package com.boxpix.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpix.app.data.freebox.auth.AppTokenStore
import com.boxpix.app.data.prefs.SettingsStore
import com.boxpix.app.data.storage.StorageEnv
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    settings: SettingsStore,
    env: StorageEnv,
    private val tokenStore: AppTokenStore,
) : ViewModel() {

    sealed interface RootState {
        data object Loading : RootState
        data object Onboarding : RootState
        data object Main : RootState
    }

    val state: StateFlow<RootState> =
        combine(env.useFakeProvider, settings.snapshots) { useFake, snapshot ->
            when {
                useFake -> RootState.Main
                snapshot.hasRoot && isPaired() -> RootState.Main
                else -> RootState.Onboarding
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RootState.Loading)

    private suspend fun isPaired(): Boolean =
        withContext(Dispatchers.IO) { tokenStore.appToken != null }
}
