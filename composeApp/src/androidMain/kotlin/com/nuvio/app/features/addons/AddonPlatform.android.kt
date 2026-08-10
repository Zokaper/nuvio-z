package com.nuvio.app.features.addons

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.app.core.diagnostics.SentryNetworkBreadcrumbInterceptor
import com.nuvio.app.core.network.IPv4FirstDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.network_empty_response_body
import nuvio.composeapp.generated.resources.network_request_failed_http
import org.jetbrains.compose.resources.getString
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.text.Charsets
import java.util.concurrent.TimeUnit

actual object AddonStorage {
    private const val preferencesName = "nuvio_addons"
    private const val addonUrlsKey = "installed_manifest_urls"
    private const val addonEnabledStatesKey = "installed_manifest_enabled_states"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadInstalledAddonUrls(profileId: Int): List<String> =
        preferences
            ?.getString("${addonUrlsKey}_$profileId", null)
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    actual fun saveInstalledAddonUrls(profileId: Int, urls: List<String>) {
        preferences
            ?.edit()
            ?.putString("${addonUrlsKey}_$profileId", urls.joinToString(separator = "\n"))
            ?.apply()
    }

    actual fun loadAddonEnabledStates(profileId: Int): Map<String, Boolean> =
        preferences
            ?.getString("${addonEnabledStatesKey}_$profileId", null)
            .orEmpty()
            .lineSequence()
            .mapNotNull(::parseEnabledStateLine)
            .toMap()

    actual fun saveAddonEnabledStates(profileId: Int, states: Map<String, Boolean>) {
        val payload = states.entries.joinToString(separator = "\n") { (url, enabled) ->
            "$url\t$enabled"
        }
        preferences
            ?.edit()
            ?.putString("${addonEnabledStatesKey}_$profileId", payload)
            ?.apply()
    }
}

private fun parseEnabledStateLine(line: String): Pair<String, Boolean>? {
    val url = line.substringBefore("\t").trim().takeIf { it.isNotEmpty() } ?: return null
    val rawEnabled = line.substringAfter("\t", "true").trim().lowercase()
    val enabled = when (rawEnabled) {
        "false" -> false
        else -> true
    }
    return url to enabled
}

private val addonHttpClient = OkHttpClient.Builder()
    .dns(IPv4FirstDns())
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .addInterceptor(SentryNetworkBreadcrumbInterceptor())
    .proxy(Proxy.NO_PROXY)
    .build()

private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

private data class LimitedReadResult(
    val bytes: ByteArray,
    val truncated: Boolean,
)

private fun requestAllowsBody(method: String): Boolean =
    when (method.uppercase()) {
        "POST", "PUT", "PATCH", "DELETE" -> true
        else -> false
    }

private fun Map<String, String>.withoutAcceptEncoding(): Map<String, String> =
    entries
        .filterNot { (key, _) -> key.equals("Accept-Encoding", ignoreCase = true) }
        .associate { (key, value) -> key to value }

private fun Map<String, String>.getHeaderIgnoreCase(name: String): String? =
    entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

private fun readAtMostBytes(stream: InputStream, maxBytes: Int): LimitedReadResult {
    val out = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var remaining = maxBytes
    var truncated = false

    while (remaining > 0) {
        val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) break
        out.write(buffer, 0, read)
        remaining -= read
    }

    if (remaining == 0) {
        truncated = stream.read() != -1
    }

    return LimitedReadResult(out.toByteArray(), truncated)
}

private fun readResponseBodyLimited(body: ResponseBody?, maxBytes: Int): String {
    if (body == null) return ""
    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    val readResult = body.byteStream().use { stream ->
        readAtMostBytes(stream, maxBytes.coerceAtLeast(0))
    }

    val decoded = try {
        String(readResult.bytes, charset)
    } catch (_: Exception) {
        String(readResult.bytes, Charsets.UTF_8)
    }

    return if (readResult.truncated) "$decoded\n...[truncated]" else decoded
}

private fun readResponseBody(body: ResponseBody?): String {
    if (body == null) return ""
    val bytes = body.bytes()
    return runCatching {
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        String(bytes, charset)
    }.getOrElse {
        String(bytes, Charsets.UTF_8)
    }
}

private suspend fun executeTextRequest(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: String = "",
): String = withContext(Dispatchers.IO) {
    val normalizedMethod = method.uppercase()
    val sanitizedHeaders = headers.withoutAcceptEncoding()
    val builder = Request.Builder().url(url)
    sanitizedHeaders.forEach { (key, value) ->
        builder.header(key, value)
    }

    val request = if (requestAllowsBody(normalizedMethod)) {
        val contentType = sanitizedHeaders.getHeaderIgnoreCase("Content-Type")
            ?: if (normalizedMethod == "POST") "application/x-www-form-urlencoded" else "application/json"
        // Preserve exact media type and avoid implicit charset rewriting used in signed APIs like MovieBox.
        val requestBody = body.toByteArray(Charsets.UTF_8).toRequestBody(contentType.toMediaType())
        builder.method(normalizedMethod, requestBody)
    } else {
        builder.method(normalizedMethod, null)
    }.build()

    addonHttpClient.newCall(request).execute().use { response ->
        val payload = readResponseBody(response.body)
        if (!response.isSuccessful) {
            error(runBlocking { getString(Res.string.network_request_failed_http, response.code) })
        }
        if (payload.isBlank()) {
            throw IllegalStateException(runBlocking { getString(Res.string.network_empty_response_body) })
        }
        payload
    }
}

actual suspend fun httpGetText(url: String): String =
    executeTextRequest(
        method = "GET",
        url = url,
        headers = mapOf("Accept" to "application/json"),
    )

actual suspend fun httpPostJson(url: String, body: String): String =
    executeTextRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ),
        body = body,
    )

actual suspend fun httpGetTextWithHeaders(
    url: String,
    headers: Map<String, String>,
): String =
    executeTextRequest(
        method = "GET",
        url = url,
        headers = mapOf("Accept" to "application/json") + headers,
    )

actual suspend fun httpPostJsonWithHeaders(
    url: String,
    body: String,
    headers: Map<String, String>,
): String =
    executeTextRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        ) + headers,
        body = body,
    )

actual suspend fun httpRequestRaw(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String,
    followRedirects: Boolean,
    maxResponseBodyBytes: Int,
): RawHttpResponse =
    withContext(Dispatchers.IO) {
        val normalizedMethod = method.uppercase()
        val sanitizedHeaders = headers.withoutAcceptEncoding()
        val builder = Request.Builder().url(url)
        sanitizedHeaders.forEach { (key, value) ->
            builder.header(key, value)
        }

        val request = if (requestAllowsBody(normalizedMethod)) {
            val contentType = sanitizedHeaders.getHeaderIgnoreCase("Content-Type")
                ?: if (normalizedMethod == "POST") "application/x-www-form-urlencoded" else "application/json"
            val requestBody = body.toByteArray(Charsets.UTF_8).toRequestBody(contentType.toMediaType())
            builder.method(normalizedMethod, requestBody)
        } else {
            builder.method(normalizedMethod, null)
        }.build()

        val client = if (followRedirects) {
            addonHttpClient
        } else {
            addonHttpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        client.newCall(request).execute().use { response ->
            RawHttpResponse(
                status = response.code,
                statusText = response.message,
                url = response.request.url.toString(),
                body = readResponseBodyLimited(response.body, maxResponseBodyBytes),
                headers = response.headers.toMultimap().mapValues { (_, values) ->
                    values.joinToString(",")
                }.mapKeys { (name, _) ->
                    name.lowercase()
                },
            )
        }
    }

actual suspend fun httpMeasureThroughput(
    url: String,
    headers: Map<String, String>,
    maxBytes: Long,
    maxMillis: Long,
    stopAboveMbps: Double?,
): ThroughputSample = withContext(Dispatchers.IO) {
    val builder = Request.Builder().url(url)
    headers.withoutAcceptEncoding().forEach { (key, value) ->
        builder.header(key, value)
    }
    // Both deliberate, and both would silently ruin the number: a compressed body times
    // compressed bytes, and an open-ended GET would keep pulling a multi-gigabyte file long
    // after the measurement is over.
    builder.header("Accept-Encoding", "identity")
    if (maxBytes > 0L) builder.header("Range", "bytes=0-${maxBytes - 1}")

    // Before the call, not after it: `execute` already blocks until the response headers are
    // in, so a clock started inside the body reader would report a time-to-first-byte of
    // nearly zero for a host that took two seconds to answer.
    val startNs = System.nanoTime()
    addonHttpClient.newCall(builder.get().build()).execute().use { response ->
        if (response.code !in 200..299) {
            return@withContext ThroughputSample(response.code, bytes = 0L, transferMs = 0L, ttfbMs = 0L)
        }
        measureBodyThroughput(
            body = response.body,
            status = response.code,
            startNs = startNs,
            maxBytes = maxBytes,
            maxMillis = maxMillis,
            stopAboveMbps = stopAboveMbps,
        )
    }
}

/**
 * Streams the body, counting bytes and timing them from the first one.
 *
 * Takes a nullable [ResponseBody] rather than dereferencing `response.body` at the call site:
 * OkHttp's accessor is non-null on one of these two source sets and nullable on the other, and
 * `response.body.byteStream()` compiles on Android while failing the desktop build outright.
 * Every other body reader in this file takes the nullable type for the same reason.
 */
private fun measureBodyThroughput(
    body: ResponseBody?,
    status: Int,
    startNs: Long,
    maxBytes: Long,
    maxMillis: Long,
    stopAboveMbps: Double?,
): ThroughputSample {
    val stream = body?.byteStream()
        ?: return ThroughputSample(status, bytes = 0L, transferMs = 0L, ttfbMs = 0L)
    val buffer = ByteArray(READ_CHUNK_BYTES)
    var firstByteNs = 0L
    var measuredBytes = 0L
    var lastNs = 0L
    stream.use {
        while (measuredBytes < maxBytes) {
            val read = it.read(buffer)
            if (read <= 0) break
            lastNs = System.nanoTime()
            if (firstByteNs == 0L) {
                // The chunk that starts the clock is not counted - its own transfer time is
                // exactly what has not been measured yet.
                firstByteNs = lastNs
                continue
            }
            measuredBytes += read
            val transferMs = (lastNs - firstByteNs) / 1_000_000L
            if (transferMs >= maxMillis) break
            if (stopAboveMbps != null && transferMs > 0L &&
                measuredBytes.toDouble() * 8.0 / transferMs.toDouble() / 1_000.0 > stopAboveMbps
            ) {
                break
            }
        }
    }
    return ThroughputSample(
        status = status,
        bytes = measuredBytes,
        transferMs = if (firstByteNs == 0L) 0L else (lastNs - firstByteNs) / 1_000_000L,
        ttfbMs = if (firstByteNs == 0L) 0L else (firstByteNs - startNs) / 1_000_000L,
    )
}

private const val READ_CHUNK_BYTES = 64 * 1024
