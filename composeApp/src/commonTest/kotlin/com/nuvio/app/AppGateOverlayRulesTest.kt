package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppGateOverlayRulesTest {

    @Test
    fun coversNativeMainContentUntilItIsReady() {
        assertTrue(appLaunchOverlayVisible(false, gateIsMain = true, externalMainContentReady = false, setupWizardGating = false))
        assertFalse(appLaunchOverlayVisible(false, gateIsMain = true, externalMainContentReady = true, setupWizardGating = false))
    }

    // Debug 54 on iPhone: a profile owing the revision 10 upgrade (or this phone's device run)
    // was stuck behind the overlay, because main content is never mounted under the wizard.
    @Test
    fun neverCoversAGatingSetupWizard() {
        assertFalse(appLaunchOverlayVisible(false, gateIsMain = true, externalMainContentReady = false, setupWizardGating = true))
    }

    @Test
    fun onlyOnTheMainGateAndOnlyForNativeMainContent() {
        assertFalse(appLaunchOverlayVisible(false, gateIsMain = false, externalMainContentReady = false, setupWizardGating = false))
        assertFalse(appLaunchOverlayVisible(true, gateIsMain = true, externalMainContentReady = false, setupWizardGating = false))
    }
}
