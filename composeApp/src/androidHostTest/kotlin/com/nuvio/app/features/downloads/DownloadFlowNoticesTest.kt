package com.nuvio.app.features.downloads

import com.nuvio.app.core.ui.NuvioToastAction
import com.nuvio.app.core.ui.NuvioToastController
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadFlowNoticesTest {
    private suspend fun awaitNextToast(previousId: Long?) = run {
        repeat(100) {
            val toast = NuvioToastController.currentToast.value
            if (toast != null && toast.id != previousId) return@run toast
            delay(20)
        }
        error("Download toast did not appear")
    }

    @Test fun bothSingleDownloadStartVariantsOpenDownloads() = runBlocking {
        val before = NuvioToastController.currentToast.value?.id
        ToastDownloadFlowNotices.started(null, null, null, null)
        val direct = awaitNextToast(before)
        assertEquals(NuvioToastAction.OpenDownloads, direct.action)
        assertNotNull(direct.actionLabel)

        ToastDownloadFlowNotices.started(null, 1080, 1_000_000L) {}
        val withChoice = awaitNextToast(direct.id)
        assertEquals(NuvioToastAction.OpenDownloads, withChoice.action)
        assertNotNull(withChoice.actionLabel)
        NuvioToastController.dismiss(withChoice.id)
    }
}
