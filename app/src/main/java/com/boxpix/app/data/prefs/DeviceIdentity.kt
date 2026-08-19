package com.boxpix.app.data.prefs

/** Who writes the journal — abstracted so tag logic is JVM-testable. */
fun interface DeviceIdentity {
    suspend fun get(): String
}
