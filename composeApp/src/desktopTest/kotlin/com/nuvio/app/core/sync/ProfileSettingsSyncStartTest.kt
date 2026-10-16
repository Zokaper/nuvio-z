package com.nuvio.app.core.sync

import com.nuvio.app.features.settings.ThemeSettingsRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `AppGate` and `warmProfileBoundRepositories` both start the settings observer from
 * `Dispatchers.Default` (Performance Phase 1 moved the first of them off the UI thread), so they can
 * race. Two observers would push every settings change twice.
 */
class ProfileSettingsSyncStartTest {
    @AfterTest
    fun stop() = ProfileSettingsSync.clearAccountState()

    @Test
    fun concurrentStartsLeaveExactlyOneObserver() {
        ProfileSettingsSync.clearAccountState()
        awaitSubscribers(0)

        val callers = 8
        val ready = CountDownLatch(callers)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(callers)
        try {
            repeat(callers) {
                pool.execute {
                    ready.countDown()
                    go.await()
                    ProfileSettingsSync.startObserving()
                }
            }
            ready.await()
            go.countDown()
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }

        // One observer collects the theme preference once; give its collector time to subscribe, then
        // make sure no second one turns up.
        awaitSubscribers(1)
        Thread.sleep(500)
        assertEquals(1, themePreferenceSubscribers())
    }

    private fun awaitSubscribers(expected: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (themePreferenceSubscribers() != expected && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(expected, themePreferenceSubscribers())
    }

    /** `ThemeSettingsRepository.selectedThemePreference` is collected by the settings observer and nothing else here. */
    private fun themePreferenceSubscribers(): Int {
        ThemeSettingsRepository.ensureLoaded()
        val field = ThemeSettingsRepository::class.java.getDeclaredField("_selectedThemePreference").apply { isAccessible = true }
        return (field.get(null) as MutableStateFlow<*>).subscriptionCount.value
    }
}
