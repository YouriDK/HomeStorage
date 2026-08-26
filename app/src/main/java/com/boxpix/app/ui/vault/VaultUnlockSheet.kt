package com.boxpix.app.ui.vault

import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.boxpix.app.R
import com.boxpix.app.ui.icons.Lucide
import com.boxpix.app.ui.theme.Hues
import com.boxpix.app.ui.theme.boxpixColors
import kotlinx.coroutines.delay
import javax.crypto.Cipher

/**
 * Unlock sheet: passphrase (with a worked waiting state — scrypt takes ~1.5 s
 * on device), inline errors with a sober shake, lock -> open morph on success,
 * and the two biometric flows (auto-prompt when a wrapped key exists, opt-in
 * wrap right after a successful passphrase unlock).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultUnlockSheet(viewModel: VaultViewModel) {
    val colors = boxpixColors
    val ui by viewModel.sheet.collectAsStateWithLifecycle()
    if (!ui.visible) return

    val activity = LocalContext.current as? FragmentActivity
    var passphrase by remember { mutableStateOf("") }

    // A stored wrapped key: offer the biometric prompt as soon as the sheet opens.
    LaunchedEffect(ui.biometricUnlockReady) {
        if (!ui.biometricUnlockReady || activity == null) return@LaunchedEffect
        val cipher = viewModel.beginBiometricUnlock() ?: return@LaunchedEffect
        biometricPrompt(
            activity = activity,
            title = activity.getString(R.string.vault_unlock_title),
            cipher = cipher,
            onSuccess = viewModel::onBiometricAuthenticated,
            onDismissed = viewModel::onBiometricAbandoned,
        )
    }

    // Success: let the morph play, then wrap (opt-in) and leave.
    LaunchedEffect(ui.justUnlocked) {
        if (!ui.justUnlocked) return@LaunchedEffect
        delay(SUCCESS_MORPH_MILLIS)
        val cipher = if (activity != null) viewModel.pendingWrapCipher() else null
        if (cipher == null) {
            viewModel.onWrapAbandoned()
            viewModel.dismissSheet()
            return@LaunchedEffect
        }
        biometricPrompt(
            activity = activity!!,
            title = activity.getString(R.string.vault_remember_prompt_title),
            cipher = cipher,
            onSuccess = { authenticated ->
                viewModel.onWrapAuthenticated(authenticated)
                viewModel.dismissSheet()
            },
            onDismissed = {
                viewModel.onWrapAbandoned()
                viewModel.dismissSheet()
            },
        )
    }

    // Sober shake on every wrong passphrase.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(ui.shakeNonce) {
        if (ui.shakeNonce == 0) return@LaunchedEffect
        shake.animateTo(
            0f,
            keyframes {
                durationMillis = 360
                (-9f) at 60
                9f at 120
                (-6f) at 180
                6f at 240
                (-3f) at 300
                0f at 360
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = { if (!ui.unlocking) viewModel.dismissSheet() },
        containerColor = colors.elevated,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LockBadge(unlocking = ui.unlocking, unlocked = ui.justUnlocked)
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(
                    if (ui.justUnlocked) R.string.vault_unlocked_toast else R.string.vault_unlock_title,
                ),
                style = MaterialTheme.typography.titleLarge,
                color = colors.text,
            )
            Spacer(Modifier.height(4.dp))
            AnimatedContent(
                targetState = ui.unlocking,
                transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
                label = "vault-subtitle",
            ) { unlocking ->
                Text(
                    text = stringResource(
                        when {
                            unlocking -> R.string.vault_unlocking
                            ui.justUnlocked -> R.string.vault_unlocked_subtitle
                            else -> R.string.vault_unlock_subtitle
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.dim,
                )
            }
            Spacer(Modifier.height(20.dp))

            if (!ui.justUnlocked) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.vault_passphrase_label)) },
                    singleLine = true,
                    enabled = !ui.unlocking,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { viewModel.unlockWithPassphrase(passphrase) },
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.hairlineStrong,
                        focusedLabelColor = colors.accent,
                        unfocusedLabelColor = colors.dim,
                        focusedTextColor = colors.text,
                        unfocusedTextColor = colors.text,
                        cursorColor = colors.accent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(shake.value.dp.roundToPx(), 0) },
                )

                ui.error?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            when (error) {
                                VaultViewModel.UnlockError.WRONG_PASSPHRASE -> R.string.vault_wrong_passphrase
                                VaultViewModel.UnlockError.UNSUPPORTED -> R.string.vault_unsupported
                                VaultViewModel.UnlockError.UNREACHABLE -> R.string.vault_unreachable
                                VaultViewModel.UnlockError.BIOMETRIC_INVALIDATED ->
                                    R.string.vault_biometrics_invalidated
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Hues.Danger,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (ui.biometricsAvailable) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.vault_remember_biometrics),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.text,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = ui.rememberChecked,
                            onCheckedChange = viewModel::setRemember,
                            enabled = !ui.unlocking,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = colors.accent,
                                checkedThumbColor = colors.bg,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = { viewModel.unlockWithPassphrase(passphrase) },
                    enabled = !ui.unlocking && passphrase.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.vault_unlock_action),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (passphrase.isNotEmpty() && !ui.unlocking) colors.accent else colors.faint,
                    )
                }
            }
        }
    }
}

/**
 * The lock in a circle: pulses gently behind a progress ring while scrypt
 * runs, morphs to an open lock with a small overshoot on success.
 */
@Composable
private fun LockBadge(unlocking: Boolean, unlocked: Boolean) {
    val colors = boxpixColors
    val pulse by rememberInfiniteTransition(label = "vault-pulse").animateFloat(
        initialValue = 1f,
        targetValue = if (unlocking) 1.07f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "vault-pulse-scale",
    )
    Box(contentAlignment = Alignment.Center) {
        if (unlocking) {
            CircularProgressIndicator(
                color = colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(72.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .scale(if (unlocking) pulse else 1f)
                .background(
                    if (unlocked) colors.accentSoft else colors.surface,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = unlocked,
                transitionSpec = {
                    (scaleIn(initialScale = 0.6f, animationSpec = tween(220)) + fadeIn(tween(160)))
                        .togetherWith(fadeOut(tween(120)))
                },
                label = "vault-lock-morph",
            ) { open ->
                Icon(
                    if (open) Lucide.LockOpen else Lucide.Lock,
                    contentDescription = null,
                    tint = if (open) colors.accent else colors.dim,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

private fun biometricPrompt(
    activity: FragmentActivity,
    title: String,
    cipher: Cipher,
    onSuccess: (Cipher) -> Unit,
    onDismissed: () -> Unit,
) {
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let(onSuccess) ?: onDismissed()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onDismissed()
            }
        },
    )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setNegativeButtonText(activity.getString(R.string.vault_use_passphrase))
            .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build(),
        BiometricPrompt.CryptoObject(cipher),
    )
}

private const val SUCCESS_MORPH_MILLIS = 650L
