package com.nuvio.z.iossetup

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
