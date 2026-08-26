package com.boxpix.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxpix.app.data.prefs.UiPrefsStore
import com.boxpix.app.data.vault.VaultAutoLock
import com.boxpix.app.ui.BoxpixRoot
import com.boxpix.app.ui.lock.AppLockGate
import com.boxpix.app.ui.theme.BoxpixTheme
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

// FragmentActivity (not ComponentActivity): BiometricPrompt requires it.
@AndroidEntryPoint
class MainActivity : androidx.fragment.app.FragmentActivity() {

    @Inject
    lateinit var uiPrefs: UiPrefsStore

    @Inject
    lateinit var vaultAutoLock: VaultAutoLock

    // Mirrors the preference so onStop never blocks on DataStore.
    @Volatile
    private var appLockEnabled = false

    // The vault locks with the app lock, or after a prolonged background stay.
    // Activity callbacks (not a composable effect): configChanges keeps this
    // activity alive across rotations, so onStop really means "left the app".
    override fun onStop() {
        super.onStop()
        vaultAutoLock.onAppStopped(appLockEnabled)
    }

    override fun onStart() {
        super.onStart()
        if (::vaultAutoLock.isInitialized) vaultAutoLock.onAppStarted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            uiPrefs.appLockEnabled.collect { appLockEnabled = it }
        }
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
