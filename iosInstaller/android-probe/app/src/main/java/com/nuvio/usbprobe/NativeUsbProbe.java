package com.nuvio.usbprobe;

/** One-directional JNI: Java owns permission and the borrowed descriptor's lifetime. */
public final class NativeUsbProbe {
    private static final java.util.concurrent.atomic.AtomicLong ATTEMPTS = new java.util.concurrent.atomic.AtomicLong();
    static { System.loadLibrary("nuvioz_usb_probe"); }
    private NativeUsbProbe() {}
    public static native String version();
    public static native String progress();
    static long nextAttempt() { return ATTEMPTS.incrementAndGet(); }
    private static native String execute(int borrowedFileDescriptor, long attempt, int mode, PairingStore store);
    static String run(int borrowedFileDescriptor, long attempt, int mode) {
        return execute(borrowedFileDescriptor, attempt, mode, null);
    }
    static String run(int borrowedFileDescriptor, long attempt, int mode, PairingStore store) {
        return execute(borrowedFileDescriptor, attempt, mode, store);
    }
    static native void cancel(long attempt);
    // Instrumentation only: synthetic namespace/bytes; no USB, phone identity or protocol operation.
    static native String testStoreBridge(PairingStore store);
    public static String probe(int borrowedFileDescriptor) {
        return run(borrowedFileDescriptor, nextAttempt(), 0);
    }
}
