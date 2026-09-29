package com.nuvio.app.core.storage

import android.app.Application
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalStoreRegistryCoverageTest {
    /**
     * Every SharedPreferences name the Android sources open must be classified, or sign-out silently
     * leaves that store for the next account - which is exactly how the TMDB settings and the
     * AIOStreams credentials outlived theirs.
     */
    @Test
    fun everySharedPreferencesNameInTheAndroidSourcesIsClassified() {
        val names = androidSourceRoots()
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { file -> preferenceNamePattern.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .toSet()
        assertTrue(names.size > 30, "the source scan found only $names - the pattern has stopped matching")
        val unclassified = names.filter { LocalStoreRegistry.find(it) == null }
        assertTrue(unclassified.isEmpty(), "classify these in LocalStoreRegistry: $unclassified")
    }

    @Test
    fun wipeClearsAccountStoresKeepsDeviceStoresAndPreservedKeys() {
        val context = RuntimeEnvironment.getApplication()
        PlatformLocalAccountDataCleaner.initialize(context)
        fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs("nuvio_tmdb_settings").edit().putString("tmdb_api_key_1", "secret").commit()
        prefs("nuvio_aiostreams_credentials").edit().putString("uuid_1", "x").commit()
        prefs("nuvio_theme_settings").edit()
            .putString("selected_app_language", "de")
            .putString("selected_theme_1", "OCEAN")
            .commit()
        prefs("nuvio_device_setup").edit()
            .putInt("device_setup_revision", 10)
            .putBoolean("setup_arrival_pending_1", true)
            .commit()
        prefs("nuvio_whats_new").edit().putInt("ack_serial", 7).commit()

        PlatformLocalAccountDataCleaner.wipe()

        assertNull(prefs("nuvio_tmdb_settings").getString("tmdb_api_key_1", null))
        assertNull(prefs("nuvio_aiostreams_credentials").getString("uuid_1", null))
        assertNull(prefs("nuvio_theme_settings").getString("selected_theme_1", null))
        assertEquals("de", prefs("nuvio_theme_settings").getString("selected_app_language", null))
        assertEquals(10, prefs("nuvio_device_setup").getInt("device_setup_revision", 0))
        assertTrue(!prefs("nuvio_device_setup").contains("setup_arrival_pending_1"))
        assertEquals(7, prefs("nuvio_whats_new").getInt("ack_serial", 0))
    }

    private fun androidSourceRoots(): List<File> {
        val base = listOf(File("src"), File("composeApp/src")).first { it.isDirectory }
        return listOf("androidMain", "androidFull", "androidPlaystore")
            .map { File(base, it) }
            .filter { it.isDirectory }
    }

    private companion object {
        // `preferencesName = "..."`, `PREFS_NAME = "..."`, `getSharedPreferences("...")` and friends.
        val preferenceNamePattern = Regex(
            """(?:(?:preferencesName|prefsName|PREFS_NAME|PREFERENCES_NAME|legacyPrefName|platformPreferencesName|legacyDebridPreferencesName)\s*=\s*|getSharedPreferences\(\s*)"([a-z_0-9]+)"""",
        )
    }
}
