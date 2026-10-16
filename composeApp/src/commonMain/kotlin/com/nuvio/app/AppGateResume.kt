package com.nuvio.app

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.profiles.ProfileRepository

/**
 * Lets a re-created app window open straight on the app instead of re-running the gate.
 *
 * Desktop tray mode (Social V2, 2026-10-16) closes the window by removing its whole composition and
 * keeping the process - and with it the signed-in session, the active profile and the Social layer -
 * alive. Without this, opening Nuvio from the tray would start [AppGate] at Loading and walk the
 * profile picker again, for a profile that never stopped being active.
 *
 * ⚠ **Off unless a platform turns it on.** Only desktop's tray sets [enabled]; mobile keeps re-gating
 * a fresh composition exactly as before. And it only resumes what was really there: the gate must have
 * last stood on Main, the session must still be authenticated and a profile still active.
 */
object AppGateResume {
    @Volatile
    var enabled: Boolean = false

    @Volatile
    private var lastGateScreen: String? = null

    internal fun onGateScreen(name: String) {
        lastGateScreen = name
    }

    internal fun canResumeMain(): Boolean =
        enabled &&
            lastGateScreen == AppGateScreen.Main.name &&
            AuthRepository.state.value is AuthState.Authenticated &&
            ProfileRepository.state.value.activeProfile != null
}
