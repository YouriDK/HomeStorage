package com.boxpix.app.data.prefs

/** Gate for the XMP write-through — abstracted so the queue stays JVM-testable. */
fun interface XmpPolicy {
    suspend fun enabled(): Boolean
}
