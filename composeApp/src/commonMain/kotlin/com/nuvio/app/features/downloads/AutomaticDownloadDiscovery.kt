package com.nuvio.app.features.downloads

import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.streams.StreamParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class AutomaticAddonTarget(
    val manifestId: String,
    val manifestName: String,
    val manifestUrl: String,
    val logoUrl: String? = null,
    val addonOrder: Int = 0,
)

/**
 * Network boundary used only by preset/bulk discovery. Filtering occurs before
 * URL construction or any HTTP call, which prevents a disallowed addon from
 * learning what the user is downloading.
 */
object AutomaticDownloadDiscovery {
    const val MAX_CONCURRENT_EPISODE_DISCOVERIES = 3

    suspend fun discover(
        type: String,
        videoId: String,
        addons: List<AutomaticAddonTarget>,
        policySnapshot: DownloadSourcePolicy,
    ): List<DownloadSourceCandidate> = coroutineScope {
        val semaphore = Semaphore(MAX_CONCURRENT_EPISODE_DISCOVERIES)
        eligibleTargets(addons, policySnapshot)
            .map { target ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            fetchAddon(type, videoId, target, policySnapshot)
                        }.getOrDefault(emptyList())
                    }
                }
            }
            .awaitAll()
            .flatten()
    }

    internal fun eligibleTargets(
        addons: List<AutomaticAddonTarget>,
        policy: DownloadSourcePolicy,
    ): List<AutomaticAddonTarget> = addons.filter { target ->
        policy.allowsAddon(AddonSourceKey(target.manifestId, target.manifestUrl))
    }

    suspend fun verifyCandidateSize(candidate: DownloadSourceCandidate): DownloadSourceCandidate {
        val url = candidate.resolvedUrl ?: return candidate
        val requestHeaders = candidate.stream.behaviorHints.proxyHeaders?.request.orEmpty()
        val head = runCatching {
            httpRequestRaw(
                method = "HEAD",
                url = url,
                headers = requestHeaders,
                body = "",
                maxResponseBodyBytes = 1,
            )
        }.getOrNull()
        val headSize = head
            ?.takeIf { it.status in 200..399 }
            ?.headers
            ?.headerLong("content-length")

        val verifiedSize = headSize ?: runCatching {
            httpRequestRaw(
                method = "GET",
                url = url,
                headers = requestHeaders + ("Range" to "bytes=0-0"),
                body = "",
                maxResponseBodyBytes = 1,
            )
        }.getOrNull()
            ?.takeIf { it.status in 200..399 }
            ?.headers
            ?.let { headers ->
                headers.headerValue("content-range")
                    ?.substringAfterLast('/')
                    ?.trim()
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?: headers.headerLong("content-length")
            }

        return if (verifiedSize != null) {
            candidate.copy(facts = candidate.facts.withVerifiedSize(verifiedSize))
        } else {
            candidate
        }
    }

    private suspend fun fetchAddon(
        type: String,
        videoId: String,
        target: AutomaticAddonTarget,
        policy: DownloadSourcePolicy,
    ): List<DownloadSourceCandidate> {
        val key = AddonSourceKey(target.manifestId, target.manifestUrl)
        val context = AioDetectionContext(
            manifestId = target.manifestId,
            manifestName = target.manifestName,
            manifestUrl = target.manifestUrl,
            treatAsAioStreams = key in policy.aioOverrides,
        )
        val url = buildAddonResourceUrl(target.manifestUrl, "stream", type, videoId)
        val headers = AioStreamsSupport.requestHeaders(context)
        val payload = if (headers.isEmpty()) {
            httpGetText(url)
        } else {
            httpGetTextWithHeaders(url, headers)
        }
        return StreamParser.parse(
            payload = payload,
            addonName = target.manifestName,
            addonId = target.manifestId,
            addonLogo = target.logoUrl,
            addonManifestUrl = target.manifestUrl,
        ).mapNotNull { stream ->
            val facts = SourceFactsExtractor.extract(stream, context)
            if (facts.isAioStreams) {
                DownloadsRepository.recordDiscoveredAioProvider(key, facts)
            }
            if (!policy.allowsResult(key, facts)) return@mapNotNull null
            DownloadSourceCandidate(
                stream = stream,
                addonKey = key,
                facts = facts,
                addonOrder = target.addonOrder,
            )
        }
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun Map<String, String>.headerLong(name: String): Long? =
        headerValue(name)?.trim()?.toLongOrNull()?.takeIf { it > 0L }
}
