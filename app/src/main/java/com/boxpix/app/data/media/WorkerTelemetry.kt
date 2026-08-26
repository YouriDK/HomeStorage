package com.boxpix.app.data.media

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live state of the worker loop for the local dashboard (SPEC M7). Pure
 * in-memory: the service and processors report in, WorkerScreen collects.
 * Cross-device status stays on the disk (/.meta/worker-status.json) — this
 * never leaves the phone.
 */
@Singleton
class WorkerTelemetry @Inject constructor(private val clock: Clock) {

    data class ActiveJob(val type: String, val fileName: String, val index: Int, val total: Int)

    data class RecentError(
        val atEpochSeconds: Long,
        val type: String,
        val fileName: String,
        val message: String,
    )

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _startedAtEpochSeconds = MutableStateFlow<Long?>(null)
    val startedAtEpochSeconds: StateFlow<Long?> = _startedAtEpochSeconds.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _cycleActive = MutableStateFlow(false)
    val cycleActive: StateFlow<Boolean> = _cycleActive.asStateFlow()

    private val _cycles = MutableStateFlow(0)
    val cycles: StateFlow<Int> = _cycles.asStateFlow()

    private val _activeJob = MutableStateFlow<ActiveJob?>(null)
    val activeJob: StateFlow<ActiveJob?> = _activeJob.asStateFlow()

    private val _lastPassEpochSeconds = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastPassEpochSeconds: StateFlow<Map<String, Long>> = _lastPassEpochSeconds.asStateFlow()

    private val _recentErrors = MutableStateFlow<List<RecentError>>(emptyList())
    val recentErrors: StateFlow<List<RecentError>> = _recentErrors.asStateFlow()

    private val wakeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    private var resyncForced = false

    fun serviceStarted() {
        _running.value = true
        _startedAtEpochSeconds.value = clock.instant().epochSecond
    }

    fun serviceStopped() {
        _running.value = false
        _startedAtEpochSeconds.value = null
        _cycleActive.value = false
        _activeJob.value = null
    }

    fun setPaused(paused: Boolean) {
        _paused.value = paused
        if (!paused) wakeRequests.tryEmit(Unit)
    }

    /** Runs a cycle at the next wake even off-charger (never on metered). */
    fun requestResync() {
        resyncForced = true
        wakeRequests.tryEmit(Unit)
    }

    fun consumeForcedResync(): Boolean {
        val forced = resyncForced
        resyncForced = false
        return forced
    }

    /** Sleeps up to [maxMillis], cut short by a resync request or un-pause. */
    suspend fun awaitWake(maxMillis: Long) {
        withTimeoutOrNull(maxMillis) { wakeRequests.first() }
    }

    fun cycleStarted() {
        _cycleActive.value = true
    }

    fun cycleEnded(totalCycles: Int) {
        _cycleActive.value = false
        _activeJob.value = null
        _cycles.value = totalCycles
    }

    fun jobStarted(type: String, fileName: String, index: Int, total: Int) {
        _activeJob.value = ActiveJob(type, fileName, index, total)
    }

    fun jobsFinished() {
        _activeJob.value = null
    }

    fun passDone(pass: String) {
        _lastPassEpochSeconds.update { it + (pass to clock.instant().epochSecond) }
    }

    fun errorLogged(type: String, fileName: String, message: String) {
        _recentErrors.update { errors ->
            (listOf(RecentError(clock.instant().epochSecond, type, fileName, message)) + errors)
                .take(MAX_ERRORS)
        }
    }

    companion object {
        const val PASS_RECONCILE = "reconcile"
        const val PASS_VIDEO_THUMBS = "video_thumbs"
        const val PASS_XMP = "xmp"
        const val PASS_PURGE = "purge"
        const val PASS_BACKUP = "backup"
        private const val MAX_ERRORS = 5
    }
}
