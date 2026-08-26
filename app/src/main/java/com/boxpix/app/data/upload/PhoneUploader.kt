package com.boxpix.app.data.upload

import com.boxpix.app.core.FbxResult
import com.boxpix.app.data.freebox.api.PathCodec
import com.boxpix.app.data.storage.StorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phone -> disk uploads (owner's flow: pick photos or a whole folder on the
 * phone, drop them into the folder currently open in the Explorer). Runs in
 * the app scope so navigating away does not cancel a batch; sequential, one
 * file in RAM at a time, through the ws/upload path every other write uses.
 *
 * Uploading into the vault works too — the routing provider encrypts on the
 * way in. V1 bound: files over [MAX_UPLOAD_BYTES] are skipped (whole-file
 * RAM buffering, no upload streaming yet) and reported.
 *
 * V1 limitation, on purpose: the batch lives in memory — keep the app around
 * until the summary shows (no persistent queue, unlike device downloads).
 */
@Singleton
class PhoneUploader @Inject constructor(
    private val provider: StorageProvider,
    private val scope: CoroutineScope,
) {

    /** One file to send: [relativeDir] recreates a picked folder's structure. */
    data class PhoneFile(val name: String, val bytes: ByteArray, val relativeDir: String = "")

    data class Progress(val fileName: String?, val index: Int, val total: Int)

    data class Outcome(
        val uploaded: Int,
        val failed: Int,
        val skippedTooLarge: Int,
        val destDisplayPath: String,
    )

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** Sticky until consumed: the Explorer reloads and shows the summary. */
    private val _lastOutcome = MutableStateFlow<Outcome?>(null)
    val lastOutcome: StateFlow<Outcome?> = _lastOutcome.asStateFlow()

    private val busy = AtomicBoolean(false)

    fun consumeOutcome() {
        _lastOutcome.value = null
    }

    /**
     * Uploads [sources] under [destDisplayPath]. Each source resolves lazily
     * (content is read only when its turn comes); a null resolution counts as
     * a failure. Ignored while a batch is already running.
     */
    fun upload(destDisplayPath: String, sources: List<suspend () -> PhoneFile?>) {
        if (sources.isEmpty() || !busy.compareAndSet(false, true)) return
        _lastOutcome.value = null
        scope.launch {
            var uploaded = 0
            var failed = 0
            var skipped = 0
            try {
                val ensuredDirs = HashSet<String>()
                sources.forEachIndexed { index, load ->
                    _progress.value = Progress(null, index + 1, sources.size)
                    val file = runCatching { load() }.getOrNull()
                    if (file == null) {
                        failed++
                        return@forEachIndexed
                    }
                    _progress.value = Progress(file.name, index + 1, sources.size)
                    if (file.bytes.size > MAX_UPLOAD_BYTES) {
                        skipped++
                        return@forEachIndexed
                    }
                    val parentDisplay = if (file.relativeDir.isEmpty()) {
                        destDisplayPath
                    } else {
                        "$destDisplayPath/${file.relativeDir}"
                    }
                    if (file.relativeDir.isNotEmpty() && ensuredDirs.add(parentDisplay)) {
                        mkdirs(destDisplayPath, file.relativeDir)
                    }
                    when (provider.upload(PathCodec.encode(parentDisplay), file.name, file.bytes)) {
                        is FbxResult.Ok -> uploaded++
                        is FbxResult.Err -> failed++
                    }
                }
            } finally {
                _progress.value = null
                _lastOutcome.value = Outcome(uploaded, failed, skipped, destDisplayPath)
                busy.set(false)
            }
        }
    }

    private suspend fun mkdirs(base: String, relativeDir: String) {
        var parent = base
        relativeDir.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            provider.mkdir(PathCodec.encode(parent), segment) // conflict = already there
            parent = "$parent/$segment"
        }
    }

    companion object {
        const val MAX_UPLOAD_BYTES = 200 * 1024 * 1024
    }
}
