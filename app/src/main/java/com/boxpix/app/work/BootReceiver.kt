package com.boxpix.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.boxpix.app.data.prefs.UiPrefsStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A dedicated worker phone should survive reboots without a human touching it. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var uiPrefs: UiPrefsStore

    @Inject lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        scope.launch {
            try {
                if (uiPrefs.workerModeEnabled.first()) {
                    WorkerService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
