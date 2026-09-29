package com.nuvio.app.core.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.setup.SETUP_WIZARD_REVISION
import com.nuvio.app.features.setup.SetupProfileFlags
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The one-time import of a profile's platform-neutral settings from the other platform family - the
 * rules are `CrossFamilyImportRules`; this is the part that talks to the backend and the blob.
 *
 * **Where it runs:** inside the app gate's profile-switching phase, right after the ordinary
 * `SyncManager.pullAllForProfile`, while the loading indicator is already up - so the gate never
 * shows Initial Setup for a moment and then swaps it for Device Setup.
 *
 * **When:** a signed-in, non-anonymous profile whose own family has never stored a settings blob
 * for it, which has not finished setup locally either, and whose other family has finished setup.
 * It therefore happens at most once per profile per family: the push at the end creates the blob
 * whose absence is the trigger.
 *
 * **Order:** settings first, then the revision, then the arrival flag, then the push. A failure
 * part-way leaves a profile with no revision, which simply gets Initial Setup with whatever was
 * already applied preselected - never a profile that looks set up and was not.
 */
internal object CrossFamilySettingsImport {
    private val log = Logger.withTag("CrossFamilySettingsImport")

    suspend fun importIfArriving(profileId: Int): CrossFamilyImportOutcome {
        val auth = AuthRepository.state.value
        if (auth !is AuthState.Authenticated || auth.isAnonymous) return CrossFamilyImportOutcome.NotEligible
        PlayerSettingsRepository.ensureLoaded()
        if (PlayerSettingsRepository.uiState.value.setupWizardCompletedRevision >= CrossFamilyImportRules.MIN_IMPORTABLE_REVISION) {
            return CrossFamilyImportOutcome.FamilyAlreadyUsed
        }
        val ownFamily = ProfileSettingsSync.ownFamilyPlatform
        val otherFamily = CrossFamilyImportRules.otherFamily(ownFamily) ?: return CrossFamilyImportOutcome.NotEligible

        val ownBlob = ProfileSettingsSync.fetchSettingsBlobJson(profileId, ownFamily).getOrElse { error ->
            log.w(error) { "own-family read failed for profile $profileId" }
            return CrossFamilyImportOutcome.TransportFailure
        }
        if (ownBlob != null) return CrossFamilyImportOutcome.FamilyAlreadyUsed

        val otherBlob = ProfileSettingsSync.fetchSettingsBlobJson(profileId, otherFamily).getOrElse { error ->
            log.w(error) { "$otherFamily read failed for profile $profileId" }
            return CrossFamilyImportOutcome.TransportFailure
        } ?: return CrossFamilyImportOutcome.NothingToImport

        val revision = CrossFamilyImportRules.importedRevision(
            otherFamilyRevision = otherFamilySetupRevision(otherBlob),
            currentRevision = SETUP_WIZARD_REVISION,
        ) ?: return CrossFamilyImportOutcome.NothingToImport

        val merged = mergeCrossFamilyBlob(local = ProfileSettingsSync.exportSettingsBlobJson(), other = otherBlob)
        if (!ProfileSettingsSync.applyImportedSettingsBlobJson(profileId, merged)) {
            return CrossFamilyImportOutcome.NothingToImport
        }
        PlayerSettingsRepository.markSetupWizardCompleted(revision)
        SetupProfileFlags.setArrivalPending(profileId, true)
        ProfileSettingsSync.pushCurrentProfileToRemote()
        log.i { "profile $profileId arrived from $otherFamily at revision $revision" }
        return CrossFamilyImportOutcome.Imported
    }

    private fun otherFamilySetupRevision(blob: JsonObject): Int? =
        (blob.features()["player_settings"] as? JsonObject)?.decodeSyncInt(CrossFamilyImportRules.SETUP_REVISION_KEY)
}

/**
 * The profile-switch gate's ordering seam. The ordinary full-pull API is asynchronous, but setup
 * cannot inspect the family blobs until that pull has completed. Keeping the sequence here makes
 * the no-flash guarantee executable in a unit test and keeps an importer exception from sending
 * the user back to profile selection.
 */
internal suspend fun pullThenImportCrossFamilySettings(
    profileId: Int,
    pullAll: suspend (Int) -> Unit,
    importSettings: suspend (Int) -> CrossFamilyImportOutcome,
): CrossFamilyImportOutcome {
    pullAll(profileId)
    return try {
        importSettings(profileId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        CrossFamilyImportOutcome.TransportFailure
    }
}

private fun JsonObject.features(): JsonObject = this["features"] as? JsonObject ?: JsonObject(emptyMap())

/**
 * This family's blob with the other family's allowlisted settings laid over it. Pure over its two
 * inputs; everything not named by a rule - and the blob's own `version` - stays [local]'s.
 */
internal fun mergeCrossFamilyBlob(local: JsonObject, other: JsonObject): JsonObject {
    val localFeatures = local.features()
    val otherFeatures = other.features()
    val fieldNames = localFeatures.keys + otherFeatures.keys
    val mergedFeatures = fieldNames.associateWith { field ->
        val mine = localFeatures[field]
        val theirs = otherFeatures[field]
        mergeCrossFamilyField(CrossFamilyImportRules.rule(field), mine, theirs) ?: mine
    }.filterValues { it != null }.mapValues { it.value!! }
    return JsonObject(local + ("features" to JsonObject(mergedFeatures)))
}

private val payloadJson = Json { ignoreUnknownKeys = true }

private fun mergeCrossFamilyField(rule: CrossFamilyFeatureRule, mine: JsonElement?, theirs: JsonElement?): JsonElement? {
    if (theirs == null) return mine
    return when (rule.mode) {
        CrossFamilyImportMode.Skip -> mine
        CrossFamilyImportMode.Whole -> theirs
        CrossFamilyImportMode.OnlyKeys, CrossFamilyImportMode.AllButKeys -> {
            val keep: (String) -> Boolean = if (rule.mode == CrossFamilyImportMode.OnlyKeys) {
                { it in rule.keys }
            } else {
                { it !in rule.keys }
            }
            when (theirs) {
                is JsonObject -> overlay(mine as? JsonObject ?: JsonObject(emptyMap()), theirs, keep)
                // Several legacy payloads are a JSON object stored as a string.
                is JsonPrimitive -> {
                    val theirObject = theirs.contentOrNull?.let(::parseObject) ?: return mine
                    val mineObject = (mine as? JsonPrimitive)?.contentOrNull?.let(::parseObject) ?: JsonObject(emptyMap())
                    JsonPrimitive(overlay(mineObject, theirObject, keep).toString())
                }
                else -> mine
            }
        }
    }
}

private fun overlay(base: JsonObject, over: JsonObject, keep: (String) -> Boolean): JsonObject =
    JsonObject(base + over.filterKeys(keep))

private fun parseObject(text: String): JsonObject? =
    text.takeIf { it.isNotBlank() }?.let { runCatching { payloadJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
