package com.boxpix.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxpix.app.data.prefs.UiPrefsStore
import com.boxpix.app.ui.BoxpixRoot
import com.boxpix.app.ui.lock.AppLockGate
import com.boxpix.app.ui.theme.BoxpixTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// FragmentActivity (not ComponentActivity): BiometricPrompt requires it.
@AndroidEntryPoint
class MainActivity : androidx.fragment.app.FragmentActivity() {

    @Inject
    lateinit var uiPrefs: UiPrefsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by uiPrefs.themeMode
                .collectAsStateWithLifecycle(initialValue = UiPrefsStore.THEME_SYSTEM)
            val accentKey by uiPrefs.accentPreset
                .collectAsStateWithLifecycle(initialValue = UiPrefsStore.ACCENT_DEFAULT)
            val lockEnabled by uiPrefs.appLockEnabled
                .collectAsStateWithLifecycle(initialValue = false)

            val darkTheme = when (themeMode) {
                UiPrefsStore.THEME_LIGHT -> false
                UiPrefsStore.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            BoxpixTheme(darkTheme = darkTheme, accentKey = accentKey) {
                AppLockGate(lockEnabled = lockEnabled) {
                    BoxpixRoot()
                }
            }
        }
    }
}
