package com.nuvio.app.features.addons

internal suspend fun fetchAddonResponseText(
    url: String,
    forceRefresh: Boolean = false,
    headers: Map<String, String> = emptyMap(),
): String {
    val requestHeaders = if (forceRefresh) {
        headers + ("Cache-Control" to "no-cache")
    } else {
        headers
    }
    return if (requestHeaders.isEmpty()) {
        httpGetText(url)
    } else {
        httpGetTextWithHeaders(url, requestHeaders)
    }
}
