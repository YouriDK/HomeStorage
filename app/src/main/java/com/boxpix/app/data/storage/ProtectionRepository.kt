package com.boxpix.app.data.storage

import com.boxpix.app.data.db.ProtectedFolderDao
import com.boxpix.app.data.db.ProtectedFolderEntity
import com.boxpix.app.data.trash.TrashRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

/** User-declared "do not touch" folders (per provider, like the trash records). */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ProtectionRepository @Inject constructor(
    private val dao: ProtectedFolderDao,
    private val env: StorageEnv,
) {

    val protectedFolders: Flow<List<ProtectedFolderEntity>> =
        env.useFakeProvider.flatMapLatest { dao.all(providerId(it)) }

    suspend fun protect(entry: StorageEntry) {
        dao.insert(
            ProtectedFolderEntity(
                providerId = currentProviderId(),
                pathB64 = entry.pathB64,
                displayPath = entry.displayPath,
            ),
        )
    }

    suspend fun unprotect(pathB64: String) {
        dao.delete(currentProviderId(), pathB64)
    }

    /**
     * True when [displayPath] is protected, contains a protected folder (trashing
     * the parent would take it along), or lives inside one (the protection covers
     * the whole subtree).
     */
    fun isGuarded(displayPath: String, protectedPaths: List<String>): Boolean =
        protectedPaths.any { protected ->
            protected == displayPath ||
                protected.startsWith("$displayPath/") ||
                displayPath.startsWith("$protected/")
        }

    private suspend fun currentProviderId(): String = providerId(env.useFakeProvider.first())

    private fun providerId(useFake: Boolean): String =
        if (useFake) TrashRepository.PROVIDER_FAKE else TrashRepository.PROVIDER_FREEBOX
}
