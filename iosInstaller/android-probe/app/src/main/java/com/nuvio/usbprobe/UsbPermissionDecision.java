package com.nuvio.usbprobe;

/** Decision used by the live permission flow; missing extras are never interpreted as denial. */
final class UsbPermissionDecision {
    enum PendingOutcome { WAIT, RECOVER_GRANTED, TIMEOUT, CLOCK_RESET }
    static PendingOutcome waiting(boolean hasPermission, long elapsed, long grantedFor) {
        if (elapsed < 0) return PendingOutcome.CLOCK_RESET;
        if (hasPermission && (grantedFor >= 1600 || elapsed >= 120000)) return PendingOutcome.RECOVER_GRANTED;
        if (elapsed >= 120000) return PendingOutcome.TIMEOUT;
        return PendingOutcome.WAIT;
    }
    enum Outcome { GRANTED, GRANTED_WITH_MISSING_EXTRAS, CALLBACK_CONTRADICTION,
        DENIED, CALLBACK_INCOMPLETE, GRANT_LOST, DISCONNECTED, REENUMERATED, WRONG_DEVICE }

    static Outcome evaluate(String requestedKey, String callbackKey, boolean currentPresent,
            boolean replacementPresent, boolean hasPermission, boolean hasGrantExtra, boolean granted) {
        if (!currentPresent) return replacementPresent ? Outcome.REENUMERATED : Outcome.DISCONNECTED;
        if (callbackKey != null && !requestedKey.equals(callbackKey)) return Outcome.WRONG_DEVICE;
        if (hasPermission) {
            if (hasGrantExtra && !granted) return Outcome.CALLBACK_CONTRADICTION;
            return hasGrantExtra && callbackKey != null ? Outcome.GRANTED : Outcome.GRANTED_WITH_MISSING_EXTRAS;
        }
        if (!hasGrantExtra) return Outcome.CALLBACK_INCOMPLETE;
        return granted ? Outcome.GRANT_LOST : Outcome.DENIED;
    }
}
