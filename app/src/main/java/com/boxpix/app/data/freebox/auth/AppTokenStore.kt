package com.boxpix.app.data.freebox.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app_token obtained at pairing, encrypted at rest with a Keystore-held key.
 * Prefs are opened lazily (first open does disk I/O) — only touch this from a
 * background dispatcher.
 */
@Singleton
class AppTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "boxpix_secure",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var appToken: String?
        get() = prefs.getString(KEY_APP_TOKEN, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_APP_TOKEN) else putString(KEY_APP_TOKEN, value)
            }.apply()
        }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_APP_TOKEN = "app_token"
    }
}
