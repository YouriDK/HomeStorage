package com.boxpix.app.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Injected so the XMP queue's "wifi only" rule is testable on the JVM. */
fun interface NetworkStatus {
    fun isUnmetered(): Boolean
}

@Singleton
class AndroidNetworkStatus @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkStatus {
    override fun isUnmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
