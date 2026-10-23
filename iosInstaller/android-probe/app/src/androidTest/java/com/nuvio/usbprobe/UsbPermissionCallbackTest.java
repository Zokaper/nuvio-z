package com.nuvio.usbprobe;

import static org.junit.Assert.*;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;

/** Exercise Android's actual PendingIntent fill-in behavior. No USB permission or USB access is requested. */
@RunWith(AndroidJUnit4.class)
public final class UsbPermissionCallbackTest {
    private Context context;
    private String id;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        id = UUID.randomUUID().toString();
        JSONObject request = new JSONObject().put("requestId", id).put("key", "test-no-usb")
                .put("vendor", 1452).put("product", 4776).put("started", SystemClock.elapsedRealtime());
        context.getSharedPreferences("usb_permission_attempt", Context.MODE_PRIVATE).edit()
                .putString("pending", request.toString()).remove("result").commit();
    }
    @After public void cleanup() { UsbPermissionReceiver.clear(context); }

    private JSONObject awaitResult() {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        JSONObject result;
        do {
            result = UsbPermissionReceiver.result(context);
            if (result != null) return result;
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Explicit permission receiver callback was not delivered");
        return null;
    }

    @Test public void mutableCallbackPreservesTheGrantedExtraWithoutAnActivity() throws Exception {
        PendingIntent callback = UsbPermissionReceiver.pendingIntent(context, id);
        if (Build.VERSION.SDK_INT >= 31) assertFalse(callback.isImmutable());
        callback.send(context, 0, new Intent().putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true));
        JSONObject result = awaitResult();
        assertTrue(result.toString(), result.getBoolean("hasGrantExtra"));
        assertTrue(result.getBoolean("granted"));
        assertFalse(result.getBoolean("hasDeviceExtra"));
        assertEquals(id, result.getString("requestId"));
    }

    @Test public void explicitDenialIsPreserved() throws Exception {
        UsbPermissionReceiver.pendingIntent(context, id).send(context, 0,
                new Intent().putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false));
        JSONObject result = awaitResult();
        assertTrue(result.getBoolean("hasGrantExtra"));
        assertFalse(result.getBoolean("granted"));
    }

    @Test public void immutableCallbackReproducesTheOriginalLostExtrasBug() throws Exception {
        Intent intent = new Intent(context, UsbPermissionReceiver.class).setAction(UsbPermissionReceiver.action(context))
                .setData(android.net.Uri.parse("nuvio-usb-probe://permission/" + id));
        PendingIntent immutable = PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        immutable.send(context, 0, new Intent().putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true));
        JSONObject result = awaitResult();
        assertFalse("Original immutable intent discards UsbManager's fill-in grant", result.getBoolean("hasGrantExtra"));
    }

    @Test public void receiverIsPrivate() throws Exception {
        assertFalse(context.getPackageManager().getReceiverInfo(
                new ComponentName(context, UsbPermissionReceiver.class), PackageManager.GET_META_DATA).exported);
    }

    @Test public void oldRequestCannotCompleteANewerAttempt() throws Exception {
        PendingIntent old = UsbPermissionReceiver.pendingIntent(context, "old-" + id);
        java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(1);
        old.send(context, 0, new Intent().putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true),
                (pendingIntent, intent, resultCode, resultData, resultExtras) -> delivered.countDown(), null);
        assertTrue(delivered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertNull(UsbPermissionReceiver.result(context));
        assertEquals(id, UsbPermissionReceiver.pending(context).getString("requestId"));
    }
}
