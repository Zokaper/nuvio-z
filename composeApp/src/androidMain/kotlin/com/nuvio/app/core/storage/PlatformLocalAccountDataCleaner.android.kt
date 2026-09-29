package com.nuvio.app.core.storage

import android.content.Context

/**
 * Signing out on Android: every store [LocalStoreRegistry] classifies as account, credential or
 * cache is cleared, and its `preservedKeys` survive; device-local stores are left alone. The list
 * of names is the registry's, never a second copy here - that copy is how fourteen stores (the TMDB
 * key and the AIOStreams credentials among them) came to outlive the account they belonged to.
 */
internal actual object PlatformLocalAccountDataCleaner {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun wipe() {
        val context = appContext ?: return
        LocalStoreRegistry.wiped.forEach { store ->
            val preferences = context.getSharedPreferences(store.name, Context.MODE_PRIVATE)
            val editor = preferences.edit()
            if (store.preservedKeys.isEmpty()) {
                editor.clear()
            } else {
                preferences.all.keys
                    .filter { it !in store.preservedKeys }
                    .forEach(editor::remove)
            }
            editor.apply()
        }
        context.filesDir.resolve("nuvio_plugin_scrapers").deleteRecursively()
    }
}
