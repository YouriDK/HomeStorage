package com.boxpix.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.boxpix.app.data.media.ReconcilerScheduler
import com.boxpix.app.ui.common.ThumbFetcher
import com.boxpix.app.ui.common.ThumbKeyer
import com.boxpix.app.work.ReconcilerWorker
import com.boxpix.app.work.TrashPurgeWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class BoxpixApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var thumbFetcherFactory: ThumbFetcher.Factory

    @Inject
    lateinit var thumbKeyer: ThumbKeyer

    @Inject
    lateinit var reconcilerScheduler: ReconcilerScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(thumbKeyer)
                add(thumbFetcherFactory)
            }
            .crossfade(true)
            .build()

    override fun onCreate() {
        super.onCreate()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            TrashPurgeWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TrashPurgeWorker>(1, TimeUnit.DAYS).build(),
        )

        // Exhaustive reconciliation runs on unmetered network + charging (SPEC §3).
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ReconcilerWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReconcilerWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build(),
                )
                .build(),
        )

        reconcilerScheduler.start()
    }
}
