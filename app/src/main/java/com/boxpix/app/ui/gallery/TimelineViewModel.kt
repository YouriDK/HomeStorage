package com.boxpix.app.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpix.app.data.db.MediaDao
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.ui.viewer.MediaRef
import com.boxpix.app.ui.viewer.ViewerSession
import com.boxpix.app.ui.viewer.toMediaRef
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimelineViewModel @Inject constructor(
    mediaDao: MediaDao,
    env: StorageEnv,
    private val viewerSession: ViewerSession,
    private val clock: Clock,
) : ViewModel() {

    data class UiState(
        val rows: List<TimelineGrouper.Row> = emptyList(),
        val medias: List<MediaRef> = emptyList(),
        val loaded: Boolean = false,
    )

    val state: StateFlow<UiState> = env.useFakeProvider
        .flatMapLatest { useFake ->
            val providerId =
                if (useFake) TrashRepository.PROVIDER_FAKE else TrashRepository.PROVIDER_FREEBOX
            mediaDao.byCaptureDate(providerId)
        }
        .map { entities ->
            val medias = entities.map { it.toMediaRef() }
            UiState(
                rows = TimelineGrouper.rows(
                    medias,
                    today = LocalDate.now(clock),
                    zone = clock.zone,
                ),
                medias = medias,
                loaded = true,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Stages the viewer on the timeline sequence, starting at the tapped media. */
    fun stageViewer(item: MediaRef) {
        val medias = state.value.medias
        viewerSession.open(medias, medias.indexOfFirst { it.pathB64 == item.pathB64 })
    }
}
