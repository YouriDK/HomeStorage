package com.boxpix.app.support

import com.boxpix.app.data.db.ExcludedFolderDao
import com.boxpix.app.data.db.ExcludedFolderEntity
import com.boxpix.app.data.db.MediaDao
import com.boxpix.app.data.db.MediaItemEntity
import com.boxpix.app.data.db.MediaTagEntity
import com.boxpix.app.data.db.ProtectedFolderDao
import com.boxpix.app.data.db.ProtectedFolderEntity
import com.boxpix.app.data.db.TagDao
import com.boxpix.app.data.db.TagEntity
import com.boxpix.app.data.db.TagWithCount
import com.boxpix.app.data.db.WorkQueueDao
import com.boxpix.app.data.db.WorkQueueEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.Base64

object TestSupport {
    /** A real 8x8 JPEG (with a small EXIF block), for JVM-side container tests. */
    val TINY_JPEG: ByteArray = Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAQAASABIAAD/4QBMRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQAAAABAAAACKADAAQAAAABAAAACAAAAAD/7QA4UGhvdG9zaG9wIDMuMAA4QklNBAQAAAAAAAA4QklNBCUAAAAAABDUHYzZjwCyBOmACZjs+EJ+/8AAEQgACAAIAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQHBQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/bAEMAAgICAgICAwICAwUDAwMFBgUFBQUGCAYGBgYGCAoICAgICAgKCgoKCgoKCgwMDAwMDA4ODg4ODw8PDw8PDw8PD//bAEMBAgICBAQEBwQEBxALCQsQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEP/dAAQAAf/aAAwDAQACEQMRAD8A+L6KKK/lM/38P//Z",
    )
}

class InMemoryMediaDao : MediaDao {
    val store = MutableStateFlow<Map<Pair<String, String>, MediaItemEntity>>(emptyMap())

    override suspend fun upsert(items: List<MediaItemEntity>) {
        store.value = store.value + items.associateBy { it.providerId to it.pathB64 }
    }

    override suspend fun folderItems(providerId: String, folder: String) =
        store.value.values.filter { it.providerId == providerId && it.folderDisplayPath == folder }

    override suspend fun deleteFolderRowsNotIn(providerId: String, folder: String, keepPathsB64: List<String>) {
        store.value = store.value.filterValues {
            !(it.providerId == providerId && it.folderDisplayPath == folder && it.pathB64 !in keepPathsB64)
        }
    }

    override suspend fun setHasThumb(providerId: String, pathB64: String, hasThumb: Boolean) {
        store.value[providerId to pathB64]?.let {
            store.value = store.value + ((providerId to pathB64) to it.copy(hasThumb = hasThumb))
        }
    }

    override suspend fun setTakenAtFromExif(providerId: String, pathB64: String, takenAt: Long?) {
        store.value[providerId to pathB64]?.let {
            if (it.takenAtManual) return
            store.value = store.value + ((providerId to pathB64) to it.copy(takenAtEpochSeconds = takenAt))
        }
    }

    override suspend fun setManualTakenAt(providerId: String, pathB64: String, takenAt: Long) {
        store.value[providerId to pathB64]?.let {
            store.value = store.value +
                ((providerId to pathB64) to it.copy(takenAtEpochSeconds = takenAt, takenAtManual = true))
        }
    }

    override suspend fun setLocation(providerId: String, pathB64: String, location: String?) {
        store.value[providerId to pathB64]?.let {
            store.value = store.value + ((providerId to pathB64) to it.copy(locationText = location))
        }
    }

    override suspend fun setMtime(providerId: String, pathB64: String, mtime: Long) {
        store.value[providerId to pathB64]?.let {
            store.value = store.value + ((providerId to pathB64) to it.copy(mtime = mtime))
        }
    }

    override suspend fun setDuration(providerId: String, pathB64: String, durationSeconds: Long?) {
        store.value[providerId to pathB64]?.let {
            store.value = store.value + ((providerId to pathB64) to it.copy(durationSeconds = durationSeconds))
        }
    }

    override fun count(providerId: String) =
        store.map { map -> map.values.count { it.providerId == providerId } }

    override fun byCaptureDate(providerId: String) =
        store.map { map ->
            map.values.filter { it.providerId == providerId }
                .sortedByDescending { it.takenAtEpochSeconds ?: it.mtime }
        }

    override suspend fun byPath(providerId: String, pathB64: String) =
        store.value[providerId to pathB64]

    override suspend fun all(providerId: String) =
        store.value.values.filter { it.providerId == providerId }

    fun allRows() = store.value.values.toList()
}

class InMemoryWorkQueueDao : WorkQueueDao {
    val store = MutableStateFlow<Map<Triple<String, String, String>, WorkQueueEntity>>(emptyMap())

    override suspend fun upsert(job: WorkQueueEntity) {
        store.value = store.value + (Triple(job.providerId, job.type, job.pathB64) to job)
    }

    override suspend fun pending(providerId: String, type: String, limit: Int) =
        store.value.values
            .filter { it.providerId == providerId && it.type == type && it.status == WorkQueueEntity.STATUS_PENDING }
            .take(limit)

    override suspend fun find(providerId: String, type: String, pathB64: String) =
        store.value[Triple(providerId, type, pathB64)]

    override fun pendingCount(providerId: String) =
        store.map { map ->
            map.values.count { it.providerId == providerId && it.status == WorkQueueEntity.STATUS_PENDING }
        }

    override fun pendingCountByType(providerId: String, type: String) =
        store.map { map ->
            map.values.count {
                it.providerId == providerId && it.type == type && it.status == WorkQueueEntity.STATUS_PENDING
            }
        }

    override fun failedCountByType(providerId: String, type: String) =
        store.map { map ->
            map.values.count {
                it.providerId == providerId && it.type == type && it.status == WorkQueueEntity.STATUS_FAILED
            }
        }

    override suspend fun retryFailed(providerId: String) {
        store.value = store.value.mapValues { (_, job) ->
            if (job.providerId == providerId && job.status == WorkQueueEntity.STATUS_FAILED) {
                job.copy(status = WorkQueueEntity.STATUS_PENDING, attempts = 0, lastError = null)
            } else {
                job
            }
        }
    }

    override suspend fun retryFailedByType(providerId: String, type: String) {
        store.value = store.value.mapValues { (_, job) ->
            if (job.providerId == providerId && job.type == type && job.status == WorkQueueEntity.STATUS_FAILED) {
                job.copy(status = WorkQueueEntity.STATUS_PENDING, attempts = 0, lastError = null)
            } else {
                job
            }
        }
    }

    override suspend fun deleteForPath(providerId: String, pathB64: String) {
        store.value = store.value.filterKeys { !(it.first == providerId && it.third == pathB64) }
    }

    fun allJobs() = store.value.values.toList()
}

class InMemoryProtectedFolderDao : ProtectedFolderDao {
    val store = MutableStateFlow<List<ProtectedFolderEntity>>(emptyList())

    override fun all(providerId: String) =
        store.map { list -> list.filter { it.providerId == providerId } }

    override suspend fun snapshot(providerId: String) =
        store.value.filter { it.providerId == providerId }

    override suspend fun insert(folder: ProtectedFolderEntity) {
        store.value = store.value.filterNot {
            it.providerId == folder.providerId && it.pathB64 == folder.pathB64
        } + folder
    }

    override suspend fun delete(providerId: String, pathB64: String) {
        store.value = store.value.filterNot { it.providerId == providerId && it.pathB64 == pathB64 }
    }

    override suspend fun clear(providerId: String) {
        store.value = store.value.filterNot { it.providerId == providerId }
    }
}

class InMemoryExcludedFolderDao : ExcludedFolderDao {
    val store = MutableStateFlow<List<ExcludedFolderEntity>>(emptyList())

    override fun all(providerId: String) =
        store.map { list -> list.filter { it.providerId == providerId } }

    override suspend fun snapshot(providerId: String) =
        store.value.filter { it.providerId == providerId }

    override suspend fun insert(folder: ExcludedFolderEntity) {
        store.value = store.value.filterNot {
            it.providerId == folder.providerId && it.pathB64 == folder.pathB64
        } + folder
    }

    override suspend fun delete(providerId: String, pathB64: String) {
        store.value = store.value.filterNot { it.providerId == providerId && it.pathB64 == pathB64 }
    }

    override suspend fun clear(providerId: String) {
        store.value = store.value.filterNot { it.providerId == providerId }
    }
}

class InMemoryTagDao : TagDao {
    private var nextId = 1L
    val tags = MutableStateFlow<Map<Long, TagEntity>>(emptyMap())
    val links = MutableStateFlow<List<MediaTagEntity>>(emptyList())

    override fun tagsWithCounts(providerId: String) =
        tags.map { map ->
            map.values.filter { it.providerId == providerId }.map { tag ->
                TagWithCount(
                    id = tag.id,
                    name = tag.name,
                    pinned = tag.pinned,
                    isSystem = tag.isSystem,
                    usageCount = links.value.count { it.tagId == tag.id },
                )
            }.sortedWith(compareByDescending<TagWithCount> { it.usageCount }.thenBy { it.name.lowercase() })
        }

    override suspend fun byName(providerId: String, name: String) =
        tags.value.values.firstOrNull { it.providerId == providerId && it.name.equals(name, ignoreCase = true) }

    override suspend fun byId(id: Long) = tags.value[id]

    override suspend fun all(providerId: String) =
        tags.value.values.filter { it.providerId == providerId }

    override suspend fun insert(tag: TagEntity): Long {
        if (byName(tag.providerId, tag.name) != null) return -1
        val id = nextId++
        tags.value = tags.value + (id to tag.copy(id = id))
        return id
    }

    override suspend fun setPinned(id: Long, pinned: Boolean) {
        tags.value[id]?.let { tags.value = tags.value + (id to it.copy(pinned = pinned)) }
    }

    override suspend fun rename(id: Long, name: String) {
        tags.value[id]?.let { tags.value = tags.value + (id to it.copy(name = name)) }
    }

    override suspend fun deleteTag(id: Long) {
        tags.value = tags.value - id
    }

    override suspend fun deleteLinksFor(tagId: Long) {
        links.value = links.value.filterNot { it.tagId == tagId }
    }

    override suspend fun linksFor(tagId: Long) =
        links.value.filter { it.tagId == tagId }

    override suspend fun link(link: MediaTagEntity) {
        links.value = links.value.filterNot {
            it.providerId == link.providerId && it.pathB64 == link.pathB64 && it.tagId == link.tagId
        } + link
    }

    override suspend fun unlink(providerId: String, pathB64: String, tagId: Long) {
        links.value = links.value.filterNot {
            it.providerId == providerId && it.pathB64 == pathB64 && it.tagId == tagId
        }
    }

    override suspend fun tagIdsForMedia(providerId: String, pathB64: String) =
        links.value.filter { it.providerId == providerId && it.pathB64 == pathB64 }.map { it.tagId }

    override fun tagIdsForMediaFlow(providerId: String, pathB64: String) =
        links.map { list ->
            list.filter { it.providerId == providerId && it.pathB64 == pathB64 }.map { it.tagId }
        }

    override suspend fun allLinks(providerId: String) =
        links.value.filter { it.providerId == providerId }

    override fun pathsForTag(providerId: String, tagId: Long) =
        links.map { list ->
            list.filter { it.providerId == providerId && it.tagId == tagId }.map { it.pathB64 }.distinct()
        }

    override suspend fun pathsWithAllTags(providerId: String, tagIds: List<Long>, tagCount: Int) =
        links.value.filter { it.providerId == providerId && it.tagId in tagIds }
            .groupBy { it.pathB64 }
            .filterValues { group -> group.map { it.tagId }.distinct().size == tagCount }
            .keys.toList()

    override suspend fun remapPath(providerId: String, oldPathB64: String, newPathB64: String, newDisplayPath: String) {
        links.value = links.value.map {
            if (it.providerId == providerId && it.pathB64 == oldPathB64) {
                it.copy(pathB64 = newPathB64, displayPath = newDisplayPath)
            } else {
                it
            }
        }
    }
}
