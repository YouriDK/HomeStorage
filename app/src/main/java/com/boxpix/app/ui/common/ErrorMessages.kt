package com.boxpix.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.boxpix.app.BuildConfig
import com.boxpix.app.R
import com.boxpix.app.core.FreeboxError

@Composable
fun FreeboxError.message(): String = when (this) {
    FreeboxError.BoxNotFound -> stringResource(R.string.error_box_not_found)
    FreeboxError.PairingDenied -> stringResource(R.string.error_pairing_denied)
    FreeboxError.PairingTimeout -> stringResource(R.string.error_pairing_timeout)
    FreeboxError.NotPaired -> stringResource(R.string.error_api, "not_paired")
    // Debug builds append the exact cause: the gate happens away from adb,
    // so the banner has to be its own diagnostic.
    is FreeboxError.Network -> stringResource(R.string.error_network) + debugDetail()
    is FreeboxError.Http -> stringResource(R.string.error_api, "HTTP $status")
    is FreeboxError.Api -> when (code) {
        "protected_folder" -> stringResource(R.string.error_protected)
        "vault_locked" -> stringResource(R.string.error_vault_locked)
        "no_vault_here" -> stringResource(R.string.error_no_vault_here)
        "vault_upload_too_large" -> stringResource(R.string.error_vault_upload_too_large)
        "vault_cross_boundary" -> stringResource(R.string.error_vault_cross_boundary)
        else -> stringResource(R.string.error_api, code) + debugDetail()
    }
}

private fun FreeboxError.debugDetail(): String {
    if (!BuildConfig.DEBUG) return ""
    val detail = when (this) {
        is FreeboxError.Network -> "${cause.javaClass.simpleName}: ${cause.message}"
        is FreeboxError.Api -> message
        else -> null
    }
    return detail?.let { "\n[$it]" }.orEmpty()
}
