package com.nuvio.app.features.downloads

import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.network.NetworkStatusUiState
import kotlinx.coroutines.flow.StateFlow

/** Injectable public-connectivity feed used by the download queue and its harness. */
internal interface DownloadConnectivityFeed {
    val states: StateFlow<NetworkStatusUiState>
    fun ensureStarted()
    fun requestRefresh()
}

internal object AppDownloadConnectivityFeed : DownloadConnectivityFeed {
    override val states: StateFlow<NetworkStatusUiState>
        get() = NetworkStatusRepository.uiState

    override fun ensureStarted() = NetworkStatusRepository.ensureStarted()

    override fun requestRefresh() = NetworkStatusRepository.requestRefresh(force = true)
}

internal fun NetworkStatusUiState.blocksMediaDownloads(): Boolean =
    condition == NetworkCondition.NoInternet
