package com.boxpix.app.data.tags

import com.boxpix.app.data.db.MediaTagEntity
import com.boxpix.app.data.db.TagDao
import com.boxpix.app.data.db.TagEntity
import com.boxpix.app.data.db.TagWithCount
import com.boxpix.app.data.db.WorkQueueDao
import com.boxpix.app.data.db.WorkQueueEntity
import com.boxpix.app.data.prefs.DeviceIdentity
import com.boxpix.app.data.storage.StorageEnv
import com.boxpix.app.data.trash.TrashRepository
import com.boxpix.app.ui.viewer.MediaRef
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tags per SPEC §2/§4: instant in Room, then write-through — journal export to
 * /.meta on every mutation, and an XMP job per touched JPEG (spike contract;
 * embedded XMP is additive-only in V1: keywords are appended, never removed —
 * Room + tags.json are the source of truth, XMP the portability net).
 * Favourites = the pinned system tag, kept out of XMP.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TagRepository @Inject constructor(
    private val tagDao: TagDao,
    private val queueDao: WorkQueueDao,
    private val env: StorageEnv,
    private val deviceIdentity: DeviceIdentity,
    private val clock: Clock,
    private val journal: Lazy<TagsJournal>,
    private val scope: CoroutineScope,
) {

    val tags: Flow<List<TagWithCount>> =
        env.useFakeProvider.flatMapLatest { tagDao.tagsWithCounts(providerId(it)) }

    val favoritePaths: Flow<List<String>> =
        env.useFakeProvider.flatMapLatest { useFake ->
            val pid = providerId(useFake)
            kotlinx.coroutines.flow.flow {
                val favorites = ensureFavorites(pid)
                emitAll(tagDao.pathsForTag(pid, favorites.id))
            }
        }

    private val _exportConflict = MutableStateFlow<TagsJournal.ExportOutcome.Conflict?>(null)
    val exportConflict: StateFlow<TagsJournal.ExportOutcome.Conflict?> = _exportConflict

    private val pendingActions = mutableListOf<JournalAction>()
    private val actionsMutex = Mutex()
    private var exportJob: Job? = null

    fun tagIdsFor(pathB64: String): Flow<List<Long>> =
        env.useFakeProvider.flatMapLatest { tagDao.tagIdsForMediaFlow(providerId(it), pathB64) }

    suspend fun createTag(name: String): TagEntity? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val pid = currentProviderId()
        tagDao.byName(pid, trimmed)?.let { return it }
        tagDao.insert(TagEntity(providerId = pid, name = trimmed, pinned = false, isSystem = false))
        val created = tagDao.byName(pid, trimmed) ?: return null
        record("tag_create", tag = created.name)
        scheduleExport()
        return created
    }

    suspend fun addTag(media: MediaRef, tagId: Long) {
        val pid = currentProviderId()
        val tag = tagDao.byId(tagId) ?: return
        tagDao.link(
            MediaTagEntity(
                providerId = pid,
                pathB64 = media.pathB64,
                displayPath = media.displayPath,
                tagId = tagId,
                taggedAtEpochSeconds = clock.instant().epochSecond,
                deviceId = deviceIdentity.get(),
            ),
        )
        record("tag_add", path = media.displayPath, tag = tag.name)
        if (!tag.isSystem) enqueueXmp(pid, media)
        scheduleExport()
    }

    suspend fun removeTag(media: MediaRef, tagId: Long) {
        val pid = currentProviderId()
        val tag = tagDao.byId(tagId) ?: return
        tagDao.unlink(pid, media.pathB64, tagId)
        record("tag_remove", path = media.displayPath, tag = tag.name)
        // Additive-only XMP: no rewrite on removal (Room/tags.json are the truth).
        scheduleExport()
    }

    suspend fun toggleFavorite(media: MediaRef): Boolean {
        val pid = currentProviderId()
        val favorites = ensureFavorites(pid)
        val has = tagDao.tagIdsForMedia(pid, media.pathB64).contains(favorites.id)
        if (has) removeTag(media, favorites.id) else addTag(media, favorites.id)
        return !has
    }

    suspend fun setPinned(tagId: Long, pinned: Boolean) {
        tagDao.setPinned(tagId, pinned)
        tagDao.byId(tagId)?.let { record(if (pinned) "tag_pin" else "tag_unpin", tag = it.name) }
        scheduleExport()
    }

    /** Non-system tag names of a media — what the XMP job writes as dc:subject. */
    suspend fun keywordsForMedia(providerId: String, pathB64: String): List<String> {
        val ids = tagDao.tagIdsForMedia(providerId, pathB64)
        return ids.mapNotNull { tagDao.byId(it) }.filterNot { it.isSystem }.map { it.name }
    }

    /** SPEC §4: a move/rename through the app remaps tagged paths atomically. */
    suspend fun remapPath(oldPathB64: String, newPathB64: String, newDisplayPath: String) {
        tagDao.remapPath(currentProviderId(), oldPathB64, newPathB64, newDisplayPath)
    }

    suspend fun ensureFavorites(providerId: String): TagEntity {
        tagDao.byName(providerId, FAVORITES)?.let { return it }
        tagDao.insert(TagEntity(providerId = providerId, name = FAVORITES, pinned = true, isSystem = true))
        return tagDao.byName(providerId, FAVORITES)!!
    }

    fun confirmConflictedExport() {
        _exportConflict.value = null
        scheduleExport(force = true, debounceMs = 0)
    }

    fun dismissConflict() {
        _exportConflict.value = null
    }

    private suspend fun enqueueXmp(providerId: String, media: MediaRef) {
        if (media.mimeType != "image/jpeg") return // spike verdict: JPEG only
        queueDao.upsert(
            WorkQueueEntity(
                providerId = providerId,
                type = WorkQueueEntity.TYPE_XMP,
                pathB64 = media.pathB64,
                displayPath = media.displayPath,
                enqueuedMtime = media.mtime,
                status = WorkQueueEntity.STATUS_PENDING,
                attempts = 0,
                lastError = null,
            ),
        )
    }

    private suspend fun record(action: String, path: String? = null, tag: String? = null) {
        actionsMutex.withLock {
            pendingActions += JournalAction(
                atEpochSeconds = clock.instant().epochSecond,
                device = deviceIdentity.get(),
                action = action,
                path = path,
                tag = tag,
            )
        }
    }

    /** Debounced snapshot export — "light and immediate" without one write per tap. */
    private fun scheduleExport(force: Boolean = false, debounceMs: Long = EXPORT_DEBOUNCE_MS) {
        exportJob?.cancel()
        exportJob = scope.launch {
            delay(debounceMs)
            val pid = currentProviderId()
            val snapshot = buildSnapshot(pid)
            val actions = actionsMutex.withLock {
                pendingActions.toList().also { pendingActions.clear() }
            }
            when (val outcome = journal.get().export(snapshot, actions, force)) {
                is TagsJournal.ExportOutcome.Conflict -> {
                    actionsMutex.withLock { pendingActions.addAll(0, actions) }
                    _exportConflict.value = outcome
                }
                is TagsJournal.ExportOutcome.Failed ->
                    actionsMutex.withLock { pendingActions.addAll(0, actions) } // next mutation retries
                TagsJournal.ExportOutcome.Done -> Unit
            }
        }
    }

    private suspend fun buildSnapshot(providerId: String): TagsSnapshot {
        val allTags = tagDao.all(providerId).associateBy { it.id }
        val links = tagDao.allLinks(providerId)
        return TagsSnapshot(
            updatedAtEpochSeconds = clock.instant().epochSecond,
            device = deviceIdentity.get(),
            tags = allTags.values
                .filterNot { it.isSystem }
                .sortedBy { it.name.lowercase() }
                .map { TagsSnapshot.SnapshotTag(it.name, it.pinned) },
            media = links.groupBy { it.displayPath }
                .map { (path, pathLinks) ->
                    TagsSnapshot.SnapshotMedia(
                        path = path,
                        tags = pathLinks.mapNotNull { allTags[it.tagId]?.name }.sorted(),
                    )
                }
                .sortedBy { it.path },
        )
    }

    private suspend fun currentProviderId(): String = providerId(env.useFakeProvider.first())

    private fun providerId(useFake: Boolean): String =
        if (useFake) TrashRepository.PROVIDER_FAKE else TrashRepository.PROVIDER_FREEBOX

    companion object {
        const val FAVORITES = "Favourites"
        private const val EXPORT_DEBOUNCE_MS = 2_000L
    }
}
