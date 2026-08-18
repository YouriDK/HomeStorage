package com.boxpix.app.data.fake

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.FakeControls
import com.boxpix.app.data.storage.StorageCapabilities
import com.boxpix.app.data.storage.StorageEntry
import com.boxpix.app.data.storage.StorageProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * In-memory StorageProvider used while M1 is not validated against the real box.
 * Serves the deterministic FakeTree with simulated latency, a triggerable
 * "sleeping disk" mode (first request stalls), and coherent mutations —
 * everything the Explorer needs, no network involved.
 */
class FakeStorageProvider(
    private val config: FakeConfig = FakeConfig(),
    private val seed: Int = 42,
    /** Null (JVM tests): image downloads fall back to filler bytes. */
    private val synthesizer: FakeImageSynthesizer? = null,
) : StorageProvider, FakeControls {

    data class FakeConfig(
        val latencyMillis: LongRange = 50L..300L,
        val wakeDelayMillis: Long = 6_000L,
    )

    private val mutex = Mutex()

    @Volatile
    private var root: FolderNode = FakeTree.seed(Random(seed))

    @Volatile
    private var diskAsleep = false

    private val latencyRandom = Random(seed + 1)

    override val capabilities = StorageCapabilities(
        supportsRangeRequests = true,
        canCreateAtRoot = true, // the fake root plays the role of the disk root
    )

    override suspend fun list(pathB64: String?, onlyFolders: Boolean): FbxResult<List<StorageEntry>> {
        simulateLatency()
        return mutex.withLock {
            val path = displayPath(pathB64)
            val folder = resolveFolder(path)
                ?: return@withLock err(StorageProvider.ERROR_NOT_FOUND)
            folder.children
                .filterNot { it.name.startsWith(".") }
                .filter { !onlyFolders || it is FolderNode }
                .map { it.toEntry(path) }
                .let { FbxResult.Ok(it) }
        }
    }

    override suspend fun download(pathB64: String, range: LongRange?): FbxResult<ByteArray> {
        simulateLatency()
        return mutex.withLock {
            val (_, node) = resolve(displayPath(pathB64))
                ?: return@withLock err(StorageProvider.ERROR_NOT_FOUND)
            val file = node as? FileNode ?: return@withLock err("path_is_directory")
            val bytes = file.content
                ?: if (file.mimeType.startsWith("image/") && synthesizer != null) {
                    synthesizer.jpegWithExif(file.name.hashCode(), file.takenAtEpochSeconds)
                } else {
                    // Videos and JVM tests: deterministic filler bytes, capped.
                    val length = file.sizeBytes.coerceAtMost(262_144L).toInt()
                    ByteArray(length) { (file.name.hashCode() + it).toByte() }
                }
            val sliced = if (range == null) {
                bytes
            } else {
                val from = range.first.coerceIn(0, bytes.size.toLong()).toInt()
                val to = (range.last + 1).coerceIn(from.toLong(), bytes.size.toLong()).toInt()
                bytes.copyOfRange(from, to)
            }
            FbxResult.Ok(sliced)
        }
    }

    override suspend fun upload(parentB64: String, name: String, bytes: ByteArray): FbxResult<Unit> {
        simulateLatency()
        return mutex.withLock {
            val parentPath = displayPath(parentB64)
            val parent = resolveFolder(parentPath)
                ?: return@withLock err(StorageProvider.ERROR_NOT_FOUND)
            parent.children.removeAll { it is FileNode && it.name == name } // overwrite semantics
            parent.children += FileNode(
                name = name,
                mtime = nowSeconds(),
                sizeBytes = bytes.size.toLong(),
                takenAtEpochSeconds = nowSeconds(),
                mimeType = mimeTypeFor(name),
                content = bytes,
            )
            FbxResult.Ok(Unit)
        }
    }

    override suspend fun mkdir(parentB64: String, name: String): FbxResult<StorageEntry> {
        simulateLatency()
        return mutex.withLock {
            val parentPath = displayPath(parentB64)
            val parent = resolveFolder(parentPath)
                ?: return@withLock err(StorageProvider.ERROR_NOT_FOUND)
            if (parent.childByName(name) != null) {
                return@withLock err(StorageProvider.ERROR_CONFLICT)
            }
            val folder = FolderNode(name, mtime = nowSeconds())
            parent.children += folder
            FbxResult.Ok(folder.toEntry(parentPath))
        }
    }

    override suspend fun rename(pathB64: String, newName: String): FbxResult<StorageEntry> {
        simulateLatency()
        return mutex.withLock {
            val path = displayPath(pathB64)
            val (parent, node) = resolve(path)
                ?: return@withLock err(StorageProvider.ERROR_NOT_FOUND)
            if (parent == null) return@withLock err("cannot_rename_root")
            if (node.name != newName && parent.childByName(newName) != null) {
                return@withLock err(StorageProvider.ERROR_CONFLICT)
            }
            node.name = newName
            node.mtime = nowSeconds()
            FbxResult.Ok(node.toEntry(parentPathOf(path)))
        }
    }

    override suspend fun move(pathsB64: List<String>, destParentB64: String): FbxResult<Unit> {
        simulateLatency()
        return mutex.withLock {
            val destPath = displayPath(destParentB64)
            val dest = resolveFolder(destPath)
                ?: return@withLock err(StorageProvider.ERROR_NOT_FOUND)

            // Validate everything before mutating: a fake should not half-move.
            val moves = pathsB64.map { encoded ->
                val path = displayPath(encoded)
                val resolved = resolve(path)
                    ?: return@withLock err(StorageProvider.ERROR_NOT_FOUND)
                val (parent, node) = resolved
                if (parent == null) return@withLock err("cannot_move_root")
                if (node is FolderNode && (destPath == path || destPath.startsWith("$path/"))) {
                    return@withLock err("destination_inside_source")
                }
                if (dest.childByName(node.name) != null && parent !== dest) {
                    return@withLock err(StorageProvider.ERROR_CONFLICT)
                }
                parent to node
            }

            moves.forEach { (parent, node) ->
                if (parent !== dest) {
                    parent.children.remove(node)
                    dest.children += node
                }
            }
            FbxResult.Ok(Unit)
        }
    }

    override suspend fun delete(pathsB64: List<String>): FbxResult<Unit> {
        simulateLatency()
        return mutex.withLock {
            pathsB64.forEach { encoded ->
                val (parent, node) = resolve(displayPath(encoded))
                    ?: return@withLock err(StorageProvider.ERROR_NOT_FOUND)
                parent?.children?.remove(node) ?: return@withLock err("cannot_delete_root")
            }
            FbxResult.Ok(Unit)
        }
    }

    // FakeControls

    override fun sleepDisk() {
        diskAsleep = true
    }

    override fun resetData() {
        root = FakeTree.seed(Random(seed))
    }

    // Internals

    private suspend fun simulateLatency() {
        if (diskAsleep) {
            diskAsleep = false
            delay(config.wakeDelayMillis)
        } else if (config.latencyMillis.last > 0) {
            delay(latencyRandom.nextLong(config.latencyMillis.first, config.latencyMillis.last + 1))
        }
    }

    private fun displayPath(pathB64: String?): String =
        pathB64?.let(PathCodec::decode)?.trimEnd('/')?.ifEmpty { "/" } ?: "/"

    private fun segments(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }

    private fun parentPathOf(path: String): String =
        "/" + segments(path).dropLast(1).joinToString("/")

    /** Returns (parent, node); parent is null for the root itself. */
    private fun resolve(path: String): Pair<FolderNode?, FakeNode>? {
        var parent: FolderNode? = null
        var current: FakeNode = root
        for (segment in segments(path)) {
            val folder = current as? FolderNode ?: return null
            parent = folder
            current = folder.childByName(segment) ?: return null
        }
        return parent to current
    }

    private fun resolveFolder(path: String): FolderNode? =
        resolve(path)?.second as? FolderNode

    private fun FolderNode.childByName(name: String): FakeNode? =
        children.firstOrNull { it.name == name }

    private fun FakeNode.toEntry(parentPath: String): StorageEntry {
        val display = if (parentPath == "/") "/$name" else "$parentPath/$name"
        return when (this) {
            is FolderNode -> StorageEntry(
                pathB64 = PathCodec.encode(display),
                displayPath = display,
                name = name,
                isDirectory = true,
                sizeBytes = 0,
                modifiedEpochSeconds = mtime,
                mimeType = null,
                hidden = name.startsWith("."),
            )
            is FileNode -> StorageEntry(
                pathB64 = PathCodec.encode(display),
                displayPath = display,
                name = name,
                isDirectory = false,
                sizeBytes = sizeBytes,
                modifiedEpochSeconds = mtime,
                mimeType = mimeType,
                hidden = name.startsWith("."),
                durationSeconds = durationSeconds,
            )
        }
    }

    private fun nowSeconds() = System.currentTimeMillis() / 1000

    private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "webp" -> "image/webp"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "heic" -> "image/heic"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun err(code: String) = FbxResult.Err(FreeboxError.Api(code))
}
