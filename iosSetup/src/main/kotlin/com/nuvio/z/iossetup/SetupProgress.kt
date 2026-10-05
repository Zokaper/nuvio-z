package com.nuvio.z.iossetup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * v2 saved state. Deliberately small: what the phone looks like is re-probed on every launch, so only
 * the user's choices and the answers no probe can give are stored. Still contains no credentials.
 */
@Serializable
data class SetupProgress(
    val schemaVersion: Int = SCHEMA_VERSION,
    val channel: SetupChannel = SetupChannel.STABLE,
    val developerWarningAccepted: Boolean = false,
    /** Requirements the user vouched for because the computer could not check them. */
    val confirmed: Set<Requirement> = emptySet(),
    val repairMode: Boolean = false,
    val advancedDeviceOverride: Boolean = false,
    val setupCompleted: Boolean = false,
) {
    fun confirm(requirement: Requirement, value: Boolean = true): SetupProgress =
        copy(confirmed = if (value) confirmed + requirement else confirmed - requirement)

    companion object {
        const val SCHEMA_VERSION = 2

        private val json = Json { ignoreUnknownKeys = true }
        private val writer = Json { prettyPrint = true; encodeDefaults = true }

        /** Reads either schema from disk text. Returns null for anything unreadable. */
        fun decode(text: String): SetupProgress? = runCatching {
            val version = json.parseToJsonElement(text).jsonObject["schemaVersion"]?.jsonPrimitive?.int ?: 1
            if (version >= SCHEMA_VERSION) json.decodeFromString<SetupProgress>(text)
            else migrate(json.decodeFromString<SetupState>(text))
        }.getOrNull()

        /**
         * v1 stored which page the user reached and every confirmation they clicked. Of those, only the
         * three answers no probe can give survive; the rest is re-derived from the phone.
         */
        fun migrate(old: SetupState): SetupProgress {
            val carried = old.manualConfirmations.mapNotNull { step ->
                when (step) {
                    SetupStep.TRUST_PROFILE -> Requirement.PROFILE_TRUST
                    SetupStep.SIDESTORE_PRIME -> Requirement.SIDESTORE_READY
                    SetupStep.ADD_SOURCE -> Requirement.SOURCE_ADDED
                    else -> null
                }
            }.toSet()
            return SetupProgress(
                channel = old.channel,
                developerWarningAccepted = old.developerWarningAccepted,
                confirmed = carried,
                repairMode = old.repairMode,
                advancedDeviceOverride = old.advancedDeviceOverride,
                setupCompleted = old.setupCompleted,
            )
        }

        fun encode(progress: SetupProgress): String = writer.encodeToString(serializer(), progress)
    }
}
