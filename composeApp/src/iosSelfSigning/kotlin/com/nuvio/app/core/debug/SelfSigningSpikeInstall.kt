package com.nuvio.app.core.debug

import com.nuvio.app.selfsigning.native.nzs_probe
import com.nuvio.app.selfsigning.native.nzs_string_free
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/** Points [SelfSigningSpike.probe] at the Rust library. The call blocks, so it runs off the main thread. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun installSelfSigningSpike() {
    if (SelfSigningSpike.probe != null) return
    SelfSigningSpike.probe = {
        withContext(Dispatchers.Default) {
            val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
                .firstOrNull() as? String ?: return@withContext """{"error":"no Documents directory"}"""
            val raw = nzs_probe(documents, null) ?: return@withContext """{"error":"no result"}"""
            try {
                raw.toKString()
            } finally {
                nzs_string_free(raw)
            }
        }
    }
}
