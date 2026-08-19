package com.boxpix.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.boxpix.app.data.media.Reconciler
import com.boxpix.app.data.media.XmpQueueProcessor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Exhaustive nightly reconciliation (SPEC §3): full scan + drain of the queues. */
@HiltWorker
class ReconcilerWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reconciler: Reconciler,
    private val xmpProcessor: XmpQueueProcessor,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        reconciler.runPass(maxFolders = Int.MAX_VALUE, processLimit = HEAVY_PASS_JOB_LIMIT)
        xmpProcessor.process(HEAVY_PASS_JOB_LIMIT)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "reconciler"
        private const val HEAVY_PASS_JOB_LIMIT = 5_000
    }
}
