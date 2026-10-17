package com.nuvio.app.core.debug

import com.nuvio.app.selfsigning.native.nzs_answer_two_factor
import com.nuvio.app.selfsigning.native.nzs_installed_profiles
import com.nuvio.app.selfsigning.native.nzs_pending_two_factor
import com.nuvio.app.selfsigning.native.nzs_probe
import com.nuvio.app.selfsigning.native.nzs_refresh
import com.nuvio.app.selfsigning.native.nzs_status
import com.nuvio.app.selfsigning.native.nzs_string_free
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Points [SelfSigningSpike] at the Rust library. Its calls block (network, the phone, Apple), so they run off the
 * main thread; the 2FA poll and answer only touch a lock and are safe anywhere.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun installSelfSigningSpike() {
    if (SelfSigningSpike.probe != null) return
    val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
    val bundle = NSBundle.mainBundle.bundlePath

    fun take(raw: CPointer<ByteVar>?): String {
        raw ?: return """{"error":"no result"}"""
        return try {
            raw.toKString()
        } finally {
            nzs_string_free(raw)
        }
    }

    SelfSigningSpike.probe = { withContext(Dispatchers.Default) { take(nzs_probe(documents, null)) } }
    SelfSigningSpike.status = { withContext(Dispatchers.Default) { take(nzs_status(bundle)) } }
    SelfSigningSpike.installedProfiles = { withContext(Dispatchers.Default) { take(nzs_installed_profiles(bundle, documents, null)) } }
    SelfSigningSpike.refresh = { email, password ->
        withContext(Dispatchers.Default) { take(nzs_refresh(bundle, documents, email, password, null)) }
    }
    SelfSigningSpike.waitingForCode = {
        val prompt = nzs_pending_two_factor()
        if (prompt != null) nzs_string_free(prompt)
        prompt != null
    }
    SelfSigningSpike.answerCode = { code -> nzs_answer_two_factor(code) }
}
