package com.boxpix.app.data.db

import androidx.sqlite.db.SimpleSQLiteQuery

/** Combinable filters (SPEC §2): name, tags (AND, pre-resolved to paths), dates, folder, type. */
object SearchQueryBuilder {

    enum class TypeFilter { PHOTO, VIDEO, OTHER }

    fun build(
        providerId: String,
        rootDisplayPath: String,
        nameContains: String?,
        fromEpochSeconds: Long?,
        toEpochSeconds: Long?,
        folderPrefix: String?,
        pathsWithAllTags: List<String>?,
        types: Set<TypeFilter> = emptySet(),
    ): SimpleSQLiteQuery {
        val sql = StringBuilder("SELECT * FROM media_items WHERE providerId = ?")
        val args = mutableListOf<Any>(providerId)

        // The index accumulates every root ever scanned (disks are swapped on the
        // box); results must never cross the disk currently browsed.
        val root = rootDisplayPath.trimEnd('/')
        if (root.isNotEmpty()) {
            sql.append(" AND (folderDisplayPath = ? OR folderDisplayPath LIKE ?)")
            args += root
            args += "$root/%"
        }

        if (!nameContains.isNullOrBlank()) {
            sql.append(" AND name LIKE ?")
            args += "%${nameContains.trim()}%"
        }
        if (fromEpochSeconds != null) {
            sql.append(" AND COALESCE(takenAtEpochSeconds, mtime) >= ?")
            args += fromEpochSeconds
        }
        if (toEpochSeconds != null) {
            sql.append(" AND COALESCE(takenAtEpochSeconds, mtime) <= ?")
            args += toEpochSeconds
        }
        if (!folderPrefix.isNullOrBlank()) {
            sql.append(" AND (folderDisplayPath = ? OR folderDisplayPath LIKE ?)")
            args += folderPrefix
            args += "$folderPrefix/%"
        }
        if (types.isNotEmpty() && types.size < TypeFilter.entries.size) {
            val clauses = types.map { type ->
                when (type) {
                    TypeFilter.PHOTO -> "mimeType LIKE 'image/%'"
                    TypeFilter.VIDEO -> "isVideo = 1"
                    TypeFilter.OTHER ->
                        "(isVideo = 0 AND (mimeType IS NULL OR mimeType NOT LIKE 'image/%'))"
                }
            }
            sql.append(" AND (${clauses.joinToString(" OR ")})")
        }
        if (pathsWithAllTags != null) {
            if (pathsWithAllTags.isEmpty()) {
                sql.append(" AND 0") // tags selected but nothing carries them all
            } else {
                sql.append(
                    " AND pathB64 IN (${pathsWithAllTags.joinToString(",") { "?" }})",
                )
                args.addAll(pathsWithAllTags)
            }
        }
        sql.append(" ORDER BY COALESCE(takenAtEpochSeconds, mtime) DESC")
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }
}
