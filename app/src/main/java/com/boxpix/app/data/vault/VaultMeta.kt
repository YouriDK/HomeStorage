package com.boxpix.app.data.vault

import kotlinx.serialization.Serializable

/**
 * The vault's private metadata, stored ENCRYPTED inside the vault itself at
 * `/.meta/` — the in-vault equivalent of Room's media_items + tags, since no
 * vault data may ever touch Room. Paths are vault-relative ("/Holidays/x.jpg").
 */
@Serializable
data class VaultIndexEntry(
    val path: String,
    val name: String,
    val folder: String,
    val sizeBytes: Long,
    val mtime: Long,
    val takenAtEpochSeconds: Long? = null,
    val takenAtManual: Boolean = false,
    val locationText: String? = null,
    val isVideo: Boolean = false,
    val durationSeconds: Long? = null,
    val hasThumb: Boolean = false,
)

@Serializable
data class VaultIndexFile(
    val version: Int = 1,
    val entries: List<VaultIndexEntry> = emptyList(),
)

@Serializable
data class VaultFileMeta(
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
)

@Serializable
data class VaultTagsFile(
    val version: Int = 1,
    /** All tag names ever created in the vault, kept even at zero usage. */
    val tags: List<String> = emptyList(),
    val files: Map<String, VaultFileMeta> = emptyMap(),
)
