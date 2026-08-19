package com.boxpix.app.ui.lock

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.boxpix.app.R
import com.boxpix.app.ui.icons.Lucide
import com.boxpix.app.ui.theme.boxpixColors

/**
 * SPEC §2 app lock: system credential (PIN / pattern / biometrics) via
 * BiometricPrompt. Locks on launch and every return from the background.
 */
@Composable
fun AppLockGate(
    lockEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (!lockEnabled) {
        content()
        return
    }

    var unlocked by remember { mutableStateOf(false) }

    // Re-lock whenever the app leaves the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) unlocked = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (unlocked) {
        content()
    } else {
        LockScreen(onUnlocked = { unlocked = true })
    }
}

@Composable
private fun LockScreen(onUnlocked: () -> Unit) {
    val colors = boxpixColors
    val activity = LocalContext.current as? FragmentActivity

    fun prompt() {
        val host = activity ?: return
        val biometricPrompt = BiometricPrompt(
            host,
            ContextCompat.getMainExecutor(host),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }
            },
        )
        biometricPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(host.getString(R.string.lock_title))
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build(),
        )
    }

    LaunchedEffect(Unit) { prompt() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Lucide.Lock,
            contentDescription = null,
            tint = colors.dim,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.lock_title),
            style = MaterialTheme.typography.titleLarge,
            color = colors.text,
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier
                .height(46.dp)
                .border(1.dp, colors.accent, RoundedCornerShape(10.dp))
                .clickable { prompt() }
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.lock_unlock),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.accent,
            )
        }
    }
}
