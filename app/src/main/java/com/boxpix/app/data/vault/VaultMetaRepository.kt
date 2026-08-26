package com.boxpix.app.data.vault

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.media.MediaTypes
import com.boxpix.app.data.db.SearchQueryBuilder.TypeFilter
import com.boxpix.app.data.storage.StorageProvider
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * The vault's index and tags: loaded into memory at unlock, reconciled by
 * diffing against the vault listing (same philosophy as the Reconciler,
 * reduced perimeter), persisted encrypted INSIDE the vault (`/.meta/`) with a
 * light write-through debounce, flushed before every lock, purged at lock.
 * `.meta` is dot-named, so the provider's own listing filter keeps it out of
 * the UI and out of this walk.
 */
class VaultMetaRepository(
    private val session: VaultSession,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    /** Tests drive [open]/[flush] by hand instead of reacting to the session. */
    observeSession: Boolean = true,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val _entries = MutableStateFlow<List<VaultIndexEntry>>(emptyList())
    val entries: StateFlow<List<VaultIndexEntry>> = _entries.asStateFlow()

    private val _tags = MutableStateFlow(VaultTagsFile())
    val tags: StateFlow<VaultTagsFile> = _tags.asStateFlow()

    private val _trash = MutableStateFlow<List<VaultTrashRecord>>(emptyList())
    val trash: StateFlow<List<VaultTrashRecord>> = _trash.asStateFlow()

    private val persistMutex = Mutex()
    private var indexDirty = false
    private var tagsDirty = false
    private var persistJob: Job? = null
    private var mutationJob: Job? = null

    init {
        session.registerLockParticipant { flush() }
        if (observeSession) {
            scope.launch {
                session.state.collect { state ->
                    when (state) {
                        VaultState.Unlocked -> open()
                        VaultState.Locked, VaultState.NoVault -> purge()
                        VaultState.Unlocking -> Unit
                    }
                }
            }
        }
    }

    /** Tag names with usage counts, for pickers and search chips. */
    fun tagCounts(): List<Pair<String, Int>> {
        val current = _tags.value
        val counts = HashMap<String, Int>()
        current.files.values.forEach { meta -> meta.tags.forEach { counts.merge(it, 1, Int::plus) } }
        return current.tags.map { it to (counts[it] ?: 0) }
    }

    /** Vault-relative paths marked favourite. */
    fun favoriteRelativePaths(): List<String> =
        _tags.value.files.filterValues { it.favorite }.keys.toList()

    fun tagsFor(relativePath: String): List<String> =
        _tags.value.files[relativePath]?.tags.orEmpty()

    fun isFavorite(relativePath: String): Boolean =
        _tags.value.files[relativePath]?.favorite == true

    fun createTag(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        _tags.update { current ->
            if (current.tags.any { it.equals(trimmed, ignoreCase = true) }) current
            else current.copy(tags = current.tags + trimmed)
        }
        markTagsDirty()
        return trimmed
    }

    fun addTag(relativePath: String, tagName: String) {
        createTag(tagName)
        updateFileMeta(relativePath) { meta ->
            if (tagName in meta.tags) meta else meta.copy(tags = meta.tags + tagName)
        }
    }

    fun removeTag(relativePath: String, tagName: String) {
        updateFileMeta(relativePath) { meta -> meta.copy(tags = meta.tags - tagName) }
    }

    fun toggleFavorite(relativePath: String) {
        updateFileMeta(relativePath) { meta -> meta.copy(favorite = !meta.favorite) }
    }

    /** In-memory equivalent of the Room search, over the vault's index. */
    fun search(
        nameContains: String? = null,
        types: Set<TypeFilter> = emptySet(),
        fromEpochSeconds: Long? = null,
        toEpochSeconds: Long? = null,
        folderPrefix: String? = null,
        tagNames: Set<String> = emptySet(),
    ): List<VaultIndexEntry> {
        val needle = nameContains?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return _entries.value.filter { entry ->
            val date = entry.takenAtEpochSeconds ?: entry.mtime
            (needle == null || needle in entry.name.lowercase()) &&
                (types.isEmpty() || typeOf(entry) in types) &&
                (fromEpochSeconds == null || date >= fromEpochSeconds) &&
                (toEpochSeconds == null || date <= toEpochSeconds) &&
                (folderPrefix == null || entry.folder == folderPrefix ||
                    entry.folder.startsWith("$folderPrefix/") ||
                    (folderPrefix == "/" )) &&
                (tagNames.isEmpty() || tagsFor(entry.path).containsAll(tagNames))
        }
    }

    /**
     * One reconciliation pass: walk the unlocked vault, diff against the
     * index. Renames keep their metadata and tags through a unique
     * (size, mtime) match — the same file, moved.
     */
    suspend fun reconcile() {
        val provider = session.provider ?: return
        val listed = walk(provider) ?: return

        val known = _entries.value.associateBy { it.path }
        val fresh = listed.associateBy { it.path }

        val removed = known.keys - fresh.keys
        val added = fresh.keys - known.keys

        // Rename detection: a removed and an added entry with the same unique
        // (size, mtime) signature are one file that moved; a unique size alone
        // is accepted as fallback (moves on some filesystems touch mtime).
        // Ambiguity always degrades to remove+add — never a wrong match.
        val removedEntries = removed.mapNotNull { known[it] }
        val bySignature = removedEntries.groupBy { it.sizeBytes to it.mtime }
            .filterValues { it.size == 1 }
            .mapValues { (_, v) -> v.single() }
        val bySize = removedEntries.groupBy { it.sizeBytes }
            .filterValues { it.size == 1 }
            .mapValues { (_, v) -> v.single() }
        val addedSizeCounts = added.groupingBy { fresh.getValue(it).sizeBytes }.eachCount()
        val renames = HashMap<String, VaultIndexEntry>() // new path -> old entry
        added.forEach { path ->
            val candidate = fresh.getValue(path)
            val old = bySignature[candidate.sizeBytes to candidate.mtime]
                ?: bySize[candidate.sizeBytes]?.takeIf { addedSizeCounts[candidate.sizeBytes] == 1 }
            if (old != null && renames.values.none { it.path == old.path }) renames[path] = old
        }

        val next = listed.map { entry ->
            val previous = known[entry.path] ?: renames[entry.path]
            val sameFile = previous != null &&
                (previous.mtime == entry.mtime || renames.containsKey(entry.path))
            if (previous != null && sameFile) {
                entry.copy(
                    takenAtEpochSeconds = previous.takenAtEpochSeconds,
                    takenAtManual = previous.takenAtManual,
                    locationText = previous.locationText,
                    durationSeconds = previous.durationSeconds ?: entry.durationSeconds,
                    hasThumb = previous.hasThumb,
                )
            } else {
                entry
            }
        }

        if (next != _entries.value) {
            _entries.value = next
            indexDirty = true
        }

        // Tags follow renames; entries gone for good drop their file meta —
        // except trashed ones, whose tags must survive until restore or purge.
        if (renames.isNotEmpty() || removed.isNotEmpty()) {
            val renamedOldPaths = renames.values.map { it.path }.toSet()
            val trashedPaths = _trash.value.map { it.originalPath }.toSet()
            _tags.update { current ->
                val files = current.files.toMutableMap()
                renames.forEach { (newPath, old) -> files.remove(old.path)?.let { files[newPath] = it } }
                (removed - renamedOldPaths - trashedPaths).forEach { files.remove(it) }
                current.copy(files = files)
            }
            tagsDirty = true
        }
        if (indexDirty || tagsDirty) schedulePersist()
    }

    /** Updates one entry in place (metadata edits, thumb bookkeeping). */
    fun updateEntry(relativePath: String, transform: (VaultIndexEntry) -> VaultIndexEntry) {
        _entries.update { current ->
            current.map { if (it.path == relativePath) transform(it) else it }
        }
        indexDirty = true
        schedulePersist()
    }

    /** Batch metadata, vault flavour of MetadataRepository.applyToSelection. */
    fun applyMetadata(
        relativePaths: List<String>,
        tagNames: Set<String>,
        takenAtEpochSeconds: Long?,
        locationText: String?,
    ) {
        relativePaths.forEach { path ->
            tagNames.forEach { addTag(path, it) }
            if (takenAtEpochSeconds != null || locationText != null) {
                updateEntry(path) { entry ->
                    entry.copy(
                        takenAtEpochSeconds = takenAtEpochSeconds ?: entry.takenAtEpochSeconds,
                        takenAtManual = entry.takenAtManual || takenAtEpochSeconds != null,
                        locationText = locationText?.trim()?.ifEmpty { null } ?: entry.locationText,
                    )
                }
            }
        }
    }

    data class TrashRequest(val relativePath: String, val isDirectory: Boolean, val sizeBytes: Long)

    /**
     * In-vault trash: a move to the `/.trash/<mirror>` INSIDE the vault (its
     * dot name keeps it out of listings), recorded in the index — never in
     * Room's trash_items.
     */
    suspend fun trashItems(items: List<TrashRequest>): FbxResult<Unit> {
        val provider = session.provider ?: return lockedError()
        for (item in items) {
            val originalName = item.relativePath.substringAfterLast('/')
            val parent = item.relativePath.substringBeforeLast('/').ifEmpty { "/" }
            val trashDir = if (parent == "/") "/$TRASH_DIR" else "/$TRASH_DIR$parent"
            mkdirs(provider, trashDir)

            // Same suffix dance as the clear trash: "name (2).ext" on conflict.
            var name = originalName
            var currentPath = item.relativePath
            var attempt = 1
            val moved: FbxResult<Unit>
            while (true) {
                val result = provider.move(listOf(PathCodec.encode(currentPath)), PathCodec.encode(trashDir))
                if (result is FbxResult.Ok || attempt >= 3 ||
                    ((result as FbxResult.Err).error as? FreeboxError.Api)?.code !in
                    StorageProvider.CONFLICT_CODES
                ) {
                    moved = result
                    break
                }
                attempt++
                val base = originalName.substringBeforeLast('.', originalName)
                val ext = originalName.substringAfterLast('.', "")
                val candidate = if (ext.isEmpty()) "$base ($attempt)" else "$base ($attempt).$ext"
                when (val renamed = provider.rename(PathCodec.encode(currentPath), candidate)) {
                    is FbxResult.Ok -> {
                        name = candidate
                        currentPath = renamed.value.displayPath
                    }
                    is FbxResult.Err -> return renamed
                }
            }
            if (moved is FbxResult.Err) return moved
            _trash.update {
                it + VaultTrashRecord(
                    originalPath = item.relativePath,
                    trashPath = "$trashDir/$name",
                    name = name,
                    isDirectory = item.isDirectory,
                    sizeBytes = item.sizeBytes,
                    trashedAtEpochSeconds = clock.instant().epochSecond,
                )
            }
            _entries.update { entries ->
                entries.filterNot {
                    it.path == item.relativePath ||
                        (item.isDirectory && it.path.startsWith("${item.relativePath}/"))
                }
            }
            indexDirty = true
        }
        schedulePersist()
        return FbxResult.Ok(Unit)
    }

    suspend fun restore(record: VaultTrashRecord): FbxResult<Unit> {
        val provider = session.provider ?: return lockedError()
        val parent = record.originalPath.substringBeforeLast('/').ifEmpty { "/" }
        mkdirs(provider, parent)
        val moved = provider.move(listOf(PathCodec.encode(record.trashPath)), PathCodec.encode(parent))
        if (moved is FbxResult.Err) return moved
        _trash.update { it - record }
        indexDirty = true
        reconcile() // the restored entry reappears with its kept meta
        return FbxResult.Ok(Unit)
    }

    suspend fun deleteForever(record: VaultTrashRecord): FbxResult<Unit> {
        val provider = session.provider ?: return lockedError()
        val deleted = provider.delete(listOf(PathCodec.encode(record.trashPath)))
        if (deleted is FbxResult.Err) return deleted
        _trash.update { it - record }
        _tags.update { it.copy(files = it.files - record.originalPath) }
        indexDirty = true
        tagsDirty = true
        schedulePersist()
        return FbxResult.Ok(Unit)
    }

    /** Runs at unlock: no worker ever touches the vault (SPEC M8). */
    suspend fun purgeExpiredTrash(days: Long = TRASH_RETENTION_DAYS) {
        val cutoff = clock.instant().epochSecond - days * 86_400
        _trash.value.filter { it.trashedAtEpochSeconds < cutoff }.forEach { deleteForever(it) }
    }

    /** Debounced full reconcile after Explorer-driven vault mutations. */
    fun onVaultMutated() {
        mutationJob?.cancel()
        mutationJob = scope.launch {
            delay(MUTATION_RECONCILE_DEBOUNCE_MS)
            reconcile()
        }
    }

    /** Writes whatever is dirty, now. Safe to call anytime while unlocked. */
    suspend fun flush() {
        persistJob?.cancel()
        persistMutex.withLock {
            val provider = session.provider ?: return
            if (indexDirty) {
                val payload = json.encodeToString(
                    VaultIndexFile.serializer(),
                    VaultIndexFile(entries = _entries.value, trash = _trash.value),
                )
                if (write(provider, INDEX_FILE, payload)) indexDirty = false
            }
            if (tagsDirty) {
                val payload = json.encodeToString(VaultTagsFile.serializer(), _tags.value)
                if (write(provider, TAGS_FILE, payload)) tagsDirty = false
            }
        }
    }

    /** Loads the persisted meta from the unlocked vault, then reconciles. */
    suspend fun open() {
        val provider = session.provider ?: return
        val index = read(provider, INDEX_FILE)
            ?.let { runCatching { json.decodeFromString(VaultIndexFile.serializer(), it) }.getOrNull() }
        _entries.value = index?.entries.orEmpty()
        _trash.value = index?.trash.orEmpty()
        _tags.value = read(provider, TAGS_FILE)
            ?.let { runCatching { json.decodeFromString(VaultTagsFile.serializer(), it) }.getOrNull() }
            ?: VaultTagsFile()
        indexDirty = false
        tagsDirty = false
        purgeExpiredTrash()
        reconcile()
    }

    private fun purge() {
        persistJob?.cancel()
        mutationJob?.cancel()
        _entries.value = emptyList()
        _trash.value = emptyList()
        _tags.value = VaultTagsFile()
        indexDirty = false
        tagsDirty = false
    }

    private suspend fun walk(provider: CryptomatorProvider): List<VaultIndexEntry>? {
        val result = ArrayList<VaultIndexEntry>()
        val toVisit = ArrayDeque(listOf("/"))
        var visited = 0
        while (toVisit.isNotEmpty() && visited < MAX_FOLDERS_PER_PASS) {
            val folder = toVisit.removeFirst()
            val listed = when (val r = provider.list(PathCodec.encode(folder))) {
                is FbxResult.Ok -> r.value
                is FbxResult.Err -> return null // transient failure: keep the old index
            }
            visited++
            listed.forEach { entry ->
                if (entry.isDirectory) {
                    toVisit.addLast(entry.displayPath)
                } else {
                    result += VaultIndexEntry(
                        path = entry.displayPath,
                        name = entry.name,
                        folder = folder,
                        sizeBytes = entry.sizeBytes,
                        mtime = entry.modifiedEpochSeconds,
                        isVideo = entry.mimeType?.startsWith("video/") == true,
                        durationSeconds = entry.durationSeconds,
                    )
                }
            }
        }
        return result
    }

    private fun updateFileMeta(relativePath: String, transform: (VaultFileMeta) -> VaultFileMeta) {
        _tags.update { current ->
            val meta = transform(current.files[relativePath] ?: VaultFileMeta())
            val files = current.files.toMutableMap()
            if (meta == VaultFileMeta()) files.remove(relativePath) else files[relativePath] = meta
            current.copy(files = files)
        }
        markTagsDirty()
    }

    private fun markTagsDirty() {
        tagsDirty = true
        schedulePersist()
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            flush()
        }
    }

    private suspend fun read(provider: CryptomatorProvider, file: String): String? =
        provider.download(PathCodec.encode("/$META_DIR/$file")).getOrNull()
            ?.toString(Charsets.UTF_8)

    private suspend fun write(provider: CryptomatorProvider, file: String, payload: String): Boolean {
        provider.mkdir(PathCodec.encode("/"), META_DIR) // conflict = already there
        return provider.upload(
            PathCodec.encode("/$META_DIR"),
            file,
            payload.toByteArray(Charsets.UTF_8),
        ) is FbxResult.Ok
    }

    private suspend fun mkdirs(provider: CryptomatorProvider, displayPath: String) {
        var parent = "/"
        displayPath.trimStart('/').split('/').filter { it.isNotEmpty() }.forEach { segment ->
            provider.mkdir(PathCodec.encode(parent), segment) // conflict = already there
            parent = if (parent == "/") "/$segment" else "$parent/$segment"
        }
    }

    private fun lockedError(): FbxResult.Err =
        FbxResult.Err(FreeboxError.Api(VaultRoutingProvider.ERROR_VAULT_LOCKED))

    private fun typeOf(entry: VaultIndexEntry): TypeFilter = when {
        entry.isVideo || MediaTypes.isVideo(entry.name) -> TypeFilter.VIDEO
        MediaTypes.isPhoto(entry.name) -> TypeFilter.PHOTO
        else -> TypeFilter.OTHER
    }

    companion object {
        const val META_DIR = ".meta"
        const val TRASH_DIR = ".trash"
        const val THUMBS_DIR = ".thumbs"
        const val INDEX_FILE = "index.json"
        const val TAGS_FILE = "tags.json"
        const val TRASH_RETENTION_DAYS = 30L
        private const val MAX_FOLDERS_PER_PASS = 500
        private const val PERSIST_DEBOUNCE_MS = 2_000L
        private const val MUTATION_RECONCILE_DEBOUNCE_MS = 400L

        /**
         * Stable negative pseudo-ids so vault tag NAMES can sit in UI lists
         * built around Room's positive autoincrement ids. Collisions across
         * names are theoretical at vault scale and only cosmetic.
         */
        fun syntheticTagId(name: String): Long =
            -((name.lowercase().hashCode().toLong() and 0x7FFF_FFFFL) + 1_000L)
    }
}
