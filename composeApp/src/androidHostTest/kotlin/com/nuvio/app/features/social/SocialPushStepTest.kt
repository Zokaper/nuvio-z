package com.nuvio.app.features.social

import kotlin.test.Test
import kotlin.test.assertEquals

class SocialPushStepTest {
    private fun input(profile: String?, ready: Boolean = true, alertsOn: Boolean = true) =
        SocialPushInput(activeProfileId = profile, ready = ready, alertsOn = alertsOn)

    @Test
    fun startUpBeforeAnyProfileKeepsTheRegistration() {
        assertEquals(SocialPushStep.Wait, socialPushStep(input(null), registered = "a", seenActive = false))
    }

    @Test
    fun activationThatHasNotLoadedYetKeepsTheRegistration() {
        // The 2026-10-19 bug: every activation starts without capabilities, and that used to forget "a".
        assertEquals(SocialPushStep.Wait, socialPushStep(input("a", ready = false), registered = "a", seenActive = true))
    }

    @Test
    fun loadedProfileRegisters() {
        assertEquals(SocialPushStep.Register, socialPushStep(input("a"), registered = "a", seenActive = true))
        assertEquals(SocialPushStep.Register, socialPushStep(input("a"), registered = null, seenActive = true))
        assertEquals(SocialPushStep.Register, socialPushStep(input("b"), registered = "a", seenActive = true))
    }

    @Test
    fun signingOutAfterAProfileWasActiveForgets() {
        assertEquals(SocialPushStep.Forget, socialPushStep(input(null), registered = "a", seenActive = true))
        assertEquals(SocialPushStep.Wait, socialPushStep(input(null), registered = null, seenActive = true))
    }

    @Test
    fun switchingToAnotherProfileForgetsTheOldOneBeforeItLoads() {
        assertEquals(SocialPushStep.Forget, socialPushStep(input("b", ready = false), registered = "a", seenActive = true))
    }

    @Test
    fun notificationsOffUnregistersOnlyTheProfileRegisteredHere() {
        assertEquals(SocialPushStep.Unregister, socialPushStep(input("a", alertsOn = false), registered = "a", seenActive = true))
        assertEquals(SocialPushStep.Forget, socialPushStep(input("b", alertsOn = false), registered = "a", seenActive = true))
        assertEquals(SocialPushStep.Wait, socialPushStep(input("a", alertsOn = false), registered = null, seenActive = true))
    }
}
