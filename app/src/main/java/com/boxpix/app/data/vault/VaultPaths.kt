package com.boxpix.app.data.vault

/**
 * The vault's virtual namespace: cleartext vault paths are exposed to the UI
 * under `<disk root>/.vault/...` (display form). Real listings can never
 * produce such paths — the box strips dot-entries and the physical layout
 * lives under `d/` — so this test is the single source of truth for "is this
 * vault data", used by every guard that keeps vault content out of Room and
 * out of the clear mirrors.
 */
object VaultPaths {

    private const val MOUNT_SEGMENT = "/${VaultFormat.VAULT_DIR}"

    fun isVaultPath(displayPath: String): Boolean =
        displayPath.endsWith(MOUNT_SEGMENT) || displayPath.contains("$MOUNT_SEGMENT/")

    /** "/inside" for `<mount>/inside`, "/" for the mount itself, null when outside. */
    fun vaultRelative(displayPath: String, mountDisplay: String): String? = when {
        displayPath == mountDisplay -> "/"
        displayPath.startsWith("$mountDisplay/") -> displayPath.removePrefix(mountDisplay)
        else -> null
    }
}
