package com.nuvio.usbprobe;

import static org.junit.Assert.assertEquals;
import static com.nuvio.usbprobe.UsbPermissionDecision.Outcome.*;
import org.junit.Test;

public final class UsbPermissionDecisionTest {
    @Test public void grantedSameInstanceCanOpen() {
        assertEquals(GRANTED, UsbPermissionDecision.evaluate("a", "a", true, false, true, true, true));
    }
    @Test public void missingExtrasWithAuthoritativePermissionCanRecover() {
        assertEquals(GRANTED_WITH_MISSING_EXTRAS, UsbPermissionDecision.evaluate("a", null, true, false, true, false, false));
    }
    @Test public void missingGrantWithNoPermissionIsNotDenial() {
        assertEquals(CALLBACK_INCOMPLETE, UsbPermissionDecision.evaluate("a", null, true, false, false, false, false));
    }
    @Test public void explicitFalseWithNoPermissionIsDenial() {
        assertEquals(DENIED, UsbPermissionDecision.evaluate("a", "a", true, false, false, true, false));
    }
    @Test public void grantFollowedByPermissionLossIsSeparate() {
        assertEquals(GRANT_LOST, UsbPermissionDecision.evaluate("a", "a", true, false, false, true, true));
    }
    @Test public void permissionPresentDespiteFalseFlagIsDiagnosticContradiction() {
        assertEquals(CALLBACK_CONTRADICTION, UsbPermissionDecision.evaluate("a", "a", true, false, true, true, false));
    }
    @Test public void disconnectedGrantedDeviceCannotOpen() {
        assertEquals(DISCONNECTED, UsbPermissionDecision.evaluate("a", "a", false, false, true, true, true));
    }
    @Test public void replacementWithSameProductDoesNotInheritPermission() {
        assertEquals(REENUMERATED, UsbPermissionDecision.evaluate("a", "a", false, true, true, true, true));
    }
    @Test public void wrongCallbackDeviceCannotOpenEvenWithPermission() {
        assertEquals(WRONG_DEVICE, UsbPermissionDecision.evaluate("a", "b", true, false, true, true, true));
    }
    @Test public void absentCallbackWaitsInsteadOfInferringDenial() {
        assertEquals(UsbPermissionDecision.PendingOutcome.WAIT, UsbPermissionDecision.waiting(false, 1000, -1));
    }
    @Test public void absentCallbackTimesOutWithoutPermission() {
        assertEquals(UsbPermissionDecision.PendingOutcome.TIMEOUT, UsbPermissionDecision.waiting(false, 120000, -1));
    }
    @Test public void authoritativeGrantRecoversAfterCallbackGrace() {
        assertEquals(UsbPermissionDecision.PendingOutcome.RECOVER_GRANTED, UsbPermissionDecision.waiting(true, 5000, 1600));
    }
    @Test public void freshGrantBrieflyWaitsForCallback() {
        assertEquals(UsbPermissionDecision.PendingOutcome.WAIT, UsbPermissionDecision.waiting(true, 1000, 400));
    }
    @Test public void grantAtDeadlineIsNotMisreportedAsDeniedOrTimedOut() {
        assertEquals(UsbPermissionDecision.PendingOutcome.RECOVER_GRANTED, UsbPermissionDecision.waiting(true, 120000, 0));
    }
    @Test public void rebootExpiresAnOldPendingRequest() {
        assertEquals(UsbPermissionDecision.PendingOutcome.CLOCK_RESET, UsbPermissionDecision.waiting(true, -5000, 5000));
    }
}
