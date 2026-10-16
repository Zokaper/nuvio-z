package com.nuvio.z.iossetup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The nightly refresh: a Shortcuts automation that connects LocalDevVPN and runs SideStore's
 * "Refresh All Apps" action, so a free account's 7-day signature never runs out.
 *
 * iOS lets nothing outside the phone create an automation, so the assistant can only explain it. What
 * it can ship is a ready-made shortcut as an iCloud link. That link is hosted, so it can be added or
 * replaced without a new release; while there is none, the user builds the same three actions by hand.
 */
@Serializable
data class AutoRefreshConfig(val schema: Int, val shortcutUrl: String? = null) {
    companion object {
        const val SCHEMA = 1

        /** Saved in Shortcuts under this name, and picked by name in the automation. */
        const val SHORTCUT_NAME = "Nuvio Z Auto-Refresh"

        const val REMOTE_URL = "https://raw.githubusercontent.com/Zokaper/nuvio-z/main/distribution/sidestore/ios-setup-autorefresh.json"

        /** Only a shared iCloud shortcut is accepted: a hosted file can never point the QR anywhere else. */
        private val icloudShortcut = Regex("^https://www\\.icloud\\.com/shortcuts/[0-9a-f]{32}$")
        private val json = Json { ignoreUnknownKeys = true }

        fun embedded(): AutoRefreshConfig = requireNotNull(parse(
            AutoRefreshConfig::class.java.getResourceAsStream("/autorefresh.json")?.bufferedReader()?.readText().orEmpty(),
        )) { "embedded autorefresh.json is invalid" }

        /** Parses and validates. Returns null for anything malformed or for a link that isn't an iCloud shortcut. */
        fun parse(text: String): AutoRefreshConfig? = runCatching { json.decodeFromString<AutoRefreshConfig>(text) }.getOrNull()
            ?.takeIf { it.schema == SCHEMA && (it.shortcutUrl == null || icloudShortcut.matches(it.shortcutUrl)) }

        /** Prefers the hosted file when it validates; otherwise the copy shipped with this build. */
        fun load(fetchRemote: () -> String? = ::fetchHosted, embedded: AutoRefreshConfig = embedded()): AutoRefreshConfig =
            runCatching { fetchRemote()?.let(::parse) }.getOrNull() ?: embedded

        private fun fetchHosted(): String? = runCatching {
            val request = HttpRequest.newBuilder(URI(REMOTE_URL)).timeout(Duration.ofSeconds(4)).GET().build()
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build()
                .send(request, HttpResponse.BodyHandlers.ofString()).takeIf { it.statusCode() == 200 }?.body()
        }.getOrNull()
    }
}

/** What can go wrong when SideStore is installed again over an expired copy. */
fun resignTips(channel: SetupChannel): List<TroubleTip> = listOf(
    TroubleTip("iOS says Untrusted Developer when SideStore opens", listOf(
        "On the iPhone open Settings → General → VPN & Device Management, select your Apple Account and tap Trust.",
    )),
    TroubleTip("There are now two SideStores", listOf(
        "iloader was signed in with a different Apple Account, so it installed a second copy instead of renewing the old one.",
        "Delete the copy that won’t open. In the one that’s left, sign in and add the ${channel.appName} source again if it’s missing.",
    )),
    TroubleTip("SideStore opens empty or asks to sign in", listOf(
        "Sign in again with the same Apple Account.",
        "If the ${channel.appName} source is missing, in SideStore open Sources, tap + and paste ${channel.sourceUrl}",
    )),
    TroubleTip("SideStore says the pairing file is invalid", listOf(
        "Keep the iPhone connected and unlocked, then use Replace the pairing file on this page.",
    )),
    TroubleTip("${channel.appName} still won’t open", listOf(
        "In SideStore → My Apps, tap the days counter next to ${channel.appName} to refresh just that app.",
        "If it isn’t listed, install it again from SideStore → Browse → ${channel.appName}.",
    )),
)

/** What can go wrong with the nightly refresh, and what to do about it. */
fun autoRefreshTips(appName: String): List<TroubleTip> = listOf(
    TroubleTip("LocalDevVPN isn’t listed in Set VPN", listOf(
        "Open LocalDevVPN and tap Connect once, so iOS knows about it.",
        "Then go back to the shortcut and pick LocalDevVPN in the Set VPN action again.",
    )),
    TroubleTip("SideStore’s Refresh All Apps action is missing", listOf(
        "Open SideStore once, then search the action list for “Refresh All Apps”.",
        "If it still isn’t there, update SideStore from SideStore → My Apps.",
    )),
    TroubleTip("The test run fails or says it isn’t connected", listOf(
        "Turn Wi-Fi on, open LocalDevVPN and check it says Connected, then run the shortcut again.",
        "If SideStore names a pairing problem, connect the iPhone to this computer and use Settings → Repair SideStore pairing.",
    )),
    TroubleTip("The days still count down", listOf(
        "In Shortcuts → Automation, open the automation and check it’s set to Run Immediately.",
        "The iPhone must be on Wi-Fi at that time. Pick a time it’s usually at home.",
        "If iOS asks you to open SideStore to finish, the refresh took longer than iOS allows in the background. Open SideStore and it completes.",
        "You can always refresh by hand: SideStore → My Apps → Refresh All.",
    )),
    TroubleTip("I use another VPN", listOf(
        "iOS runs one VPN at a time, which is why the second automation disconnects LocalDevVPN.",
        "Add a second Set VPN action to that automation that connects your usual VPN again.",
        "$appName keeps working while LocalDevVPN is off. It’s only needed during a refresh.",
    )),
)
