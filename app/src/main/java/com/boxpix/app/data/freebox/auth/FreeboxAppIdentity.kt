package com.boxpix.app.data.freebox.auth

import android.os.Build
import com.boxpix.app.BuildConfig

/** Identity sent to the box at pairing and login (shown on the Freebox display). */
object FreeboxAppIdentity {
    const val APP_ID = "com.boxpix.app"
    const val APP_NAME = "Boxpix"
    val APP_VERSION: String = BuildConfig.VERSION_NAME
    val DEVICE_NAME: String get() = Build.MODEL.ifBlank { "Android phone" }
}
