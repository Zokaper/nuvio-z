package com.nuvio.usbprobe;

import static org.junit.Assert.*;
import android.content.pm.PackageManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class UsbProbeSmokeTest {
    @Test public void loadsRustThroughJni() {
        assertTrue(NativeUsbProbe.version().contains("rusbmux/0.3.0+nuvio"));
        assertTrue(NativeUsbProbe.version().contains("idevice/0.1.68"));
    }

    @Test public void invalidDescriptorReturnsStructuredBlockerWithoutUsbAccess() throws Exception {
        JSONObject report = new JSONObject(NativeUsbProbe.probe(-1));
        assertFalse(report.getBoolean("ok"));
        assertFalse(report.getBoolean("muxConnected"));
        assertFalse(report.getBoolean("lockdownConnected"));
        assertTrue(report.toString(), report.getString("blocker").startsWith("file_descriptor:"));
    }

    @Test public void experimentIsDebuggableAndHasNoInternetPermission() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.nuvio.app.z.usbprobe", context.getPackageName());
        assertTrue((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        assertEquals(PackageManager.PERMISSION_DENIED,
                context.checkCallingOrSelfPermission("android.permission.INTERNET"));
    }

    @Test public void activityStartsAndReportsNativeLoadWithoutAnAttachedIphone() {
        try (ActivityScenario<UsbProbeActivity> scenario = ActivityScenario.launch(UsbProbeActivity.class)) {
            scenario.onActivity(activity -> assertTrue(activity.report().contains("Native loaded: nuvio-usb-probe/0.3.0")));
        }
    }

    @Test public void nativeRejectsANonUsbDescriptorAndPreservesTheJavaOwnedDescriptor() throws Exception {
        try (android.os.ParcelFileDescriptor descriptor = android.os.ParcelFileDescriptor.open(
                new java.io.File("/dev/null"), android.os.ParcelFileDescriptor.MODE_READ_ONLY)) {
            JSONObject report = new JSONObject(NativeUsbProbe.probe(descriptor.getFd()));
            assertFalse(report.getBoolean("ok"));
            assertTrue(report.toString(), report.getString("blocker").startsWith("file_descriptor:"));
            assertNotNull(android.system.Os.fstat(descriptor.getFileDescriptor()));
        }
    }

    @Test public void repeatedNativeFailuresReleaseDescriptorsAndBusyState() throws Exception {
        NativeUsbProbe.probe(-1); // Initialize runtime/logger before measuring.
        int before = new java.io.File("/proc/self/fd").list().length;
        for (int i = 0; i < 20; i++) {
            nativeRejectsANonUsbDescriptorAndPreservesTheJavaOwnedDescriptor();
        }
        int after = new java.io.File("/proc/self/fd").list().length;
        assertTrue("FDs before=" + before + " after=" + after, after <= before + 3);
    }
}
