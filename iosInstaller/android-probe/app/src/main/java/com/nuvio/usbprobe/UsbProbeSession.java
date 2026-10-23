package com.nuvio.usbprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Process-owned operation: screen recreation cannot start overlapping JNI calls or lose results.
 * All mutable state belongs to the main thread; the worker alone owns/closes the Java USB FD. */
final class UsbProbeSession {
    interface Listener { void changed(); }
    private static UsbProbeSession instance;
    static UsbProbeSession get(Context context) {
        if (instance == null) instance = new UsbProbeSession(context.getApplicationContext());
        return instance;
    }

    private final UsbManager usb;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String> journal = new ArrayList<>();
    private final List<String> results = new ArrayList<>();
    private Listener listener;
    /** Called once with the final report of the attempt that set it (the installer bridge). */
    private java.util.function.Consumer<String> done;
    private long attempt;
    private UsbDevice selected;
    private String cancelled;
    private String status = "";
    private int completed;

    private UsbProbeSession(Context context) {
        this.context = context;
        usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        BroadcastReceiver detach = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                UsbDevice device = UsbPermissionReceiver.deviceExtra(intent);
                if (attempt != 0 && selected != null && device != null
                        && UsbPermissionReceiver.key(selected).equals(UsbPermissionReceiver.key(device))) {
                    cancel("usb_disconnected_during_probe");
                }
            }
        };
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(detach, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(detach, filter);
    }

    void observe(Listener next) { listener = next; }
    void unobserve(Listener previous) { if (listener == previous) listener = null; }
    boolean busy() { return attempt != 0; }
    String status() { return status; }
    int completed() { return completed; }
    String report() {
        return "Completed attempts in this process: " + completed + " (last 12 reports retained)\n"
                + android.text.TextUtils.join("\n", journal) + "\n\n"
                + android.text.TextUtils.join("\n\n", results);
    }

    void note(String message) {
        Log.i("NuvioUsbProbe", message);
        journal.add(SystemClock.elapsedRealtime() + "ms " + message);
        if (journal.size() > 160) journal.remove(0);
        if (listener != null) listener.changed();
    }

    private void setStatus(String message) {
        if (status.equals(message)) return;
        status = message;
        if (listener != null) listener.changed();
    }

    boolean start(UsbDevice device, int mode) {
        return start(device, mode, null);
    }
    boolean start(UsbDevice device, int mode, InstallRequest install) {
        return start(device, mode, install, null);
    }
    boolean start(UsbDevice device, int mode, InstallRequest install, java.util.function.Consumer<String> whenDone) {
        if (busy()) return false;
        done = whenDone;
        final long id = NativeUsbProbe.nextAttempt();
        attempt = id;
        selected = device;
        cancelled = null;
        note("attempt=" + id + " mode=" + mode + " " + (device == null
                ? "no USB access" : "android_open_begin: " + UsbPermissionReceiver.snapshot(device)));
        setStatus("Starting probe " + id + (mode == 1 ? " (10-second unplug recovery hold)" : ""));
        main.postDelayed(poll, 100);
        worker.execute(() -> {
            String result;
            String failedAt = "native_selftest";
            try {
                if (device == null) result = NativeUsbProbe.run(-1, id, mode);
                else {
                    failedAt = "android_device_lookup";
                    UsbDevice current = UsbPermissionController.find(usb.getDeviceList(), UsbPermissionReceiver.key(device));
                    if (current == null) throw new IllegalStateException("Selected USB instance is gone");
                    if (current.getVendorId() != device.getVendorId() || current.getProductId() != device.getProductId())
                        throw new IllegalStateException("USB identity changed before open");
                    failedAt = "android_permission_check";
                    if (!usb.hasPermission(current)) throw new SecurityException("hasPermission=false before open");
                    failedAt = "android_open";
                    UsbDeviceConnection connection = usb.openDevice(current);
                    if (connection == null) throw new IllegalStateException("openDevice returned null");
                    try {
                        main.post(() -> note("attempt=" + id + " android_open_success"));
                        failedAt = "native_probe";
                        PairingStore pairStore = mode >= 3 ? new PairingStore(context) : null;
                        result = mode == 6 ? NativeUsbProbe.install(connection.getFileDescriptor(), id, pairStore, install)
                                : NativeUsbProbe.run(connection.getFileDescriptor(), id, mode, pairStore);
                    } finally {
                        // Never close this FD underneath JNI. Rust closes only its own duplicate.
                        connection.close();
                    }
                }
            } catch (Exception | LinkageError error) {
                JSONObject failure = new JSONObject();
                try {
                    failure.put("ok", false).put("blocker", failedAt + ": " + error.getClass().getSimpleName()
                            + (mode >= 3 ? ": sensitive details withheld" : ": " + error.getMessage()));
                } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
                result = failure.toString();
            } finally {
                if (install != null) install.erase();
            }
            final String finished = result;
            main.post(() -> finish(id, mode, finished));
        });
        return true;
    }

    void cancel(String reason) {
        if (!busy()) return;
        if (cancelled == null) {
            cancelled = reason;
            note("attempt=" + attempt + " " + reason + ": cancelling; wait for descriptor cleanup before retry");
        }
        NativeUsbProbe.cancel(attempt);
        setStatus(reason + ": releasing USB connection...");
    }

    private String deviceProblem() {
        if (selected == null) return null;
        UsbDevice current = UsbPermissionController.find(usb.getDeviceList(), UsbPermissionReceiver.key(selected));
        if (current == null) return "usb_disconnected_during_probe";
        if (current.getVendorId() != selected.getVendorId() || current.getProductId() != selected.getProductId())
            return "usb_identity_changed_during_probe";
        if (!usb.hasPermission(current)) return "permission_lost_during_probe";
        return null;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!busy()) return;
            try {
                String problem = deviceProblem();
                if (problem != null) cancel(problem);
                if (cancelled == null) setStatus(NativeUsbProbe.progress());
            } catch (RuntimeException error) { cancel("android_device_monitor_failed"); }
            main.postDelayed(this, 100);
        }
    };

    private void finish(long id, int mode, String result) {
        if (id != attempt) return;
        main.removeCallbacks(poll);
        // Recheck at completion as well: a queued detach must not be hidden by a success report.
        try {
            String problem = deviceProblem();
            if (problem != null && cancelled == null) cancelled = problem;
        } catch (RuntimeException error) { if (cancelled == null) cancelled = "android_device_monitor_failed"; }
        boolean ok = false;
        boolean waitingForReboot = false;
        try {
            JSONObject report = new JSONObject(result);
            report.put("androidAttempt", id).put("androidMode", mode);
            if (cancelled != null) report.put("ok", false).put("androidRecovery", cancelled)
                    .put("blocker", cancelled + ": attempt ended; scan/select to retry");
            report.put("javaConnectionClosed", true);
            ok = report.optBoolean("ok");
            JSONObject pairing = report.optJSONObject("pairing");
            waitingForReboot = pairing != null && pairing.optBoolean("rebootPending");
            result = report.toString(2);
        } catch (org.json.JSONException error) { result = "BLOCKER invalid_native_report: " + result; }
        results.add(result);
        if (results.size() > 12) results.remove(0);
        completed++;
        String outcome = cancelled != null ? cancelled : ok ? "PASS" : "BLOCKED (see report)";
        attempt = 0;
        selected = null;
        note("attempt=" + id + " finished: " + outcome + "; Java connection closed; retry available");
        Log.i("NuvioUsbProbe", result);
        setStatus("Attempt " + id + ": " + outcome + ". Connection released. "
                + (waitingForReboot ? "Pairing retained: complete the iPhone reboot, reconnect and tap Saved pairing."
                : mode == 6 && ok ? "Nuvio Z Debug installed. Open it on the iPhone and trust its developer profile if iOS asks."
                : mode >= 3 && ok ? "Authenticated pairing connection passed." : "Ready to retry."));
        java.util.function.Consumer<String> callback = done;
        done = null;
        if (callback != null) callback.accept(result);
    }
}
