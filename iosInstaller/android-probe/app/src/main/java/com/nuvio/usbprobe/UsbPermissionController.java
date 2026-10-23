package com.nuvio.usbprobe;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.Map;
import java.util.UUID;

/** One USB instance per attempt. VID/PID similarity is diagnostic, never permission to open a replacement. */
final class UsbPermissionController {
    interface Listener {
        void state(String stage, String message);
        void granted(UsbDevice currentDevice);
    }
    private final Context context;
    private final UsbManager usb;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private long grantObserved = -1;
    private boolean stopped;
    private final Runnable poll = this::check;

    UsbPermissionController(Context context, UsbManager usb, Listener listener) {
        this.context = context.getApplicationContext();
        this.usb = usb;
        this.listener = listener;
    }

    boolean pending() { return UsbPermissionReceiver.pending(context) != null; }
    void resume() { if (pending()) check(); }
    void stop() { stopped = true; main.removeCallbacks(poll); }
    void devicesChanged() { if (pending()) check(); }
    void detached(UsbDevice device) {
        JSONObject pending = UsbPermissionReceiver.pending(context);
        if (pending != null && pending.optString("key").equals(UsbPermissionReceiver.key(device))) {
            finish("usb_disconnected", "Android detached the requested USB instance. A later attachment requires a fresh selection.");
        }
    }

    void request(UsbDevice selected) {
        if (pending()) { listener.state("permission_waiting", "An earlier USB permission request is still pending."); return; }
        UsbDevice current = find(usb.getDeviceList(), UsbPermissionReceiver.key(selected));
        if (current == null) {
            listener.state("device_changed", "Selected USB instance is gone. Scan and select the currently attached device.");
            return;
        }
        if (current.getVendorId() != selected.getVendorId() || current.getProductId() != selected.getProductId()) {
            listener.state("device_changed", "USB address was reused by a different VID/PID. Select the current device.");
            return;
        }
        if (usb.hasPermission(current)) {
            listener.state("permission_already_granted", "UsbManager confirms permission for " + UsbPermissionReceiver.key(current));
            listener.granted(current);
            return;
        }
        String id = UUID.randomUUID().toString();
        UsbPermissionReceiver.begin(context, current, id);
        grantObserved = -1;
        listener.state("permission_requested", "request=" + id + " selected=" + UsbPermissionReceiver.snapshot(current)
                + "; mutable explicit callback to private receiver; waiting up to 120s");
        try {
            usb.requestPermission(current, UsbPermissionReceiver.pendingIntent(context, id));
            schedule();
        } catch (RuntimeException error) {
            finish("permission_request_failed", error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private void schedule() {
        main.removeCallbacks(poll);
        if (!stopped) main.postDelayed(poll, 400);
    }

    private void check() {
        try { checkCurrent(); }
        catch (RuntimeException error) { finish("permission_state_failed", error.getClass().getSimpleName() + ": " + error.getMessage()); }
    }

    private void checkCurrent() {
        if (stopped) return;
        JSONObject pending = UsbPermissionReceiver.pending(context);
        if (pending == null) return;
        Map<String, UsbDevice> devices = usb.getDeviceList();
        String expected = pending.optString("key");
        UsbDevice current = find(devices, expected);
        if (current != null && (current.getVendorId() != pending.optInt("vendor")
                || current.getProductId() != pending.optInt("product"))) {
            finish("usb_identity_changed", "USB address was reused with a different VID/PID. Scan and select again.");
            return;
        }
        boolean replacement = false;
        for (UsbDevice candidate : devices.values()) {
            if (candidate.getVendorId() == pending.optInt("vendor") && candidate.getProductId() == pending.optInt("product")
                    && !expected.equals(UsbPermissionReceiver.key(candidate))) {
                replacement = true;
                listener.state("usb_instance_seen", "Different instance with same VID/PID: " + UsbPermissionReceiver.snapshot(candidate));
            }
        }
        if (current == null) {
            finish(replacement ? "usb_reenumerated" : "usb_disconnected",
                    "Requested instance " + expected + " is absent. "
                    + (replacement ? "Same VID/PID appeared at another address; select it and grant fresh access." : "Reconnect, scan and retry."));
            return;
        }
        boolean hasPermission = usb.hasPermission(current);
        JSONObject result = UsbPermissionReceiver.result(context);
        if (result != null) {
            JSONObject callbackDevice = result.optJSONObject("device");
            String callbackKey = callbackDevice == null ? null : callbackDevice.optString("key");
            listener.state("permission_callback", "request=" + pending.optString("requestId")
                    + " result=" + result + "; currentPresent=true managerHasPermission=" + hasPermission);
            UsbPermissionDecision.Outcome decision = UsbPermissionDecision.evaluate(expected, callbackKey, true,
                    replacement, hasPermission, result.optBoolean("hasGrantExtra"), result.optBoolean("granted"));
            switch (decision) {
                case GRANTED:
                case GRANTED_WITH_MISSING_EXTRAS:
                case CALLBACK_CONTRADICTION:
                    finish("permission_" + decision.name().toLowerCase(java.util.Locale.ROOT),
                            "UsbManager authorizes the same current USB instance; continuing to openDevice.");
                    listener.granted(current);
                    return;
                case DENIED:
                    finish("permission_denied", "Android explicitly returned granted=false and hasPermission=false; device is still attached.");
                    return;
                case GRANT_LOST:
                    finish("permission_grant_lost", "Callback says granted=true, but UsbManager hasPermission=false for the current instance. Retry access.");
                    return;
                case WRONG_DEVICE:
                    finish("permission_wrong_device", "Callback belongs to " + callbackKey + ", requested " + expected + ". No device opened.");
                    return;
                default:
                    finish("permission_callback_incomplete", "Callback lacks a grant result and UsbManager hasPermission=false. This is not an explicit denial.");
                    return;
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (hasPermission) {
            if (grantObserved < 0) {
                grantObserved = now;
                listener.state("permission_manager_granted", "Permission is present; briefly waiting for the callback.");
            }
        } else grantObserved = -1;
        long elapsed = now - pending.optLong("started");
        switch (UsbPermissionDecision.waiting(hasPermission, elapsed, grantObserved < 0 ? -1 : now - grantObserved)) {
            case RECOVER_GRANTED:
                finish("permission_callback_missing_recovered", "No callback received, but UsbManager authorizes the same current device. Continuing safely.");
                listener.granted(current);
                return;
            case TIMEOUT:
                finish("permission_callback_timeout", "No callback received and hasPermission=false; no denial is inferred. Device is still attached. Retry.");
                return;
            case CLOCK_RESET:
                finish("permission_request_expired", "Device clock restarted since the request; select the attached device again.");
                return;
            default: break;
        }
        schedule();
    }

    private void finish(String stage, String message) {
        main.removeCallbacks(poll);
        UsbPermissionReceiver.clear(context);
        listener.state(stage, message);
    }

    static UsbDevice find(Map<String, UsbDevice> devices, String key) {
        for (UsbDevice device : devices.values()) if (UsbPermissionReceiver.key(device).equals(key)) return device;
        return null;
    }
}
