package com.boxpix.app.di

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.storage.StorageCapabilities
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.data.storage.StorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/** Per-call delegation to fake or real storage, following the debug preference. */
class SwitchingStorageProvider(
    private val fake: StorageProvider,
    private val real: StorageProvider,
    useFake: Flow<Boolean>,
    scope: CoroutineScope,
) : StorageProvider {

    private val useFakeState = useFake.stateIn(scope, SharingStarted.Eagerly, initialValue = true)

    private val active: StorageProvider get() = if (useFakeState.value) fake else real

    override val capabilities: StorageCapabilities get() = active.capabilities

    override suspend fun list(pathB64: String?, onlyFolders: Boolean): FbxResult<List<StorageEntry>> =
        active.list(pathB64, onlyFolders)

    override suspend fun download(pathB64: String, range: LongRange?): FbxResult<ByteArray> =
        active.download(pathB64, range)

    override suspend fun mkdir(parentB64: String, name: String): FbxResult<StorageEntry> =
        active.mkdir(parentB64, name)

    override suspend fun rename(pathB64: String, newName: String): FbxResult<StorageEntry> =
        active.rename(pathB64, newName)

    override suspend fun move(pathsB64: List<String>, destParentB64: String): FbxResult<Unit> =
        active.move(pathsB64, destParentB64)

    override suspend fun delete(pathsB64: List<String>): FbxResult<Unit> =
        active.delete(pathsB64)

    override suspend fun upload(parentB64: String, name: String, bytes: ByteArray): FbxResult<Unit> =
        active.upload(parentB64, name, bytes)
}
