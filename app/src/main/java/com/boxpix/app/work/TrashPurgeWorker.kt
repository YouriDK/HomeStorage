package com.boxpix.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.boxpix.app.data.trash.TrashRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Daily sweep enforcing the 30-day trash retention (SPEC §2). */
@HiltWorker
class TrashPurgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val trash: TrashRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        trash.purgeOlderThan()
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "trash-purge"
    }
}
