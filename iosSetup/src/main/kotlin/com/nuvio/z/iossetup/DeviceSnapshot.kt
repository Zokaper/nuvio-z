package com.nuvio.z.iossetup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The helper protocol version this app understands. Bump together with `PROTOCOL` in the helper. */
const val HELPER_PROTOCOL = 1

enum class TrustState { VALID, INVALID }

enum class PairingFileState { PRESENT, ABSENT, UNKNOWN, SIDESTORE_MISSING }

data class InstalledApp(val bundleId: String, val version: String)

/** What the helper could see on an attached iPhone. A null field means "could not be determined". */
data class DeviceSnapshot(
    val udidPrefix: String,
    val trust: TrustState,
    val iosVersion: String?,
    val developerMode: Boolean?,
    val sidestore: InstalledApp?,
    val localDevVpn: InstalledApp?,
    val nuvioStable: InstalledApp?,
    val nuvioDebug: InstalledApp?,
    /** False when the app list could not be read, so a null app means "unknown", not "absent". */
    val appsKnown: Boolean,
    val pairingFile: PairingFileState,
)

data class HelperStatus(
    val protocol: Int,
    val usbmuxdReachable: Boolean,
    val usbDeviceCount: Int,
    val device: DeviceSnapshot?,
    /** Raw per-probe failure reasons, for diagnostics only. Never shown as the primary message. */
    val errors: Map<String, String>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses one `status` line. Returns null when the output is not a protocol we understand. */
        fun parse(line: String): HelperStatus? = runCatching {
            val root = json.parseToJsonElement(line.trim()).jsonObject
            val protocol = root["protocol"]?.jsonPrimitive?.int ?: return null
            if (protocol != HELPER_PROTOCOL) return null
            val usbmuxd = root["usbmuxd"]?.jsonObject
            val errors = root["errors"]?.jsonObject
                ?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty()
            HelperStatus(
                protocol = protocol,
                usbmuxdReachable = usbmuxd?.get("reachable")?.jsonPrimitive?.booleanOrNull == true,
                usbDeviceCount = usbmuxd?.get("usbDevices")?.jsonPrimitive?.intOrNull ?: 0,
                device = root["device"]?.takeUnless { it is JsonNull }?.jsonObject?.let(::parseDevice),
                errors = errors,
            )
        }.getOrNull()

        private fun parseDevice(device: JsonObject): DeviceSnapshot {
            val apps = device["apps"]?.takeUnless { it is JsonNull }?.jsonObject
            fun app(key: String): InstalledApp? = apps?.get(key)?.takeUnless { it is JsonNull }?.jsonObject?.let {
                InstalledApp(
                    it["bundleId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    it["version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            return DeviceSnapshot(
                udidPrefix = device["udidPrefix"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                trust = if (device["trust"]?.jsonPrimitive?.contentOrNull == "valid") TrustState.VALID else TrustState.INVALID,
                iosVersion = device["iosVersion"]?.optionalString(),
                developerMode = device["developerMode"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.boolean,
                sidestore = app("sidestore"),
                localDevVpn = app("localDevVpn"),
                nuvioStable = app("nuvioStable"),
                nuvioDebug = app("nuvioDebug"),
                appsKnown = apps != null,
                pairingFile = when (device["pairingFile"]?.jsonPrimitive?.contentOrNull) {
                    "present" -> PairingFileState.PRESENT
                    "absent" -> PairingFileState.ABSENT
                    "sidestoreMissing" -> PairingFileState.SIDESTORE_MISSING
                    else -> PairingFileState.UNKNOWN
                },
            )
        }

        private fun JsonElement.optionalString(): String? = takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
    }
}
