package com.boxpix.app.data.db

import androidx.sqlite.db.SimpleSQLiteQuery

/** Combinable filters (SPEC §2): name, tags (AND, pre-resolved to paths), dates, folder. */
object SearchQueryBuilder {

    fun build(
        providerId: String,
        nameContains: String?,
        fromEpochSeconds: Long?,
        toEpochSeconds: Long?,
        folderPrefix: String?,
        pathsWithAllTags: List<String>?,
    ): SimpleSQLiteQuery {
        val sql = StringBuilder("SELECT * FROM media_items WHERE providerId = ?")
        val args = mutableListOf<Any>(providerId)

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
