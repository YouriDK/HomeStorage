package com.boxpix.app.data.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** In-app mirror of the download notification: what the batch is doing right now. */
@Singleton
class DownloadProgress @Inject constructor() {

    data class Active(val fileName: String, val index: Int, val total: Int)

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()

    fun update(fileName: String, index: Int, total: Int) {
        _active.value = Active(fileName, index, total)
    }

    fun clear() {
        _active.value = null
    }
}
