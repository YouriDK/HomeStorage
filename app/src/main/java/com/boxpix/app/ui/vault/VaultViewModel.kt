package com.boxpix.app.ui.vault

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.boxpix.app.data.vault.UnlockResult
import com.boxpix.app.data.vault.VaultKeyStore
import com.boxpix.app.data.vault.VaultSession
import com.boxpix.app.data.vault.VaultState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * Drives the unlock sheet and the vault banner. All crypto work stays in
 * VaultSession (off the main thread); this only orchestrates UI state and the
 * two BiometricPrompt flows (wrap after a passphrase unlock, unwrap on later
 * unlocks). The sheet owns the actual prompt — it needs the activity.
 */
@HiltViewModel
class VaultViewModel @Inject constructor(
    private val session: VaultSession,
    private val keyStore: VaultKeyStore,
    @ApplicationContext context: Context,
) : ViewModel() {

    enum class UnlockError { WRONG_PASSPHRASE, UNSUPPORTED, UNREACHABLE, BIOMETRIC_INVALIDATED }

    data class SheetUi(
        val visible: Boolean = false,
        val unlocking: Boolean = false,
        /** Success morph is playing; the sheet dismisses itself right after. */
        val justUnlocked: Boolean = false,
        val error: UnlockError? = null,
        /** Bumped on every failed passphrase so the field can shake again. */
        val shakeNonce: Int = 0,
        /** Device has enrolled biometrics: the remember switch is offered. */
        val biometricsAvailable: Boolean = false,
        val rememberChecked: Boolean = false,
        /** A wrapped key is stored: the sheet auto-opens the biometric prompt. */
        val biometricUnlockReady: Boolean = false,
    )

    val vaultState: StateFlow<VaultState> = session.state

    private val biometricsAvailable =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    private val _sheet = MutableStateFlow(SheetUi())
    val sheet: StateFlow<SheetUi> = _sheet.asStateFlow()

    private val _settingsProbeFailed = MutableStateFlow(false)
    val settingsProbeFailed: StateFlow<Boolean> = _settingsProbeFailed.asStateFlow()

    /**
     * The Settings entry point: probes `<configured root>/.vault` on demand,
     * then opens the unlock sheet — or reports that nothing is there.
     */
    fun openVaultFromSettings() {
        viewModelScope.launch {
            _settingsProbeFailed.value = false
            when (session.probe()) {
                VaultState.Unlocked -> Unit // already open; the Explorer banner leads back in
                VaultState.Locked -> openSheet()
                else -> _settingsProbeFailed.value = true
            }
        }
    }

    fun openSheet() {
        _sheet.value = SheetUi(
            visible = true,
            biometricsAvailable = biometricsAvailable,
            biometricUnlockReady = biometricsAvailable && keyStore.isRemembered,
        )
    }

    fun dismissSheet() = _sheet.update { it.copy(visible = false) }

    fun setRemember(checked: Boolean) = _sheet.update { it.copy(rememberChecked = checked) }

    /**
     * Passphrase unlock. On success the caller may still have to run the wrap
     * prompt ([pendingWrapCipher] non-null when remember was ticked).
     */
    fun unlockWithPassphrase(passphrase: String) {
        if (_sheet.value.unlocking || passphrase.isEmpty()) return
        _sheet.update { it.copy(unlocking = true, error = null) }
        viewModelScope.launch {
            val remember = _sheet.value.rememberChecked && biometricsAvailable
            when (session.unlock(passphrase, retainRawKey = remember)) {
                UnlockResult.Success -> _sheet.update { it.copy(unlocking = false, justUnlocked = true) }
                UnlockResult.WrongPassphrase -> failed(UnlockError.WRONG_PASSPHRASE, shake = true)
                is UnlockResult.UnsupportedVault -> failed(UnlockError.UNSUPPORTED)
                is UnlockResult.Failed -> failed(UnlockError.UNREACHABLE)
            }
        }
    }

    /** Cipher for the wrap prompt, or null when nothing was retained. */
    fun pendingWrapCipher(): Cipher? {
        if (!_sheet.value.rememberChecked) return null
        return runCatching { keyStore.encryptCipher() }.getOrNull()
    }

    /** The wrap prompt succeeded: persist the wrapped masterkey. */
    fun onWrapAuthenticated(cipher: Cipher) {
        val raw = session.takeRawKeyForWrapping() ?: return
        runCatching { keyStore.store(cipher, raw) }
    }

    /** The wrap prompt was cancelled: drop the retained key, stay unlocked. */
    fun onWrapAbandoned() {
        session.takeRawKeyForWrapping()?.fill(0)
    }

    /** Cipher for the unwrap prompt; updates state when the blob is unusable. */
    fun beginBiometricUnlock(): Cipher? = when (val remembered = keyStore.beginBiometricUnlock()) {
        is VaultKeyStore.Remembered.Ready -> remembered.cipher
        VaultKeyStore.Remembered.Invalidated -> {
            _sheet.update {
                it.copy(biometricUnlockReady = false, error = UnlockError.BIOMETRIC_INVALIDATED)
            }
            null
        }
        VaultKeyStore.Remembered.None -> {
            _sheet.update { it.copy(biometricUnlockReady = false) }
            null
        }
    }

    /** The unwrap prompt succeeded: unlock from the raw key. */
    fun onBiometricAuthenticated(cipher: Cipher) {
        val raw = keyStore.unwrap(cipher)
        if (raw == null) {
            keyStore.forget()
            _sheet.update { it.copy(biometricUnlockReady = false, error = UnlockError.BIOMETRIC_INVALIDATED) }
            return
        }
        _sheet.update { it.copy(unlocking = true, error = null) }
        viewModelScope.launch {
            when (session.unlockWithRawKey(raw)) {
                UnlockResult.Success -> _sheet.update { it.copy(unlocking = false, justUnlocked = true) }
                else -> {
                    // A stored key that stopped working is stale: forget it.
                    keyStore.forget()
                    failed(UnlockError.UNREACHABLE)
                    _sheet.update { it.copy(biometricUnlockReady = false) }
                }
            }
        }
    }

    /** The unwrap prompt was cancelled: fall back to the passphrase field. */
    fun onBiometricAbandoned() = _sheet.update { it.copy(biometricUnlockReady = false) }

    fun lock() {
        viewModelScope.launch { session.lock() }
    }

    private fun failed(error: UnlockError, shake: Boolean = false) = _sheet.update {
        it.copy(
            unlocking = false,
            error = error,
            shakeNonce = if (shake) it.shakeNonce + 1 else it.shakeNonce,
        )
    }
}
