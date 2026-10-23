package com.nuvio.usbprobe;

/** One-directional JNI: Java owns permission and the borrowed descriptor's lifetime. */
public final class NativeUsbProbe {
    static { System.loadLibrary("nuvioz_usb_probe"); }
    private NativeUsbProbe() {}
    public static native String version();
    public static native String progress();
    public static native String probe(int borrowedFileDescriptor);
}
