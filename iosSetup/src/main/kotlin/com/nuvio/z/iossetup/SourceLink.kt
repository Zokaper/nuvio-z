package com.nuvio.z.iossetup

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Links the phone needs: the SideStore source, and the App Store page for LocalDevVPN. */
object SourceLink {
    /** A tiny static page that opens SideStore for the iPhone camera. Served by `sidestore-landing.yml`. */
    const val LANDING_BASE = "https://zokaper.github.io/nuvio-z/sidestore/add/"
    const val LOCALDEVVPN_APP_STORE = "https://apps.apple.com/app/id6755608044"

    fun deepLink(channel: SetupChannel): String =
        "sidestore://source?url=" + URLEncoder.encode(channel.sourceUrl, StandardCharsets.UTF_8).replace("+", "%20")

    fun landingUrl(channel: SetupChannel): String =
        LANDING_BASE + "?channel=" + if (channel == SetupChannel.STABLE) "stable" else "debug"

    /**
     * The iPhone camera opens https links reliably but often ignores custom schemes, so the QR carries the
     * landing page when it is live and falls back to the raw sidestore:// link when it is not.
     */
    fun qrPayload(channel: SetupChannel, landingReachable: Boolean): String =
        if (landingReachable) landingUrl(channel) else deepLink(channel)

    /** `sidestore://install?url=<ipa>`: SideStore offers to sign and install the app from this IPA. */
    fun installDeepLink(ipaUrl: String): String =
        "sidestore://install?url=" + URLEncoder.encode(ipaUrl, StandardCharsets.UTF_8).replace("+", "%20")

    fun installLandingUrl(channel: SetupChannel): String = landingUrl(channel) + "&action=install"

    /** The newest IPA listed in a SideStore source document (versions are newest first), or null. */
    fun latestIpaUrl(sourceJson: String): String? = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(sourceJson).jsonObject
        root["apps"]?.jsonArray?.firstOrNull()?.jsonObject?.get("versions")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("downloadURL")?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("https://") }
    }.getOrNull()

    /** Fetches the live feed for [channel] and returns its newest IPA URL. Null when offline or malformed. */
    fun fetchLatestIpaUrl(channel: SetupChannel, timeoutMillis: Int = 5_000): String? = runCatching {
        val connection = URI(channel.sourceUrl).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            if (connection.responseCode != 200) null else latestIpaUrl(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    fun isReachable(url: String, timeoutMillis: Int = 3_000): Boolean = runCatching {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "HEAD"
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.instanceFollowRedirects = true
            connection.responseCode in 200..399
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)
}
