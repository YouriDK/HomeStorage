package com.boxpix.app.data.db

import androidx.room.Entity

/**
 * Folder the user marked non-deletable from the app: trash, move and rename are
 * refused for it and for anything containing it. App-side guard only — the disk
 * itself is untouched.
 */
@Entity(tableName = "protected_folders", primaryKeys = ["providerId", "pathB64"])
data class ProtectedFolderEntity(
    val providerId: String,
    val pathB64: String,
    val displayPath: String,
)
