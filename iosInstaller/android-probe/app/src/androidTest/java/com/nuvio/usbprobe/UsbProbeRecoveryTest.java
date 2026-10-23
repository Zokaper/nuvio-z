package com.nuvio.usbprobe;

import static org.junit.Assert.*;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Synthetic cancellation/lifecycle tests; no iPhone, USB grant or USB transport exercised. */
@RunWith(AndroidJUnit4.class)
public final class UsbProbeRecoveryTest {
    @Test public void cancelBeforeJniStartsCannotBeLostAndNextAttemptCanRun() throws Exception {
        long id = NativeUsbProbe.nextAttempt();
        NativeUsbProbe.cancel(id);
        JSONObject cancelled = new JSONObject(NativeUsbProbe.run(-1, id, 2));
        assertTrue(cancelled.toString(), cancelled.getString("blocker").startsWith("probe_cancelled:"));
        JSONObject next = new JSONObject(NativeUsbProbe.probe(-1));
        assertTrue(next.toString(), next.getString("blocker").startsWith("file_descriptor:"));
    }

    @Test public void cancellationStopsAnActiveNativeWaitAndReleasesBusyState() throws Exception {
        NativeUsbProbe.probe(-1); // Clear progress from any preceding synthetic wait.
        long id = NativeUsbProbe.nextAttempt();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<String> pending = worker.submit(() -> NativeUsbProbe.run(-1, id, 2));
        try {
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (!NativeUsbProbe.progress().startsWith("synthetic_wait:") && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(20);
            assertTrue(NativeUsbProbe.progress(), NativeUsbProbe.progress().startsWith("synthetic_wait:"));
            NativeUsbProbe.cancel(id);
            JSONObject cancelled = new JSONObject(pending.get(5, TimeUnit.SECONDS));
            assertFalse(cancelled.getBoolean("ok"));
            assertFalse(cancelled.getBoolean("muxConnected"));
            assertFalse(cancelled.getBoolean("lockdownConnected"));
            assertTrue(cancelled.toString(), cancelled.getJSONArray("events").toString().contains("synthetic_wait"));
            assertTrue(cancelled.getString("blocker").startsWith("probe_cancelled:"));
        } finally {
            NativeUsbProbe.cancel(id);
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue(new JSONObject(NativeUsbProbe.probe(-1)).getString("blocker").startsWith("file_descriptor:"));
    }

    @Test public void screenRecreationKeepsOneActiveAttemptAndItsResult() {
        UsbProbeSession[] session = new UsbProbeSession[1];
        int[] before = new int[1];
        try (ActivityScenario<UsbProbeActivity> scenario = ActivityScenario.launch(UsbProbeActivity.class)) {
            scenario.onActivity(activity -> {
                session[0] = UsbProbeSession.get(activity);
                before[0] = session[0].completed();
                assertTrue(session[0].start(null, 2));
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertSame(session[0], UsbProbeSession.get(activity));
                assertTrue(session[0].busy());
                assertFalse(session[0].start(null, 0));
                session[0].cancel("synthetic_recreation_test");
            });
            long deadline = SystemClock.elapsedRealtime() + 7000;
            boolean[] done = {false};
            while (!done[0] && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity(activity -> done[0] = !session[0].busy());
                SystemClock.sleep(20);
            }
            assertTrue("Session did not finish after cancellation", done[0]);
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(before[0] + 1, session[0].completed());
                assertTrue(activity.report(), activity.report().contains("synthetic_recreation_test"));
                assertTrue(activity.report().contains("\"javaConnectionClosed\": true"));
                assertTrue(session[0].start(null, 0));
            });
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (session[0] != null) session[0].cancel("test_cleanup");
            });
            // Let final worker completion settle before other JNI tests run.
            long deadline = SystemClock.elapsedRealtime() + 7000;
            boolean[] busy = {true};
            while (busy[0] && SystemClock.elapsedRealtime() < deadline) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> busy[0] = session[0] != null && session[0].busy());
                SystemClock.sleep(20);
            }
            assertFalse(busy[0]);
        }
    }
}
