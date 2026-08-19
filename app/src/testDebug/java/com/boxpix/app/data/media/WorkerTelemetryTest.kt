package com.boxpix.app.data.media

import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.support.InMemoryWorkQueueDao
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock

class WorkerTelemetryTest {

    private val telemetry = WorkerTelemetry(Clock.systemUTC())

    @Test
    fun `recent errors keep only the last five, newest first`() {
        repeat(7) { telemetry.errorLogged("THUMB", "photo_$it.jpg", "generation_failed") }
        val errors = telemetry.recentErrors.value
        assertEquals(5, errors.size)
        assertEquals("photo_6.jpg", errors.first().fileName)
        assertEquals("photo_2.jpg", errors.last().fileName)
    }

    @Test
    fun `forced resync is consumed exactly once`() {
        assertFalse(telemetry.consumeForcedResync())
        telemetry.requestResync()
        assertTrue(telemetry.consumeForcedResync())
        assertFalse(telemetry.consumeForcedResync())
    }

    @Test
    fun `cycle end clears the active job and stores the count`() {
        telemetry.cycleStarted()
        telemetry.jobStarted("VIDEO_THUMB", "clip.mp4", 3, 10)
        telemetry.cycleEnded(4)
        assertFalse(telemetry.cycleActive.value)
        assertEquals(null, telemetry.activeJob.value)
        assertEquals(4, telemetry.cycles.value)
    }

    @Test
    fun `retry failed resets status and attempts but leaves done jobs alone`() = runTest {
        val dao = InMemoryWorkQueueDao()
        fun job(path: String, status: String, attempts: Int) = WorkQueueEntity(
            providerId = "freebox", type = WorkQueueEntity.TYPE_VIDEO_THUMB, pathB64 = path,
            displayPath = "/$path", enqueuedMtime = 1L, status = status, attempts = attempts,
            lastError = if (status == WorkQueueEntity.STATUS_FAILED) "boom" else null,
        )
        dao.upsert(job("a", WorkQueueEntity.STATUS_FAILED, 3))
        dao.upsert(job("b", WorkQueueEntity.STATUS_DONE, 0))

        dao.retryFailed("freebox")

        val jobs = dao.allJobs().associateBy { it.pathB64 }
        assertEquals(WorkQueueEntity.STATUS_PENDING, jobs["a"]?.status)
        assertEquals(0, jobs["a"]?.attempts)
        assertEquals(null, jobs["a"]?.lastError)
        assertEquals(WorkQueueEntity.STATUS_DONE, jobs["b"]?.status)
    }
}
