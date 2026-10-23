package com.nuvio.usbprobe;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;

/** Explicit, non-exported PendingIntent destination; survives Activity recreation/dialog pauses. */
public final class UsbPermissionReceiver extends BroadcastReceiver {
    private static final String TAG = "NuvioUsbProbe";
    private static final String PREFS = "usb_permission_attempt";
    private static final String PENDING = "pending";
    private static final String RESULT = "result";

    static String action(Context context) { return context.getPackageName() + ".USB_PERMISSION"; }
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String key(UsbDevice device) { return device.getDeviceName() + "#" + device.getDeviceId(); }

    static JSONObject snapshot(UsbDevice device) {
        JSONObject object = new JSONObject();
        try {
            object.put("key", key(device));
            object.put("vendor", device.getVendorId());
            object.put("product", device.getProductId());
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        return object;
    }

    static JSONObject begin(Context context, UsbDevice device, String id) {
        JSONObject pending = snapshot(device);
        try {
            pending.put("requestId", id);
            pending.put("started", SystemClock.elapsedRealtime());
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        prefs(context).edit().putString(PENDING, pending.toString()).remove(RESULT).apply();
        return pending;
    }

    static PendingIntent pendingIntent(Context context, String id) {
        Intent callback = new Intent(context, UsbPermissionReceiver.class)
                .setAction(action(context)).setPackage(context.getPackageName())
                .setData(new Uri.Builder().scheme("nuvio-usb-probe").authority("permission").appendPath(id).build());
        // UsbManager fills EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED at send time.
        // A mutable *explicit* intent is necessary; do not use FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT.
        int flags = PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(context, 0, callback, flags);
    }

    static JSONObject pending(Context context) { return read(context, PENDING); }
    static JSONObject result(Context context) { return read(context, RESULT); }
    private static JSONObject read(Context context, String key) {
        String text = prefs(context).getString(key, null);
        if (text == null) return null;
        try { return new JSONObject(text); }
        catch (JSONException error) { return null; }
    }

    static void clear(Context context) { prefs(context).edit().remove(PENDING).remove(RESULT).apply(); }

    @Override public void onReceive(Context context, Intent intent) {
        if (!action(context).equals(intent.getAction())) return;
        String id = intent.getData() == null ? null : intent.getData().getLastPathSegment();
        JSONObject pending = pending(context);
        if (id == null || pending == null || !id.equals(pending.optString("requestId"))) {
            Log.i(TAG, "permission_callback_stale: no matching active request; ignored");
            return;
        }
        JSONObject result = new JSONObject();
        try {
            result.put("requestId", id);
            result.put("received", SystemClock.elapsedRealtime());
            result.put("hasGrantExtra", intent.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED));
            result.put("granted", intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false));
            UsbDevice device = deviceExtra(intent);
            if (device != null) result.put("device", snapshot(device));
            result.put("hasDeviceExtra", device != null);
        } catch (RuntimeException | JSONException error) {
            try { result.put("parseError", error.getClass().getSimpleName()); }
            catch (JSONException ignored) { }
        }
        prefs(context).edit().putString(RESULT, result.toString()).apply();
        Log.i(TAG, "permission_callback_received: " + result);
    }

    @SuppressWarnings("deprecation")
    static UsbDevice deviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }
}
