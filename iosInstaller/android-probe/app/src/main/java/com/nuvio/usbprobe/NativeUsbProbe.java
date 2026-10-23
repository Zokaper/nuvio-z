package com.nuvio.usbprobe;

/** One-directional JNI: Java owns permission and the borrowed descriptor's lifetime. */
public final class NativeUsbProbe {
    private static final java.util.concurrent.atomic.AtomicLong ATTEMPTS = new java.util.concurrent.atomic.AtomicLong();
    static { System.loadLibrary("nuvioz_usb_probe"); }
    private NativeUsbProbe() {}
    public static native String version();
    public static native String progress();
    static long nextAttempt() { return ATTEMPTS.incrementAndGet(); }
    static native String run(int borrowedFileDescriptor, long attempt, int mode);
    static native void cancel(long attempt);
    public static String probe(int borrowedFileDescriptor) {
        return run(borrowedFileDescriptor, nextAttempt(), 0);
    }
}
