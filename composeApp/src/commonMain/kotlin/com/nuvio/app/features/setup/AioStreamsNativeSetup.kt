package com.nuvio.app.features.setup

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Creates the Nuvio Z recommended AIOStreams configuration without a browser.
 *
 * ## What AIOStreams actually does, read from its source rather than guessed
 *
 * Verified against `Viren070/AIOStreams` at `v2.34.0`, which is what the public instance's
 * `GET /api/v1/status` reports running:
 *
 * - **Templates are resolved entirely in the browser.** `applyTemplateConditionals` substitutes
 *   `{{inputs.<id>}}` and `resolveCredentialRefs` substitutes `{{services.<id>.<key>}}`; the server
 *   never sees a template, only the finished config. [AioStreamsTemplateResolver] is the native
 *   port of the subset the Nuvio Z template uses, and refuses anything else.
 * - **The browser also drops presets the instance does not offer** (`filterUnavailablePresets`),
 *   using `settings.presets[].ID` from the status endpoint. Done here too.
 * - **`POST /api/v1/user` with `{config, password}` creates the config** and answers
 *   `{success, data: {uuid, encryptedPassword}}`, or `{success: false, error: {code, message}}`.
 *   The server validates the config first, which is where a bad TorBox key surfaces.
 * - **The manifest URL is `{base}/stremio/{uuid}/{encryptedPassword}/manifest.json`** - the
 *   frontend's `buildManifestUrl`. `encryptedPassword` is URL-safe base64 encrypted with the
 *   instance's own secret, so the URL works on its own without the plaintext password.
 *
 * ## The password
 *
 * AIOStreams will not create a config without one (`createUser` rejects under six characters), so
 * one is generated: 256 bits from `Uuid.random()`, which is backed by a CSPRNG on every platform.
 * Nuvio never needs it again - streaming only needs the manifest URL - and it is not stored. It is
 * handed back once in [AioStreamsConfigResult.Created] so the success screen can offer it on
 * request to someone who wants to edit the config on the AIOStreams website.
 *
 * ## The TorBox key
 *
 * Lives in [TorBoxApiKey], whose `toString` never prints it, is written into one request body, and
 * is never logged, persisted or put in an exception message. A server error message is passed
 * through [redactSetupError] before anything shows it, because AIOStreams' validation errors can
 * quote addon URLs that embed the user's credentials.
 */
internal class AioStreamsRecommendedSetup(
    private val instanceBaseUrl: String = AIOSTREAMS_INSTANCE_BASE_URL,
    private val templateUrl: String = NUVIO_Z_AIOSTREAMS_TEMPLATE_URL,
    private val http: AioStreamsHttp = DefaultAioStreamsHttp,
    private val generatePassword: () -> String = ::generateAioStreamsPassword,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createConfig(request: RecommendedSourceRequest): AioStreamsConfigResult {
        val apiKey = request.torBoxApiKey.value.trim()
        if (apiKey.isEmpty()) return AioStreamsConfigResult.Failed(RecommendedSourceFailure.MissingKey)

        val template = try {
            val response = http.request("GET", templateUrl, emptyMap(), "")
            if (response.status !in 200..299) {
                return AioStreamsConfigResult.Failed(RecommendedSourceFailure.TemplateUnavailable)
            }
            json.parseToJsonElement(response.body).jsonObject
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return AioStreamsConfigResult.Failed(RecommendedSourceFailure.TemplateUnavailable)
        }

        val availablePresets = try {
            val response = http.request("GET", "$base/api/v1/status", emptyMap(), "")
            if (response.status !in 200..299) {
                return AioStreamsConfigResult.Failed(RecommendedSourceFailure.InstanceUnavailable)
            }
            availablePresetTypes(json.parseToJsonElement(response.body).jsonObject)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return AioStreamsConfigResult.Failed(RecommendedSourceFailure.InstanceUnavailable)
        }

        val config = try {
            AioStreamsTemplateResolver.resolve(
                template = template,
                inputs = mapOf(
                    "sourceLanguages" to listOf(request.sourceLanguage),
                    "subtitleLanguages" to listOf(request.subtitleLanguage),
                ),
                credentials = mapOf("torbox.apiKey" to apiKey),
                availablePresetTypes = availablePresets,
            )
        } catch (_: AioStreamsTemplateException) {
            // ⚠ Not the exception's message: nothing in it is shown or logged, so a future message
            // that happened to quote a resolved value could not leak the key through here.
            return AioStreamsConfigResult.Failed(RecommendedSourceFailure.TemplateUnsupported)
        }

        val password = generatePassword()
        val body = buildJsonObject {
            put("config", config)
            put("password", JsonPrimitive(password))
        }.toString()

        val response = try {
            http.request(
                "POST",
                "$base/api/v1/user",
                mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                body,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return AioStreamsConfigResult.Failed(RecommendedSourceFailure.InstanceUnavailable)
        }

        val parsed = runCatching { json.parseToJsonElement(response.body).jsonObject }.getOrNull()
        val data = parsed?.get("data") as? JsonObject
        val uuid = data?.stringOrNull("uuid")
        val encryptedPassword = data?.stringOrNull("encryptedPassword")
        if (response.status in 200..299 && uuid != null && encryptedPassword != null) {
            return AioStreamsConfigResult.Created(
                manifestUrl = "$base/stremio/$uuid/$encryptedPassword/manifest.json",
                addonName = config.stringOrNull("addonName") ?: NUVIO_Z_RECOMMENDED_ADDON_NAME,
                recovery = AioStreamsRecovery(
                    configureUrl = "$base/stremio/configure",
                    uuid = uuid,
                    password = password,
                ),
            )
        }

        val serverMessage = (parsed?.get("error") as? JsonObject)?.stringOrNull("message")
        return AioStreamsConfigResult.Failed(
            reason = when (response.status) {
                429 -> RecommendedSourceFailure.RateLimited
                in 400..499 -> RecommendedSourceFailure.Rejected
                else -> RecommendedSourceFailure.InstanceUnavailable
            },
            detail = serverMessage?.let { redactSetupError(it, apiKey, password) },
        )
    }

    private val base: String get() = instanceBaseUrl.trimEnd('/')

    private fun availablePresetTypes(status: JsonObject): Set<String> {
        val presets = status["data"]?.jsonObject?.get("settings")?.jsonObject?.get("presets")?.jsonArray
            ?: return emptySet()
        return presets.mapNotNull { element ->
            val preset = element as? JsonObject ?: return@mapNotNull null
            val disabled = (preset["DISABLED"] as? JsonObject)?.get("disabled")
                ?.jsonPrimitive?.contentOrNull == "true"
            preset.stringOrNull("ID")?.takeUnless { disabled }
        }.toSet()
    }
}

/** The one network seam, so tests can answer for AIOStreams and GitHub. */
internal fun interface AioStreamsHttp {
    suspend fun request(method: String, url: String, headers: Map<String, String>, body: String): RawHttpResponse
}

internal val DefaultAioStreamsHttp = AioStreamsHttp { method, url, headers, body ->
    // The status payload is ~400 KB on v2.34.0 and grows with every preset AIOStreams adds.
    httpRequestRaw(method, url, headers, body, maxResponseBodyBytes = 4 * 1024 * 1024)
}

/** A TorBox API key. Exists so that no `toString`, log line or data-class dump can print one. */
internal class TorBoxApiKey(val value: String) {
    override fun toString(): String = "TorBoxApiKey(redacted)"
    override fun equals(other: Any?): Boolean = other is TorBoxApiKey && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

internal data class RecommendedSourceRequest(
    val torBoxApiKey: TorBoxApiKey,
    /** An AIOStreams language name, e.g. `English` - see [aioStreamsLanguageForCode]. */
    val sourceLanguage: String,
    val subtitleLanguage: String,
)

internal sealed interface AioStreamsConfigResult {
    data class Created(
        val manifestUrl: String,
        val addonName: String,
        val recovery: AioStreamsRecovery,
    ) : AioStreamsConfigResult

    data class Failed(
        val reason: RecommendedSourceFailure,
        /** Already redacted. Null when AIOStreams did not say anything useful. */
        val detail: String? = null,
    ) : AioStreamsConfigResult
}

internal enum class RecommendedSourceFailure {
    MissingKey,
    TemplateUnavailable,
    TemplateUnsupported,
    InstanceUnavailable,
    Rejected,
    RateLimited,
    InstallFailed,
}

/**
 * What someone needs to edit the config on the AIOStreams website, and nothing Nuvio keeps.
 *
 * Held in memory for as long as the Sources step is on screen and dropped when it leaves.
 */
internal data class AioStreamsRecovery(
    val configureUrl: String,
    val uuid: String,
    val password: String,
) {
    override fun toString(): String = "AioStreamsRecovery(uuid=$uuid, password=redacted)"
}

/** Persists [recovery] for [manifestUrl]; see [AioStreamsCredentialStorage]. */
internal fun rememberAioStreamsCredentials(manifestUrl: String, recovery: AioStreamsRecovery) {
    val value = buildJsonObject {
        put("manifestUrl", JsonPrimitive(manifestUrl))
        put("configureUrl", JsonPrimitive(recovery.configureUrl))
        put("uuid", JsonPrimitive(recovery.uuid))
        put("password", JsonPrimitive(recovery.password))
    }.toString()
    runCatching { AioStreamsCredentialStorage.save(recovery.uuid, value) }
}

internal const val NUVIO_Z_RECOMMENDED_TEMPLATE_ID = "zokaper.nuvio-z-recommended"
internal const val NUVIO_Z_RECOMMENDED_ADDON_NAME = "AIOStreams Z"

/**
 * Every name the recommended install has shipped under, so re-running setup still replaces an install
 * made before the 2026-09-17 rename ("Nuvio Z Recommended" read as if one source were endorsed).
 */
internal val NUVIO_Z_RECOMMENDED_ADDON_NAMES = setOf(NUVIO_Z_RECOMMENDED_ADDON_NAME, "Nuvio Z Recommended")

@OptIn(ExperimentalUuidApi::class)
internal fun generateAioStreamsPassword(): String =
    Uuid.random().toHexString() + Uuid.random().toHexString()

/**
 * Makes an AIOStreams error safe to show.
 *
 * Strips every URL - a failed preset's manifest URL can carry that addon's whole config, TorBox key
 * included, in an encoding a plain substring check would miss - then any literal secret that is
 * still there.
 */
internal fun redactSetupError(message: String, vararg secrets: String): String {
    var redacted = message.replace(Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://\S+"""), "[link]")
    secrets.filter { it.length >= 4 }.forEach { secret -> redacted = redacted.replace(secret, "[redacted]") }
    return redacted.take(300)
}

internal class AioStreamsTemplateException(message: String) : Exception(message)

/**
 * The Nuvio Z template, resolved the way AIOStreams' own template wizard resolves it.
 *
 * ⚠ **A subset, and it fails closed.** The frontend also understands `__if`, `__value`, `__switch`
 * and `__remove`. The Nuvio Z template uses none of them, and a partial reimplementation that
 * silently kept a conditional block would create a config the template's author never wrote - so
 * meeting one throws, and the user gets "set up manually" rather than a wrong source setup. Add
 * support here in the same commit as the template change that needs it.
 */
internal object AioStreamsTemplateResolver {

    /**
     * Fields `DefaultUserData` gives every browser config and the template does not set.
     *
     * The browser applies a template as `{...DefaultUserData, ...template.config}` - a shallow
     * merge - so a config created from the template in a browser carries these. Without them a
     * native config would sort differently from the one the template was tested as.
     */
    private val browserDefaults: JsonObject = Json.parseToJsonElement(
        """
        {
          "formatter": { "id": "gdrive" },
          "sortCriteria": { "global": [
            { "key": "cached", "direction": "desc" },
            { "key": "library", "direction": "desc" },
            { "key": "resolution", "direction": "desc" },
            { "key": "quality", "direction": "desc" },
            { "key": "streamExpressionScore", "direction": "desc" },
            { "key": "regexPatterns", "direction": "desc" },
            { "key": "streamType", "direction": "desc" },
            { "key": "visualTag", "direction": "desc" },
            { "key": "audioTag", "direction": "desc" },
            { "key": "audioChannel", "direction": "desc" },
            { "key": "encode", "direction": "desc" },
            { "key": "language", "direction": "desc" },
            { "key": "subtitle", "direction": "desc" },
            { "key": "size", "direction": "desc" }
          ] },
          "autoPlay": { "enabled": true, "method": "matchingFile", "attributes": ["resolution", "quality", "releaseGroup"] },
          "cacheAndPlay": { "enabled": false, "streamTypes": ["usenet"] },
          "ageRangeTypes": ["usenet"],
          "languageInference": { "enabled": true, "sources": [] },
          "regexOverrides": []
        }
        """.trimIndent(),
    ).jsonObject

    private val directiveKeys = setOf("__if", "__value", "__switch", "__remove")
    private val soleToken = Regex("""^\{\{(inputs|services)\.([^}]+)\}\}$""")
    private val anyToken = Regex("""\{\{(inputs|services)\.([^}]+)\}\}""")

    /**
     * @param inputs template input id to chosen values; each must be one of that input's options.
     * @param credentials `<serviceId>.<credentialKey>` to value.
     * @param availablePresetTypes preset types the instance offers; others are dropped.
     */
    fun resolve(
        template: JsonObject,
        inputs: Map<String, List<String>>,
        credentials: Map<String, String>,
        availablePresetTypes: Set<String>,
    ): JsonObject {
        val metadata = template["metadata"] as? JsonObject
            ?: throw AioStreamsTemplateException("template has no metadata")
        if (metadata.stringOrNull("id") != NUVIO_Z_RECOMMENDED_TEMPLATE_ID) {
            throw AioStreamsTemplateException("not the Nuvio Z template")
        }
        val config = template["config"] as? JsonObject
            ?: throw AioStreamsTemplateException("template has no config")

        val inputValues = validateInputs(metadata, inputs)
        val selectedServices = (metadata["services"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

        val resolved = resolveElement(config, inputValues, selectedServices, credentials) as JsonObject
        val presets = (resolved["presets"] as? JsonArray)?.filter { preset ->
            (preset as? JsonObject)?.stringOrNull("type") in availablePresetTypes
        }
        if (presets.isNullOrEmpty()) throw AioStreamsTemplateException("no preset is available on this instance")

        return buildJsonObject {
            browserDefaults.forEach { (key, value) -> if (key !in resolved) put(key, value) }
            resolved.forEach { (key, value) -> put(key, if (key == "presets") JsonArray(presets) else value) }
            // What the browser records, so AIOStreams can offer template updates for this config.
            put(
                "appliedTemplates",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(NUVIO_Z_RECOMMENDED_TEMPLATE_ID))
                            metadata.stringOrNull("version")?.let { put("version", JsonPrimitive(it)) }
                            metadata.stringOrNull("sourceUrl")?.let { put("url", JsonPrimitive(it)) }
                        },
                    )
                },
            )
        }
    }

    private fun validateInputs(metadata: JsonObject, inputs: Map<String, List<String>>): Map<String, JsonElement> {
        val declared = (metadata["inputs"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        return declared.associate { input ->
            val id = input.stringOrNull("id") ?: throw AioStreamsTemplateException("input without id")
            val type = input.stringOrNull("type")
            if (type != "multi-select") throw AioStreamsTemplateException("unsupported input type")
            val offered = (input["options"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.stringOrNull("value") }
                .orEmpty()
                .toSet()
            val chosen = inputs[id].orEmpty()
            val required = (input["required"] as? JsonPrimitive)?.contentOrNull == "true"
            if (required && chosen.isEmpty()) throw AioStreamsTemplateException("missing input")
            if (chosen.any { it !in offered }) throw AioStreamsTemplateException("value not offered")
            id to JsonArray(chosen.map(::JsonPrimitive))
        }
    }

    private fun resolveElement(
        element: JsonElement,
        inputs: Map<String, JsonElement>,
        services: List<String>,
        credentials: Map<String, String>,
    ): JsonElement = when (element) {
        is JsonObject -> {
            if (element.keys.any { it in directiveKeys }) {
                throw AioStreamsTemplateException("template directive not supported natively")
            }
            JsonObject(element.mapValues { (_, value) -> resolveElement(value, inputs, services, credentials) })
        }
        is JsonArray -> JsonArray(
            element.flatMap { item ->
                val resolved = resolveElement(item, inputs, services, credentials)
                // A string item that was a sole `{{inputs.x}}` token and resolved to an array is
                // spread into its parent, exactly like the frontend.
                if (item is JsonPrimitive && item.isString && resolved is JsonArray) resolved else listOf(resolved)
            },
        )
        is JsonPrimitive -> if (element.isString) resolveString(element.content, inputs, services, credentials) else element
        JsonNull -> element
    }

    private fun resolveString(
        value: String,
        inputs: Map<String, JsonElement>,
        services: List<String>,
        credentials: Map<String, String>,
    ): JsonElement {
        if (value == "{{services}}") return JsonArray(services.map(::JsonPrimitive))
        soleToken.matchEntire(value)?.let { match ->
            return resolveToken(match.groupValues[1], match.groupValues[2], inputs, services, credentials)
        }
        if (!value.contains("{{")) return JsonPrimitive(value)
        return JsonPrimitive(
            value.replace(anyToken) { match ->
                when (val token = resolveToken(match.groupValues[1], match.groupValues[2], inputs, services, credentials)) {
                    is JsonArray -> token.joinToString(",") { (it as? JsonPrimitive)?.content.orEmpty() }
                    is JsonPrimitive -> token.content
                    else -> ""
                }
            },
        )
    }

    private fun resolveToken(
        namespace: String,
        key: String,
        inputs: Map<String, JsonElement>,
        services: List<String>,
        credentials: Map<String, String>,
    ): JsonElement = when (namespace) {
        "inputs" -> inputs[key] ?: throw AioStreamsTemplateException("unknown input reference")
        else -> if (key.contains('.')) {
            JsonPrimitive(credentials[key] ?: throw AioStreamsTemplateException("missing credential"))
        } else {
            JsonPrimitive(key in services)
        }
    }
}

/**
 * The template's language names for the codes Nuvio stores.
 *
 * AIOStreams names languages in English (`"English"`, `"Latino"`), Nuvio stores ISO-style codes.
 * Every value here is one of the template's `sourceLanguages` options; the resolver re-checks that
 * against the fetched template, so a template that drops a language fails closed instead of
 * sending a value AIOStreams would ignore.
 */
internal val AioStreamsLanguageByCode: Map<String, String> = linkedMapOf(
    "ar" to "Arabic", "bn" to "Bengali", "bg" to "Bulgarian", "zh" to "Chinese", "hr" to "Croatian",
    "cs" to "Czech", "da" to "Danish", "nl" to "Dutch", "en" to "English", "et" to "Estonian",
    "fi" to "Finnish", "fr" to "French", "de" to "German", "el" to "Greek", "gu" to "Gujarati",
    "he" to "Hebrew", "hi" to "Hindi", "hu" to "Hungarian", "id" to "Indonesian", "it" to "Italian",
    "ja" to "Japanese", "kn" to "Kannada", "ko" to "Korean", "es-419" to "Latino", "lv" to "Latvian",
    "lt" to "Lithuanian", "ms" to "Malay", "ml" to "Malayalam", "mr" to "Marathi", "no" to "Norwegian",
    "fa" to "Persian", "pl" to "Polish", "pt" to "Portuguese", "pa" to "Punjabi", "ro" to "Romanian",
    "ru" to "Russian", "sr" to "Serbian", "sk" to "Slovak", "sl" to "Slovenian", "es" to "Spanish",
    "sv" to "Swedish", "ta" to "Tamil", "te" to "Telugu", "th" to "Thai", "tr" to "Turkish",
    "uk" to "Ukrainian", "vi" to "Vietnamese",
)

/** Regional variants Nuvio stores that AIOStreams folds into one name. */
private val AioStreamsLanguageAliases = mapOf("zh-CN" to "zh", "zh-TW" to "zh", "pt-BR" to "pt")

/** The code the Sources pickers should show for a stored language preference, or null. */
internal fun aioStreamsLanguageCodeFor(storedCode: String?): String? {
    if (storedCode.isNullOrBlank()) return null
    val code = AioStreamsLanguageAliases[storedCode] ?: storedCode
    return code.takeIf { it in AioStreamsLanguageByCode }
}

internal fun aioStreamsLanguageForCode(code: String): String? = AioStreamsLanguageByCode[code]

private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
