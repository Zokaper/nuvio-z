package com.nuvio.usbprobe;

/** One-directional JNI: Java owns permission and the borrowed descriptor's lifetime. */
public final class NativeUsbProbe {
    private static final java.util.concurrent.atomic.AtomicLong ATTEMPTS = new java.util.concurrent.atomic.AtomicLong();
    static { System.loadLibrary("nuvioz_usb_probe"); }
    private NativeUsbProbe() {}
    public static native String version();
    public static native void initialize(android.content.Context applicationContext);
    static native String networkCheck();
    public static native String progress();
    static long nextAttempt() { return ATTEMPTS.incrementAndGet(); }
    private static native String execute(int borrowedFileDescriptor, long attempt, int mode, PairingStore store, InstallRequest install);
    static String run(int borrowedFileDescriptor, long attempt, int mode) {
        return execute(borrowedFileDescriptor, attempt, mode, null, null);
    }
    static String run(int borrowedFileDescriptor, long attempt, int mode, PairingStore store) {
        return execute(borrowedFileDescriptor, attempt, mode, store, null);
    }
    static String install(int fd, long attempt, PairingStore store, InstallRequest request) {
        return execute(fd, attempt, 6, store, request);
    }
    static native boolean answerChallenge(String answer);
    static native String certificateChoices();
    static native void cancel(long attempt);
    // Instrumentation only: synthetic namespace/bytes; no USB, phone identity or protocol operation.
    static native String testStoreBridge(PairingStore store);
    public static String probe(int borrowedFileDescriptor) {
        return run(borrowedFileDescriptor, nextAttempt(), 0);
    }
}
