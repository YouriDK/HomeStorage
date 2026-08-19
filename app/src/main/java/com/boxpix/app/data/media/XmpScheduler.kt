package com.boxpix.app.data.media

import com.boxpix.app.data.db.WorkQueueDao
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.trash.TrashRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Kicks the XMP queue shortly after tags change; the nightly worker drains the rest. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class XmpScheduler @Inject constructor(
    private val processor: XmpQueueProcessor,
    private val queueDao: WorkQueueDao,
    private val env: StorageEnv,
    private val scope: CoroutineScope,
) {

    fun start() {
        scope.launch {
            env.useFakeProvider
                .flatMapLatest { useFake ->
                    val providerId =
                        if (useFake) TrashRepository.PROVIDER_FAKE else TrashRepository.PROVIDER_FREEBOX
                    queueDao.pendingCountByType(providerId, WorkQueueEntity.TYPE_XMP)
                }
                .filter { it > 0 }
                .collectLatest {
                    delay(KICK_DELAY_MS)
                    processor.process(BATCH)
                }
        }
    }

    private companion object {
        const val KICK_DELAY_MS = 3_000L
        const val BATCH = 50
    }
}
