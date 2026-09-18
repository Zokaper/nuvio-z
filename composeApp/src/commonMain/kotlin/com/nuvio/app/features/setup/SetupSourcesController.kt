package com.nuvio.app.features.setup

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddAddonResult
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/** Which part of the Sources step is showing. Saved by name; carries nothing secret. */
internal enum class SetupSourcesMode { Choice, Recommended, Manual }

/**
 * Everything the Sources step draws.
 *
 * ⚠ [torBoxApiKey] is the only secret, and it is **never** in a saveable, a log, or [toString]. It is
 * blanked in the same state write that ends a setup attempt, success or failure.
 */
internal data class SetupSourcesState(
    val mode: SetupSourcesMode = SetupSourcesMode.Choice,
    val torBoxApiKey: String = "",
    val sourceLanguageCode: String = "en",
    val subtitleLanguageCode: String = "en",
    val busy: Boolean = false,
    val recommendedFailure: RecommendedSourceFailure? = null,
    val recommendedFailureDetail: String? = null,
    /** Set once sources were configured during this visit, by either path. */
    val configuredName: String? = null,
    val recovery: AioStreamsRecovery? = null,
    val manualUrl: String = "",
    val manualError: String? = null,
) {
    override fun toString(): String =
        "SetupSourcesState(mode=$mode, torBoxApiKey=${if (torBoxApiKey.isEmpty()) "empty" else "redacted"}, " +
            "source=$sourceLanguageCode, subtitle=$subtitleLanguageCode, busy=$busy, " +
            "failure=$recommendedFailure, configured=$configuredName, manualError=$manualError)"
}

/**
 * The Sources step's behaviour, outside Compose so every path is testable.
 *
 * ## Duplicate installs
 *
 * Three ways a second copy could appear, each closed here:
 *
 * 1. **A double press.** Both setup actions take [inFlight] with `tryLock`; the second press returns
 *    without doing anything, so there is never a second config created on AIOStreams.
 * 2. **Re-running the recommended setup** - to change a language or a key. The new config is
 *    installed first and only then are earlier Nuvio Z recommended installs from the same instance
 *    removed, so a failure never leaves the profile with no sources. Anyone else's addon, AIOStreams
 *    or not, is left alone: "use recommended instead" must not delete a setup the user built.
 * 3. **A URL that is already installed.** Answered from the installed list before calling the
 *    installer, which would otherwise report it as an error.
 */
internal class SetupSourcesController(
    private val createConfig: suspend (RecommendedSourceRequest) -> AioStreamsConfigResult =
        AioStreamsRecommendedSetup()::createConfig,
    private val install: suspend (String) -> AddAddonResult = AddonRepository::addAddon,
    private val remove: (String) -> Unit = AddonRepository::removeAddon,
    private val installedAddons: () -> List<ManagedAddon> = { AddonRepository.uiState.value.addons },
    /** Keeps the install's AIOStreams credentials, so template changes can reach it later. */
    private val rememberCredentials: (manifestUrl: String, AioStreamsRecovery) -> Unit = ::rememberAioStreamsCredentials,
    private val instanceBaseUrl: String = AIOSTREAMS_INSTANCE_BASE_URL,
    initial: SetupSourcesState = SetupSourcesState(),
) {
    private val log = Logger.withTag("SetupSources")
    private val inFlight = Mutex()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SetupSourcesState> = _state.asStateFlow()

    private var languagesChosen = false

    /** Seeds the pickers from the Language step's answers, until the user picks one here. */
    fun seedLanguages(preferredAudioLanguage: String?, preferredSubtitleLanguage: String?) {
        if (languagesChosen) return
        val source = aioStreamsLanguageCodeFor(preferredAudioLanguage) ?: "en"
        val subtitle = aioStreamsLanguageCodeFor(preferredSubtitleLanguage) ?: source
        _state.update { it.copy(sourceLanguageCode = source, subtitleLanguageCode = subtitle) }
    }

    fun chooseRecommended() = _state.update { it.copy(mode = SetupSourcesMode.Recommended) }

    fun chooseManual() = _state.update { it.copy(mode = SetupSourcesMode.Manual) }

    /** Back to the question, dropping anything typed. */
    fun backToChoice() = _state.update {
        it.copy(
            mode = SetupSourcesMode.Choice,
            torBoxApiKey = "",
            recommendedFailure = null,
            recommendedFailureDetail = null,
            manualError = null,
        )
    }

    fun setTorBoxApiKey(value: String) = _state.update {
        it.copy(torBoxApiKey = value, recommendedFailure = null, recommendedFailureDetail = null)
    }

    fun setSourceLanguage(code: String) {
        if (code !in AioStreamsLanguageByCode) return
        languagesChosen = true
        _state.update { it.copy(sourceLanguageCode = code) }
    }

    fun setSubtitleLanguage(code: String) {
        if (code !in AioStreamsLanguageByCode) return
        languagesChosen = true
        _state.update { it.copy(subtitleLanguageCode = code) }
    }

    fun setManualUrl(value: String) = _state.update { it.copy(manualUrl = value, manualError = null) }

    /** Called when the Sources step leaves the screen: nothing sensitive outlives it. */
    fun forgetSensitive() = _state.update { it.copy(torBoxApiKey = "", recovery = null) }

    suspend fun setUpRecommended() {
        if (!inFlight.tryLock()) return
        try {
            val snapshot = _state.value
            // ⚠ Read once and blanked in the very next write, before any network call, so the key
            // is not sitting in observable UI state for the length of a slow AIOStreams validation.
            val key = TorBoxApiKey(snapshot.torBoxApiKey)
            _state.update {
                it.copy(torBoxApiKey = "", busy = true, recommendedFailure = null, recommendedFailureDetail = null)
            }

            val request = RecommendedSourceRequest(
                torBoxApiKey = key,
                sourceLanguage = aioStreamsLanguageForCode(snapshot.sourceLanguageCode) ?: "English",
                subtitleLanguage = aioStreamsLanguageForCode(snapshot.subtitleLanguageCode) ?: "English",
            )
            val result = try {
                createConfig(request)
            } catch (error: CancellationException) {
                _state.update { it.copy(busy = false) }
                throw error
            } catch (_: Throwable) {
                // Deliberately not logged or shown: an unexpected exception's message is not ours to
                // vouch for, and this one was thrown while the key was in hand.
                AioStreamsConfigResult.Failed(RecommendedSourceFailure.InstanceUnavailable)
            }
            when (val created = result) {
                is AioStreamsConfigResult.Failed -> {
                    log.i { "Recommended source setup failed: ${created.reason}" }
                    fail(created.reason, created.detail)
                }
                is AioStreamsConfigResult.Created -> {
                    val previous = previousRecommendedInstalls(except = created.manifestUrl)
                    val installed = installOnce(created.manifestUrl)
                    if (installed is SetupSourceInstallResult.Failed) {
                        log.i { "Recommended source install failed" }
                        fail(RecommendedSourceFailure.InstallFailed, redactSetupError(installed.message))
                        return
                    }
                    previous.forEach(remove)
                    rememberCredentials(created.manifestUrl, created.recovery)
                    val name = (installed as SetupSourceInstallResult.Installed).addonName
                    _state.update {
                        it.copy(busy = false, configuredName = name, recovery = created.recovery)
                    }
                }
            }
        } finally {
            inFlight.unlock()
        }
    }

    suspend fun installManual(emptyUrlMessage: String) {
        if (!inFlight.tryLock()) return
        try {
            val url = _state.value.manualUrl
            _state.update { it.copy(busy = true, manualError = null) }
            when (val result = installOnce(url, emptyUrlMessage)) {
                is SetupSourceInstallResult.Installed -> _state.update {
                    it.copy(busy = false, manualUrl = "", configuredName = result.addonName)
                }
                is SetupSourceInstallResult.Failed -> _state.update {
                    it.copy(busy = false, manualError = result.message)
                }
            }
        } finally {
            inFlight.unlock()
        }
    }

    private fun fail(reason: RecommendedSourceFailure, detail: String?) = _state.update {
        it.copy(torBoxApiKey = "", busy = false, recommendedFailure = reason, recommendedFailureDetail = detail)
    }

    private suspend fun installOnce(url: String, emptyUrlMessage: String = ""): SetupSourceInstallResult {
        installedAddons().firstOrNull { it.manifestUrl.equals(url.trim(), ignoreCase = false) }?.let { existing ->
            return SetupSourceInstallResult.Installed(existing.displayTitle)
        }
        return installSetupSource(url, emptyUrlMessage, install)
    }

    private fun previousRecommendedInstalls(except: String): List<String> {
        val prefix = instanceBaseUrl.trimEnd('/') + "/stremio/"
        return installedAddons()
            .filter { addon ->
                addon.manifestUrl != except &&
                    addon.manifestUrl.startsWith(prefix) &&
                    addon.manifest?.name in NUVIO_Z_RECOMMENDED_ADDON_NAMES
            }
            .map(ManagedAddon::manifestUrl)
    }
}
