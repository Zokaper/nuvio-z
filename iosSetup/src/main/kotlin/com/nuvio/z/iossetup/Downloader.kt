package com.nuvio.z.iossetup

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration

sealed interface DownloadResult {
    data class Done(val path: Path) : DownloadResult
    data class Failed(val reason: Reason, val detail: String = "") : DownloadResult

    enum class Reason { NETWORK, HTTP, CHECKSUM, SIZE }
}

/**
 * Resumable, verified downloads. Bytes land in `<name>.part`, are checked against the pin, and only
 * then renamed into place, so a half-finished or tampered file can never be mistaken for the real one.
 */
class Downloader(
    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(15)).build(),
    private val attempts: Int = 3,
    private val backoffMillis: Long = 1_500,
) {
    fun download(
        url: String,
        destination: Path,
        expectedSha256: String? = null,
        expectedBytes: Long? = null,
        minBytes: Long? = null,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
    ): DownloadResult {
        Files.createDirectories(destination.parent)
        val part = destination.resolveSibling(destination.fileName.toString() + ".part")
        var last: DownloadResult.Failed = DownloadResult.Failed(DownloadResult.Reason.NETWORK)
        repeat(attempts) { attempt ->
            if (attempt > 0) Thread.sleep(backoffMillis * attempt)
            when (val step = fetch(url, part, onProgress)) {
                is Step.Ok -> return verify(part, destination, expectedSha256, expectedBytes, minBytes)
                is Step.Retry -> last = step.failure
                is Step.Fatal -> return step.failure
            }
        }
        return last
    }

    private sealed interface Step {
        data object Ok : Step
        data class Retry(val failure: DownloadResult.Failed) : Step
        data class Fatal(val failure: DownloadResult.Failed) : Step
    }

    private fun fetch(url: String, part: Path, onProgress: (Long, Long?) -> Unit): Step = try {
        val have = if (Files.exists(part)) Files.size(part) else 0L
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(10)).apply {
            if (have > 0) header("Range", "bytes=$have-")
        }.GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val resumed = response.statusCode() == 206
        when {
            response.statusCode() == 416 -> { Files.deleteIfExists(part); Step.Retry(DownloadResult.Failed(DownloadResult.Reason.HTTP, "416")) }
            response.statusCode() !in 200..299 -> {
                response.body().close()
                val failure = DownloadResult.Failed(DownloadResult.Reason.HTTP, "HTTP ${response.statusCode()}")
                // A server error may clear up; a missing file will not.
                if (response.statusCode() >= 500 || response.statusCode() == 429) Step.Retry(failure) else Step.Fatal(failure)
            }
            else -> {
                val offset = if (resumed) have else 0L
                val total = response.headers().firstValueAsLong("Content-Length").let { if (it.isPresent) it.asLong + offset else null }
                val options = if (resumed) arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND) else arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                var downloaded = offset
                response.body().use { input ->
                    Files.newOutputStream(part, *options).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
                if (total != null && downloaded < total) Step.Retry(DownloadResult.Failed(DownloadResult.Reason.NETWORK, "connection closed early")) else Step.Ok
            }
        }
    } catch (error: IOException) {
        Step.Retry(DownloadResult.Failed(DownloadResult.Reason.NETWORK, error.message.orEmpty()))
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        Step.Fatal(DownloadResult.Failed(DownloadResult.Reason.NETWORK, "interrupted"))
    }

    private fun verify(part: Path, destination: Path, sha256: String?, bytes: Long?, minBytes: Long?): DownloadResult {
        val size = Files.size(part)
        if (bytes != null && size != bytes) return reject(part, DownloadResult.Reason.SIZE, "expected $bytes bytes, got $size")
        if (minBytes != null && size < minBytes) return reject(part, DownloadResult.Reason.SIZE, "only $size bytes")
        if (sha256 != null) {
            val actual = sha256Of(part)
            if (!actual.equals(sha256, ignoreCase = true)) return reject(part, DownloadResult.Reason.CHECKSUM, "sha256 $actual")
        }
        Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING)
        return DownloadResult.Done(destination)
    }

    /** A file that fails verification is deleted so a retry starts clean instead of resuming bad bytes. */
    private fun reject(part: Path, reason: DownloadResult.Reason, detail: String): DownloadResult {
        Files.deleteIfExists(part)
        return DownloadResult.Failed(reason, detail)
    }

    companion object {
        fun sha256Of(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
