package com.boxpix.app.data.storage

import com.boxpix.app.data.db.ExcludedFolderDao
import com.boxpix.app.data.db.ExcludedFolderEntity
import com.boxpix.app.data.trash.TrashRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

/** Folders the reconciler skips (V1 feedback), shared via /.meta/folders.json. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ScanExclusionRepository @Inject constructor(
    private val dao: ExcludedFolderDao,
    private val env: StorageEnv,
    private val sync: FolderListsSync,
) {

    val excludedFolders: Flow<List<ExcludedFolderEntity>> =
        env.useFakeProvider.flatMapLatest { dao.all(providerId(it)) }

    suspend fun exclude(entry: StorageEntry) {
        dao.insert(
            ExcludedFolderEntity(
                providerId = currentProviderId(),
                pathB64 = entry.pathB64,
                displayPath = entry.displayPath,
            ),
        )
        sync.scheduleExport()
    }

    suspend fun include(pathB64: String) {
        dao.delete(currentProviderId(), pathB64)
        sync.scheduleExport()
    }

    /** True when [displayPath] is excluded or lives inside an excluded subtree. */
    fun isExcluded(displayPath: String, excludedPaths: List<String>): Boolean =
        excludedPaths.any { excluded ->
            excluded == displayPath || displayPath.startsWith("$excluded/")
        }

    private suspend fun currentProviderId(): String = providerId(env.useFakeProvider.first())

    private fun providerId(useFake: Boolean): String =
        if (useFake) TrashRepository.PROVIDER_FAKE else TrashRepository.PROVIDER_FREEBOX
}
