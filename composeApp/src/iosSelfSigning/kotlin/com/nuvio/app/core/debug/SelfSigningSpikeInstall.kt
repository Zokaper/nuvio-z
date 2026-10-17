package com.nuvio.app.core.debug

import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.selfsigning.native.nzs_answer_certificates
import com.nuvio.app.selfsigning.native.nzs_answer_two_factor
import com.nuvio.app.selfsigning.native.nzs_import_signing_key
import com.nuvio.app.selfsigning.native.nzs_install_update
import com.nuvio.app.selfsigning.native.nzs_installed_profiles
import com.nuvio.app.selfsigning.native.nzs_pending_certificates
import com.nuvio.app.selfsigning.native.nzs_pending_two_factor
import com.nuvio.app.selfsigning.native.nzs_probe
import com.nuvio.app.selfsigning.native.nzs_refresh
import com.nuvio.app.selfsigning.native.nzs_reset_sign_in
import com.nuvio.app.selfsigning.native.nzs_signing_state
import com.nuvio.app.selfsigning.native.nzs_status
import com.nuvio.app.selfsigning.native.nzs_string_free
import com.nuvio.app.selfsigning.native.nzs_update_progress
import com.nuvio.app.selfsigning.native.nzs_version
import com.nuvio.app.selfsigning.native.nzs_vpn_reachable
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIDevice
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The app is in the foreground: the platform plus the on-open renewal (at start and each time it comes back). */
internal actual fun installSelfSigningSpike() {
    val platform = IosSelfSigning.shared
    if (SelfSigningSpike.platform == null) {
        SelfSigningSpike.platform = platform
        NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            platform.onOpen()
        }
        platform.onOpen()
    }
}

/** Swift entry points (App Intent, background task) need the platform without the UI. */
internal actual fun selfSigningPlatform(): SelfSigningPlatform? = IosSelfSigning.shared.also {
    if (SelfSigningSpike.platform == null) SelfSigningSpike.platform = it
}

/**
 * Points [SelfSigningSpike] at the Rust library. Its calls block (network, the phone, Apple), so they run off the
 * main thread; the polls and answers only touch a lock and are safe anywhere.
 *
 * What it remembers lives in NSUserDefaults under `nzs.` (results and times only; the password is in
 * [SelfSigningKeychain]). Swift writes `nzs.bgtask.*` and `nzs.intent.*` itself.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
internal class IosSelfSigning private constructor() : SelfSigningPlatform {
    companion object {
        val shared by lazy { IosSelfSigning() }

        /** Renew quietly when the newest profile has less than this left. */
        private const val RENEW_WHEN_DAYS_LEFT = 3.0
        /** Opening the app checks at most this often (seconds). */
        private const val OPEN_CHECK_INTERVAL = 6 * 3600.0
        private const val UPDATE_TEST_FILE = "NuvioZ-Update-test.ipa"
        private const val SHORTCUT_CONFIG =
            "https://raw.githubusercontent.com/Zokaper/nuvio-z/main/distribution/self-signing/refresh-shortcut.json"
        private val icloudShortcut = Regex("^https://www\\.icloud\\.com/shortcuts/[0-9a-f]{32}$")
    }

    private val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
    private val bundle = NSBundle.mainBundle.bundlePath
    private val defaults = NSUserDefaults.standardUserDefaults
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    private fun take(raw: CPointer<ByteVar>?): String {
        raw ?: return """{"error":"no result"}"""
        return try {
            raw.toKString()
        } finally {
            nzs_string_free(raw)
        }
    }

    private fun takeOrNull(raw: CPointer<ByteVar>?): String? = raw?.let { take(it) }

    private fun now(): Instant = Clock.System.now()

    private fun obj(text: String?): JsonObject? = text?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content

    // ---- Calls into the library ----

    override suspend fun probe(): String = withContext(Dispatchers.Default) { take(nzs_probe(documents, null)) }

    override suspend fun status(): String = withContext(Dispatchers.Default) { take(nzs_status(bundle)) }

    override suspend fun installedProfiles(): String = withContext(Dispatchers.Default) {
        take(nzs_installed_profiles(bundle, documents, null)).also { result ->
            val root = obj(result)
            if (root != null && root.str("ok") == "true") mainExpiry(root["profiles"])?.let { defaults.setObject(it, "nzs.installedExpiry") }
        }
    }

    override suspend fun vpnReachable(): Boolean = withContext(Dispatchers.Default) { nzs_vpn_reachable(null) }

    override suspend fun refresh(email: String, password: String?, remember: Boolean): String {
        val secret = password ?: SelfSigningKeychain.read()?.password
            ?: return """{"ok":false,"error":{"kind":"other","summary":"no saved password; type it"}}"""
        val result = withContext(Dispatchers.Default) { take(nzs_refresh(bundle, documents, email, secret, null, true)) }
        val ok = obj(result)?.str("ok") == "true"
        if (ok && remember && password != null) SelfSigningKeychain.save(SelfSigningKeychain.Account(email, password))
        record("app", result)
        return result
    }

    override suspend fun refreshSaved(trigger: String): String {
        val account = SelfSigningKeychain.read()
        val result = if (account == null) {
            """{"ok":false,"error":{"kind":"other","summary":"no Apple Account saved on this iPhone; renew once with Remember ticked"}}"""
        } else {
            withContext(Dispatchers.Default) { take(nzs_refresh(bundle, documents, account.email, account.password, null, false)) }
        }
        record(trigger, result)
        return result
    }

    override fun waitingForCode(): Boolean = takeOrNull(nzs_pending_two_factor()) != null

    override fun answerCode(code: String?) {
        nzs_answer_two_factor(code)
    }

    override fun pendingCertificates(): String? = takeOrNull(nzs_pending_certificates())

    override fun answerCertificates(serials: List<String>?) {
        nzs_answer_certificates(serials?.let { list -> JsonArray(list.map { JsonPrimitive(it) }).toString() })
    }

    override suspend fun installUpdate(source: String, sha256: String?): String {
        val account = SelfSigningKeychain.read()
            ?: return """{"ok":false,"error":{"kind":"other","summary":"no Apple Account saved on this iPhone; renew once with Remember ticked"}}"""
        val embeddedBefore = obj(status())?.str("expires")
        defaults.setObject(buildJsonObject {
            put("at", now().toString())
            put("state", "installing")
            put("source", if (source.startsWith("https://")) source else "IPA on this iPhone")
            put("embeddedBefore", embeddedBefore)
        }.toString(), "nzs.lastUpdate")
        val result = withContext(Dispatchers.Default) {
            take(nzs_install_update(bundle, documents, source, sha256, account.email, account.password, null))
        }
        // Usually not reached on success: iOS ends this app when it replaces it, and the next start records it.
        val root = obj(result)
        defaults.setObject(buildJsonObject {
            put("at", now().toString())
            put("state", if (root?.str("ok") == "true") "returned" else "failed")
            put("result", root ?: JsonObject(emptyMap()))
        }.toString(), "nzs.lastUpdate")
        return result
    }

    override fun updateProgress(): String? = takeOrNull(nzs_update_progress())

    override fun updateTestIpa(): String? =
        documents?.let { "$it/$UPDATE_TEST_FILE" }?.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }

    override fun savedAccount(): String? = SelfSigningKeychain.read()?.email

    override fun forgetAccount() = SelfSigningKeychain.clear()

    override suspend fun resetSignIn(): String = withContext(Dispatchers.Default) { take(nzs_reset_sign_in()) }

    override fun lastRefresh(): String? = defaults.stringForKey("nzs.lastRefresh")

    override suspend fun refreshShortcutUrl(): String? = withContext(Dispatchers.Default) {
        withTimeoutOrNull(5_000) {
            runCatching {
                val client = HttpClient()
                try {
                    val response = client.get(SHORTCUT_CONFIG)
                    if (!response.status.isSuccess()) return@runCatching null
                    obj(response.bodyAsText())?.str("shortcutUrl")?.takeIf { icloudShortcut.matches(it) }
                } finally {
                    client.close()
                }
            }.getOrNull()
        }
    }

    // ---- What is remembered ----

    /** The main app's expiry among the profiles a renew or a check reported. */
    private fun mainExpiry(profiles: JsonElement?): String? =
        (profiles as? JsonArray)?.firstOrNull()?.jsonObject?.str("expires")

    private fun record(trigger: String, result: String) {
        val root = obj(result)
        val ok = root?.str("ok") == "true"
        val error = root?.get("error") as? JsonObject
        val expires = if (ok) mainExpiry(root?.get("report")?.jsonObject?.get("profiles")) else null
        if (expires != null) defaults.setObject(expires, "nzs.installedExpiry")
        defaults.setObject(buildJsonObject {
            put("at", now().toString())
            put("trigger", trigger)
            put("ok", ok)
            error?.str("kind")?.let { put("kind", it) }
            error?.str("code")?.let { put("code", it) }
            error?.str("summary")?.let { put("summary", it.take(300)) }
            root?.get("report")?.jsonObject?.str("route")?.let { put("route", it) }
            expires?.let { put("expires", it) }
        }.toString(), "nzs.lastRefresh")
        if (trigger == "shortcut") defaults.setObject(now().toString(), "nzs.intent.lastRun")
        if (trigger == "background") defaults.setObject(now().toString(), "nzs.bgtask.lastRun")
    }

    private fun parse(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

    /** Days until this copy stops opening: the later of the embedded profile and the newest one known installed. */
    private suspend fun daysLeft(): Double? {
        val embedded = parse(obj(status())?.str("expires"))
        val installed = parse(defaults.stringForKey("nzs.installedExpiry"))
        val best = listOfNotNull(embedded, installed).maxOrNull() ?: return null
        return (best - now()).inWholeMinutes / (24.0 * 60.0)
    }

    // ---- Opening the app ----

    fun onOpen() {
        scope.launch {
            // Let the app finish starting (the toast host has to be on screen to show anything).
            delay(5_000)
            documents?.let { take(nzs_import_signing_key(it)) }
            settleUpdate()
            val last = defaults.doubleForKey("nzs.openCheckAt")
            val nowSeconds = now().epochSeconds.toDouble()
            if (nowSeconds - last < OPEN_CHECK_INTERVAL) return@launch
            defaults.setDouble(nowSeconds, "nzs.openCheckAt")
            val left = daysLeft() ?: return@launch
            if (left > RENEW_WHEN_DAYS_LEFT) return@launch
            val days = if (left < 1) "less than a day" else "${left.toInt()} days"
            if (SelfSigningKeychain.read() == null) {
                NuvioToastController.show("Nuvio Z stops opening in $days. Renew it in Settings › Advanced › Keep Nuvio Z working.", durationMillis = 8_000)
                return@launch
            }
            if (!vpnReachable()) {
                NuvioToastController.show("Connect LocalDevVPN so Nuvio Z can renew itself ($days left).", durationMillis = 8_000)
                defaults.setDouble(0.0, "nzs.openCheckAt")
                return@launch
            }
            val root = obj(refreshSaved("open"))
            if (root?.str("ok") != "true") {
                val error = root?.get("error") as? JsonObject
                val why = if (error?.str("kind") == "codeNeeded") "Apple wants a code: renew once in Settings › Advanced › Keep Nuvio Z working"
                else error?.str("summary") ?: "unknown error"
                NuvioToastController.show("Nuvio Z couldn’t renew itself: $why", durationMillis = 8_000)
                defaults.setDouble(0.0, "nzs.openCheckAt")
            }
        }
    }

    /** An update that was installing when the app last ran: iOS replaced the app, so it worked if the signature moved. */
    private suspend fun settleUpdate() {
        val last = obj(defaults.stringForKey("nzs.lastUpdate")) ?: return
        if (last.str("state") != "installing") return
        val before = last.str("embeddedBefore")
        val after = obj(status())?.str("expires")
        val installed = after != null && after != before
        defaults.setObject(buildJsonObject {
            put("at", now().toString())
            put("state", if (installed) "installed" else "interrupted")
            put("startedAt", last.str("at"))
            put("embeddedBefore", before)
            put("embeddedAfter", after)
        }.toString(), "nzs.lastUpdate")
        if (installed) {
            updateTestIpa()?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) }
            NuvioToastController.show("Update installed: this copy is now signed until ${day(after)}.", durationMillis = 6_000)
        }
    }

    // ---- The test report ----

    private fun day(iso: String?): String = iso?.replace('T', ' ')?.take(16)?.plus(" UTC") ?: "unknown"

    private fun mask(email: String): String {
        val (name, domain) = email.split('@', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return "${name.take(1)}***@$domain"
    }

    override suspend fun testReport(): String {
        val number = defaults.integerForKey("nzs.reportCount") + 1
        defaults.setInteger(number, "nzs.reportCount")
        val info = NSBundle.mainBundle.infoDictionary
        val version = info?.get("CFBundleShortVersionString") as? String
        val build = info?.get("CFBundleVersion") as? String
        val name = info?.get("CFBundleDisplayName") as? String ?: "Nuvio Z"
        val lines = mutableListOf<String>()
        lines += "Nuvio Z self-signing report #$number"
        lines += "$name $version ($build) on iOS ${UIDevice.currentDevice.systemVersion}, signer ${take(nzs_version())}"
        lines += "Time ${now()}"

        val status = obj(status())
        lines += "This copy's own signature (embedded profile): expires ${day(status?.str("expires"))}; bundle ids ${status?.get("bundleIds")?.let { (it as? JsonArray)?.size } ?: 0}"

        val vpn = vpnReachable()
        lines += "LocalDevVPN reachable: ${if (vpn) "yes" else "no"}"
        if (vpn) {
            val probe = obj(probe())
            val routes = probe?.get("probe")?.jsonObject?.get("routes") as? JsonArray
            lines += if (routes == null) "Routes: ${probe?.str("error") ?: "no result"}"
            else "Routes: " + routes.joinToString(" | ") { element ->
                val route = element.jsonObject
                val label = route.str("route")
                if (route.str("ok") == "true") "$label works, ${route.str("apps")} apps (${route.str("millis")} ms)"
                else "$label failed: ${route.str("error")} (${route.str("millis")} ms)"
            }
            val installed = obj(installedProfiles())
            lines += if (installed != null && installed.str("ok") == "true") {
                "Installed profiles (via ${installed.str("route")}): " + (installed["profiles"] as JsonArray).joinToString("; ") { p ->
                    val o = p.jsonObject
                    "${o.str("bundleId")?.substringAfterLast(".nuvio.app.z")?.ifEmpty { "app" } ?: "?"} ${day(o.str("expires"))}"
                }
            } else "Installed profiles: ${installed?.str("error") ?: "no result"}"
        } else {
            lines += "Routes and installed profiles: skipped (LocalDevVPN not reachable)"
            lines += "Last known installed profile: ${day(defaults.stringForKey("nzs.installedExpiry"))}"
        }
        daysLeft()?.let { lines += "Days left: ${(it * 10).toInt() / 10.0}" }

        val account = SelfSigningKeychain.read()
        val signing = obj(take(nzs_signing_state(account?.email)))
        lines += "Apple Account saved in Keychain: ${account?.let { "yes (${mask(it.email)})" } ?: "no"}"
        lines += "Signing key from the installer: ${if (signing?.str("signingKey") == "true") "yes" else "no"}; sign-in identity on this iPhone: ${if (signing?.str("anisette") == "true") "yes" else "no"}"

        val last = obj(lastRefresh())
        lines += if (last == null) "Last renew: never" else buildString {
            append("Last renew: ${last.str("at")} by ${last.str("trigger")}: ")
            if (last.str("ok") == "true") append("ok via ${last.str("route")}, app now expires ${day(last.str("expires"))}")
            else append("failed (${last.str("kind")}${last.str("code")?.let { " $it" } ?: ""}) ${last.str("summary")}")
        }

        val intentCompiled = defaults.boolForKey("nzs.intent.compiled")
        lines += "Shortcuts action \"Refresh Nuvio Z\": ${if (intentCompiled) "in this build" else "NOT in this build"}; last run ${defaults.stringForKey("nzs.intent.lastRun") ?: "never"}"
        val submitted = defaults.stringForKey("nzs.bgtask.submittedAt")
        val bgError = defaults.stringForKey("nzs.bgtask.error")
        lines += "Background refresh: ${if (submitted != null) "scheduled at $submitted" else "not scheduled"}${bgError?.let { " (error: $it)" } ?: ""}; last run ${defaults.stringForKey("nzs.bgtask.lastRun") ?: "never"}"

        val ipa = updateTestIpa()
        val size = ipa?.let {
            when (val v = NSFileManager.defaultManager.attributesOfItemAtPath(it, null)?.get(NSFileSize)) {
                is Number -> v.toLong()
                is NSNumber -> v.longLongValue
                else -> null
            }
        }
        lines += "Update test IPA: ${if (ipa != null) "present (${(size ?: 0) / 1_000_000} MB)" else "absent"}"
        val update = obj(defaults.stringForKey("nzs.lastUpdate"))
        lines += "Last update: ${update?.let { u ->
            when (u.str("state")) {
                "installed" -> "installed (started ${u.str("startedAt")}; signature ${day(u.str("embeddedBefore"))} -> ${day(u.str("embeddedAfter"))})"
                "interrupted" -> "did not finish (started ${u.str("startedAt")}; signature unchanged)"
                "failed" -> "failed: ${(u["result"] as? JsonObject)?.get("error")?.let { e -> (e as? JsonObject)?.let { "${it.str("kind")} ${it.str("summary")}" } ?: e.toString() }}"
                else -> u.str("state")
            }
        } ?: "never"}"
        return lines.joinToString("\n")
    }
}
