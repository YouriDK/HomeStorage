package com.boxpix.app.data.vault

import com.boxpix.app.core.FbxResult
import com.boxpix.app.core.FreeboxError
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.RootLocator
import com.boxpix.app.data.storage.StorageProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.cryptomator.cryptolib.api.Cryptor
import org.cryptomator.cryptolib.api.CryptorProvider as CryptolibProvider
import org.cryptomator.cryptolib.api.InvalidPassphraseException
import org.cryptomator.cryptolib.api.Masterkey
import org.cryptomator.cryptolib.common.MasterkeyFileAccess
import java.security.SecureRandom

sealed interface VaultState {
    /** No vault on this disk (or none probed yet): no vault code wakes up. */
    data object NoVault : VaultState
    data object Locked : VaultState
    data object Unlocking : VaultState
    data object Unlocked : VaultState
}

sealed interface UnlockResult {
    data object Success : UnlockResult
    data object WrongPassphrase : UnlockResult
    data class UnsupportedVault(val reason: String) : UnlockResult
    data class Failed(val error: FreeboxError) : UnlockResult
}

/**
 * Lifecycle of the single vault: discovery probe, unlock (scrypt unwrap, off
 * the main thread by construction — callers stay on any thread, the heavy
 * work runs on [cryptoDispatcher]), and lock. The masterkey material lives in
 * process memory only and is destroyed on lock; nothing about the vault is
 * ever written outside the vault itself.
 *
 * Discovery cannot rely on fs/ls (the box strips dot-entries via removeHidden)
 * so [probe] downloads `<disk root>/.vault/vault.cryptomator` directly. It is
 * meant to run when the disk is mounted and on manual resync — not per screen.
 */
class VaultSession(
    private val inner: StorageProvider,
    private val rootLocator: RootLocator,
    private val cryptoDispatcher: CoroutineDispatcher,
) {

    private val _state = MutableStateFlow<VaultState>(VaultState.NoVault)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    /** Non-null exactly while [state] is Unlocked. The UI navigates through it. */
    @Volatile
    var provider: CryptomatorProvider? = null
        private set

    private val transition = Mutex()
    private var cryptor: Cryptor? = null
    private var vaultRootDisplay: String? = null
    private var configJwt: String? = null
    private val csprng = SecureRandom()

    /**
     * Looks for `<disk root>/.vault/vault.cryptomator`. Present -> Locked
     * (unless already unlocked); absent or failing -> NoVault, fail-closed.
     */
    suspend fun probe(): VaultState = transition.withLock {
        if (_state.value == VaultState.Unlocked) return@withLock _state.value
        val rootB64 = rootLocator.rootPathB64() ?: return@withLock settle(VaultState.NoVault)
        val rootDisplay = runCatching { PathCodec.decode(rootB64) }.getOrNull()
            ?: return@withLock settle(VaultState.NoVault)
        val vaultRoot = "${rootDisplay.trimEnd('/')}/${VaultFormat.VAULT_DIR}"
        val config = inner.download(PathCodec.encode("$vaultRoot/${VaultFormat.CONFIG_FILE}"))
        when (config) {
            is FbxResult.Ok -> {
                vaultRootDisplay = vaultRoot
                configJwt = String(config.value, Charsets.UTF_8)
                settle(VaultState.Locked)
            }
            is FbxResult.Err -> settle(VaultState.NoVault)
        }
    }

    suspend fun unlock(passphrase: CharSequence): UnlockResult {
        transition.withLock {
            when (_state.value) {
                VaultState.Locked -> _state.value = VaultState.Unlocking
                VaultState.Unlocked -> return UnlockResult.Success
                else -> return UnlockResult.Failed(FreeboxError.Api(ERROR_NO_VAULT))
            }
        }
        val outcome = doUnlock(passphrase)
        transition.withLock {
            _state.value = if (outcome is UnlockResult.Success) VaultState.Unlocked else VaultState.Locked
        }
        return outcome
    }

    /**
     * Destroys the key material and every derived cache. After this returns,
     * nothing decrypted remains reachable from the session.
     */
    suspend fun lock() {
        transition.withLock {
            provider?.invalidateResolutionCache()
            provider = null
            cryptor?.destroy()
            cryptor = null
            if (_state.value == VaultState.Unlocked || _state.value == VaultState.Unlocking) {
                _state.value = VaultState.Locked
            }
        }
    }

    private suspend fun doUnlock(passphrase: CharSequence): UnlockResult {
        val vaultRoot = vaultRootDisplay
            ?: return UnlockResult.Failed(FreeboxError.Api(ERROR_NO_VAULT))
        val jwt = configJwt ?: return UnlockResult.Failed(FreeboxError.Api(ERROR_NO_VAULT))
        val keyBytes = when (
            val downloaded = inner.download(PathCodec.encode("$vaultRoot/${VaultFormat.MASTERKEY_FILE}"))
        ) {
            is FbxResult.Ok -> downloaded.value
            is FbxResult.Err -> return UnlockResult.Failed(downloaded.error)
        }
        return withContext(cryptoDispatcher) {
            val masterkey: Masterkey = try {
                MasterkeyFileAccess(NO_PEPPER, csprng).load(keyBytes.inputStream(), passphrase)
            } catch (_: InvalidPassphraseException) {
                return@withContext UnlockResult.WrongPassphrase
            } catch (_: Exception) {
                return@withContext UnlockResult.Failed(FreeboxError.Api(ERROR_MASTERKEY_UNREADABLE))
            }
            try {
                when (val check = VaultConfig.verify(jwt, masterkey.encoded)) {
                    is VaultConfig.Check.Supported -> {
                        val newCryptor = CryptolibProvider
                            .forScheme(CryptolibProvider.Scheme.SIV_GCM)
                            .provide(masterkey.copy(), csprng)
                        transition.withLock {
                            cryptor = newCryptor
                            provider = CryptomatorProvider(inner, newCryptor, vaultRoot, cryptoDispatcher)
                        }
                        UnlockResult.Success
                    }
                    is VaultConfig.Check.Unsupported -> UnlockResult.UnsupportedVault(check.reason)
                    VaultConfig.Check.BadSignature -> UnlockResult.UnsupportedVault("config signature")
                    VaultConfig.Check.Malformed -> UnlockResult.UnsupportedVault("config malformed")
                }
            } finally {
                masterkey.destroy()
            }
        }
    }

    private fun settle(state: VaultState): VaultState {
        _state.value = state
        if (state == VaultState.NoVault) {
            vaultRootDisplay = null
            configJwt = null
        }
        return state
    }

    companion object {
        const val ERROR_NO_VAULT = "no_vault"
        const val ERROR_MASTERKEY_UNREADABLE = "masterkey_unreadable"

        /** Cryptomator desktop writes masterkey files without a pepper. */
        private val NO_PEPPER = ByteArray(0)
    }
}
