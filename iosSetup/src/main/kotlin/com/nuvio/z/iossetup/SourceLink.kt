package com.nuvio.z.iossetup

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Links the phone needs: the SideStore source and install deep links, and LocalDevVPN's App Store page. */
object SourceLink {
    const val LOCALDEVVPN_APP_STORE = "https://apps.apple.com/app/id6755608044"

    fun deepLink(channel: SetupChannel): String =
        "sidestore://source?url=" + URLEncoder.encode(channel.sourceUrl, StandardCharsets.UTF_8).replace("+", "%20")

    /**
     * What the "add the source" QR encodes: always the sidestore:// deep link, which the iPhone Camera
     * hands straight to SideStore. Never an https link: a plain source URL opens Safari showing the raw
     * JSON text instead of adding anything, and there is deliberately no web page in between.
     */
    fun sourceQr(channel: SetupChannel): String = deepLink(channel)

    /** True only for payloads that open SideStore itself rather than a web page. */
    fun opensSideStore(payload: String): Boolean =
        payload.startsWith("sidestore://source?url=https%3A%2F%2F") || payload.startsWith("sidestore://install?url=https%3A%2F%2F")

    /** `sidestore://install?url=<ipa>`: SideStore offers to sign and install the app from this IPA. */
    fun installDeepLink(ipaUrl: String): String =
        "sidestore://install?url=" + URLEncoder.encode(ipaUrl, StandardCharsets.UTF_8).replace("+", "%20")

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
}
