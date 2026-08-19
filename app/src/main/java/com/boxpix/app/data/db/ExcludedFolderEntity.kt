package com.boxpix.app.data.db

import androidx.room.Entity

/** Folder subtree the reconciler never scans (V1 feedback), per provider. */
@Entity(tableName = "excluded_folders", primaryKeys = ["providerId", "pathB64"])
data class ExcludedFolderEntity(
    val providerId: String,
    val pathB64: String,
    val displayPath: String,
)
