package com.nuvio.app.features.downloads

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TRANSFER_BUFFER_BYTES = 64 * 1024

private val downloadHttpClient = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

internal actual object DownloadsPlatformDownloader {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun start(
        request: DownloadPlatformRequest,
        listener: DownloadTransferListener,
    ): DownloadsTaskHandle {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val handle = AndroidDownloadsTaskHandle(job)

        scope.launch {
            val context = appContext
            if (context == null) {
                listener.onFailed(
                    DownloadFailureReason.Fatal,
                    runBlocking { getString(Res.string.downloads_error_not_initialized) },
                    0L,
                )
                return@launch
            }

            // Scheduling is best-effort and must not be able to take the transfer with
            // it. JobScheduler refuses a user-initiated job outright when the app is
            // not in a state allowed to start one, and that used to throw straight out
            // of start(), leaving the queue with an item marked as downloading that
            // nothing was ever going to download.
            runCatching {
                DownloadsBackgroundScheduler.schedule(
                    context = context,
                    allowMeteredNetwork = request.allowMeteredNetwork,
                )
            }

            val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
            val destination = File(downloadsDir, request.destinationFileName)
            val tempFile = File(downloadsDir, "${request.destinationFileName}.part")
            var downloadedBytes = 0L

            try {
                var resumeFromBytes = tempFile.takeIf { it.exists() }?.length()?.coerceAtLeast(0L) ?: 0L
                downloadedBytes = resumeFromBytes

                fun buildRequest(rangeStart: Long?): Request {
                    val requestBuilder = Request.Builder().url(request.sourceUrl)
                    request.sourceHeaders.forEach { (key, value) ->
                        requestBuilder.header(key, value)
                    }
                    if (rangeStart != null && rangeStart > 0L) {
                        requestBuilder.header("Range", "bytes=$rangeStart-")
                        // Without a validator the server cannot tell us the bytes it is
                        // about to send belong to a different file than the partial one
                        // on disk, and we would append them blindly.
                        resumeValidator(request.resumeEtag, request.resumeLastModified)?.let {
                            requestBuilder.header("If-Range", it)
                        }
                    }
                    return requestBuilder.get().build()
                }

                var attemptedRangeRequest = resumeFromBytes > 0L
                var response = handle
                    .attachCall(
                        downloadHttpClient.newCall(
                            buildRequest(if (attemptedRangeRequest) resumeFromBytes else null),
                        ),
                    )
                    .execute()

                if (attemptedRangeRequest && response.code == 416) {
                    // The requested range starts past the end of the object. If that is
                    // because the partial file already holds every byte, the download is
                    // finished and re-fetching it from zero would be pure waste.
                    val reportedTotal = parseContentRangeTotal(response.header("Content-Range"))
                        ?: request.knownTotalBytes
                    response.close()

                    if (reportedTotal != null && tempFile.length() == reportedTotal) {
                        val finalized = finalizePartialFile(tempFile, destination)
                        if (finalized == null) {
                            listener.onFailed(
                                DownloadFailureReason.Transient,
                                runBlocking { getString(Res.string.downloads_error_finalize_file_failed) },
                                downloadedBytes,
                            )
                        } else {
                            listener.onCompleted(finalized.first, finalized.second)
                        }
                        return@launch
                    }

                    tempFile.delete()
                    resumeFromBytes = 0L
                    downloadedBytes = 0L
                    attemptedRangeRequest = false
                    response = handle
                        .attachCall(downloadHttpClient.newCall(buildRequest(null)))
                        .execute()
                }

                if (!response.isSuccessful) {
                    val statusCode = response.code
                    response.close()
                    listener.onFailed(
                        failureReasonForHttpStatus(statusCode),
                        runBlocking { getString(Res.string.downloads_error_http_failed, statusCode) },
                        downloadedBytes,
                    )
                    return@launch
                }

                response.use { openResponse ->
                    val isPartialResume =
                        attemptedRangeRequest && openResponse.code == 206 && resumeFromBytes > 0L
                    val appendToTemp = isPartialResume
                    val startingBytes = if (appendToTemp) resumeFromBytes else 0L

                    // A 200 answer to a range request means the server either ignores
                    // ranges or has told us, via If-Range, that the object changed. Either
                    // way the bytes on disk no longer belong to this response.
                    if (!appendToTemp && tempFile.exists()) {
                        tempFile.delete()
                    }
                    downloadedBytes = startingBytes

                    val body = openResponse.body ?: error(
                        runBlocking { getString(Res.string.downloads_error_empty_body) },
                    )
                    val totalBytes = resolveTotalBytes(
                        startingBytes = startingBytes,
                        isPartialResume = isPartialResume,
                        contentRangeHeader = openResponse.header("Content-Range"),
                        contentLength = body.contentLength().takeIf { it > 0L },
                    )
                    listener.onOpened(
                        resumedFromBytes = startingBytes,
                        totalBytes = totalBytes,
                        etag = openResponse.header("ETag"),
                        lastModified = openResponse.header("Last-Modified"),
                    )
                    listener.onProgress(downloadedBytes, totalBytes)

                    var lastReportedBytes = downloadedBytes
                    var lastReportedAtEpochMs = DownloadsClock.nowEpochMs()

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile, appendToTemp).use { output ->
                            val buffer = ByteArray(TRANSFER_BUFFER_BYTES)
                            while (true) {
                                ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                downloadedBytes += read.toLong()

                                // Reporting every chunk used to re-serialise and rewrite the
                                // whole downloads payload thousands of times per file.
                                val nowEpochMs = DownloadsClock.nowEpochMs()
                                if (
                                    shouldReportProgress(
                                        downloadedBytes = downloadedBytes,
                                        lastReportedBytes = lastReportedBytes,
                                        nowEpochMs = nowEpochMs,
                                        lastReportedAtEpochMs = lastReportedAtEpochMs,
                                    )
                                ) {
                                    lastReportedBytes = downloadedBytes
                                    lastReportedAtEpochMs = nowEpochMs
                                    listener.onProgress(downloadedBytes, totalBytes)
                                }
                            }
                            output.flush()
                        }
                    }
                    listener.onProgress(downloadedBytes, totalBytes)

                    // Reaching the end of the body is not the same as having the whole
                    // file. A dropped connection or a short error body ends the stream
                    // just as cleanly as a finished download does.
                    when (val completion = evaluateCompletion(downloadedBytes, totalBytes)) {
                        is DownloadCompletion.Short -> {
                            listener.onFailed(
                                DownloadFailureReason.Incomplete,
                                runBlocking { getString(Res.string.downloads_error_incomplete_transfer) },
                                completion.downloadedBytes,
                            )
                            return@launch
                        }

                        is DownloadCompletion.Overrun -> {
                            // More bytes than the file can hold means the partial file is
                            // not what we thought it was; it cannot be resumed from.
                            tempFile.delete()
                            downloadedBytes = 0L
                            listener.onFailed(
                                DownloadFailureReason.SourceChanged,
                                runBlocking { getString(Res.string.downloads_error_source_changed) },
                                0L,
                            )
                            return@launch
                        }

                        DownloadCompletion.Complete -> Unit
                    }

                    val finalized = finalizePartialFile(tempFile, destination)
                    if (finalized == null) {
                        listener.onFailed(
                            DownloadFailureReason.Transient,
                            runBlocking { getString(Res.string.downloads_error_finalize_file_failed) },
                            downloadedBytes,
                        )
                        return@launch
                    }
                    listener.onCompleted(finalized.first, finalized.second)
                }
            } catch (cancellation: CancellationException) {
                // A pause is a deliberate stop, not a failure. Reporting it as one is
                // what used to leave paused downloads sitting in a failed state.
                listener.onPaused(currentPartialBytes(tempFile, downloadedBytes))
                throw cancellation
            } catch (error: Throwable) {
                if (handle.isCancelled) {
                    listener.onPaused(currentPartialBytes(tempFile, downloadedBytes))
                } else {
                    listener.onFailed(
                        DownloadFailureReason.Transient,
                        error.message ?: runBlocking { getString(Res.string.download_failed) },
                        currentPartialBytes(tempFile, downloadedBytes),
                    )
                }
            }
        }

        return handle
    }

    actual fun freeStorageBytes(): Long =
        appContext?.filesDir?.usableSpace?.takeIf { it > 0L } ?: -1L

    actual fun removeFile(localFileUri: String?): Boolean {
        if (localFileUri.isNullOrBlank()) return false
        val file = localFileUri.toLocalFileOrNull() ?: return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    actual fun removePartialFile(destinationFileName: String): Boolean {
        val tempFile = partialFile(destinationFileName) ?: return false
        if (!tempFile.exists()) return true
        return runCatching { tempFile.delete() }.getOrDefault(false)
    }

    actual fun partialFileBytes(destinationFileName: String): Long {
        val tempFile = partialFile(destinationFileName) ?: return 0L
        return runCatching { tempFile.takeIf { it.exists() }?.length() ?: 0L }.getOrDefault(0L)
    }

    actual fun resolveLocalFileUri(localFileUri: String?, destinationFileName: String): String? {
        localFileUri
            ?.toLocalFileOrNull()
            ?.takeIf { it.exists() }
            ?.let { return it.toURI().toString() }

        val context = appContext ?: return null
        val fileName = destinationFileName.trim().takeIf { it.isNotBlank() }
            ?: localFileUri
                ?.toLocalFileOrNull()
                ?.name
                ?.takeIf { it.isNotBlank() }
            ?: return null
        val downloadsDir = File(context.filesDir, "downloads")
        val localFile = File(downloadsDir, fileName)
        return localFile.takeIf { it.exists() }?.toURI()?.toString()
    }

    actual fun openDownloadsDirectory(): Boolean {
        val context = appContext ?: return false
        val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
        val uri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                downloadsDir,
            )
        }.getOrNull() ?: return false

        val intents = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
            },
            Intent(Intent.ACTION_VIEW).apply {
                data = uri
            },
        )

        return intents.any { intent ->
            intent.addCategory(Intent.CATEGORY_DEFAULT)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)

            runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }

    private fun partialFile(destinationFileName: String): File? {
        val context = appContext ?: return null
        val downloadsDir = File(context.filesDir, "downloads")
        return File(downloadsDir, "$destinationFileName.part")
    }
}

/** Moves a verified partial file into place, returning its URI and confirmed size. */
private fun finalizePartialFile(tempFile: File, destination: File): Pair<String, Long>? {
    return runCatching {
        if (destination.exists()) {
            destination.delete()
        }
        if (!tempFile.renameTo(destination)) {
            tempFile.copyTo(destination, overwrite = true)
            tempFile.delete()
        }
        val finalSize = destination.length()
        if (!destination.exists() || finalSize <= 0L) return null
        destination.toURI().toString() to finalSize
    }.getOrNull()
}

/** The byte count actually on disk, which is what a resume will continue from. */
private fun currentPartialBytes(tempFile: File, fallback: Long): Long =
    runCatching { tempFile.takeIf { it.exists() }?.length() }.getOrNull() ?: fallback

/**
 * Handle over a transfer whose read loop is blocking, not suspending.
 *
 * Cancelling the job alone does not stop a thread parked in a socket read; it only
 * takes effect at the next `ensureActive`, which can be a whole read timeout away.
 * Cancelling the call ends the read now. This used to hang off
 * `job.invokeOnCompletion`, which fires once the job's children have finished -
 * that is, after the very read it was meant to interrupt.
 */
private class AndroidDownloadsTaskHandle(
    private val job: Job,
) : DownloadsTaskHandle {
    private val activeCall = AtomicReference<Call?>(null)
    private val cancelled = AtomicBoolean(false)

    /** True once [cancel] has been called, so a resulting IO error reads as a pause. */
    val isCancelled: Boolean
        get() = cancelled.get()

    /** Registers [call] as the one to interrupt, and hands it straight back. */
    fun attachCall(call: Call): Call {
        activeCall.set(call)
        // Cancelled while the request was being built; honour it rather than issuing
        // a call nothing will ever stop.
        if (cancelled.get()) call.cancel()
        return call
    }

    override fun cancel() {
        cancelled.set(true)
        activeCall.get()?.let { runCatching { it.cancel() } }
        job.cancel()
    }
}

private fun String.toLocalFileOrNull(): File? {
    return runCatching {
        if (startsWith("file:")) {
            File(URI(this))
        } else {
            File(this)
        }
    }.getOrNull()
}
