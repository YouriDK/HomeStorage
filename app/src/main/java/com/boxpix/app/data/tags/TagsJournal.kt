package com.boxpix.app.data.tags

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.MirrorPaths
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.storage.StorageFolders
import com.boxpix.app.data.storage.StorageProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class TagsSnapshot(
    val version: Int = 1,
    @SerialName("updated_at") val updatedAtEpochSeconds: Long,
    val device: String,
    val tags: List<SnapshotTag>,
    val media: List<SnapshotMedia>,
) {
    @Serializable
    data class SnapshotTag(val name: String, val pinned: Boolean)

    @Serializable
    data class SnapshotMedia(
        val path: String,
        val tags: List<String>,
    )
}

@Serializable
data class JournalAction(
    @SerialName("at") val atEpochSeconds: Long,
    val device: String,
    val action: String,
    val path: String? = null,
    val tag: String? = null,
)

/**
 * SPEC §5 /.meta/tags.json: the disk is the coordination bus. Each mutation
 * exports the full snapshot (small, cheap) and appends who/what/when lines to
 * tags-actions.jsonl. Conflicts are last-write-wins, but a snapshot freshly
 * written by ANOTHER device is not silently clobbered: the caller gets a
 * Conflict to confirm. Meta files are overwritten directly (no tmp+rename):
 * they are idempotent exports the next mutation repairs.
 */
@Singleton
class TagsJournal @Inject constructor(
    private val provider: StorageProvider,
    private val folders: StorageFolders,
    private val rootLocator: RootLocator,
    private val json: Json,
) {

    sealed interface ExportOutcome {
        data object Done : ExportOutcome
        data class Conflict(val remoteDevice: String, val remoteAtEpochSeconds: Long) : ExportOutcome
        data class Failed(val error: FreeboxError) : ExportOutcome
    }

    @Volatile
    private var lastExportedAt: Long = 0

    suspend fun export(
        snapshot: TagsSnapshot,
        actions: List<JournalAction>,
        force: Boolean = false,
    ): ExportOutcome {
        val metaDir = metaDir() ?: return ExportOutcome.Failed(FreeboxError.BoxNotFound)
        folders.ensure(metaDir)?.let { return ExportOutcome.Failed(it) }

        if (!force) {
            val remote = readSnapshot(metaDir)
            if (remote != null &&
                remote.device != snapshot.device &&
                remote.updatedAtEpochSeconds > lastExportedAt &&
                snapshot.updatedAtEpochSeconds - remote.updatedAtEpochSeconds < RECENT_WINDOW_SECONDS
            ) {
                return ExportOutcome.Conflict(remote.device, remote.updatedAtEpochSeconds)
            }
        }

        val snapshotBytes = json.encodeToString(TagsSnapshot.serializer(), snapshot).toByteArray()
        val uploaded = provider.upload(PathCodec.encode(metaDir), TAGS_FILE, snapshotBytes)
        if (uploaded is FbxResult.Err) return ExportOutcome.Failed(uploaded.error)

        if (actions.isNotEmpty()) {
            val existing = provider.download(PathCodec.encode("$metaDir/$ACTIONS_FILE")).getOrNull()
                ?.toString(Charsets.UTF_8).orEmpty()
            val appended = buildString {
                append(existing)
                actions.forEach { appendLine(json.encodeToString(JournalAction.serializer(), it)) }
            }
            provider.upload(PathCodec.encode(metaDir), ACTIONS_FILE, appended.toByteArray())
        }

        lastExportedAt = snapshot.updatedAtEpochSeconds
        return ExportOutcome.Done
    }

    private suspend fun readSnapshot(metaDir: String): TagsSnapshot? =
        provider.download(PathCodec.encode("$metaDir/$TAGS_FILE")).getOrNull()?.let { bytes ->
            runCatching {
                json.decodeFromString(TagsSnapshot.serializer(), bytes.toString(Charsets.UTF_8))
            }.getOrNull()
        }

    private suspend fun metaDir(): String? {
        val rootB64 = rootLocator.rootPathB64() ?: return null
        val rootDisplay = runCatching { PathCodec.decode(rootB64) }.getOrNull() ?: return null
        return MirrorPaths.appRootDirFor(rootDisplay, MirrorPaths.META_DIR, provider.capabilities.canCreateAtRoot)
    }

    companion object {
        const val TAGS_FILE = "tags.json"
        const val ACTIONS_FILE = "tags-actions.jsonl"
        const val RECENT_WINDOW_SECONDS = 600L
    }
}
