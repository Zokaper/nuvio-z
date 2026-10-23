package com.nuvio.usbprobe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Isolated debug application: no pairing, credentials or installation controls. */
public final class UsbProbeActivity extends Activity {
    private static final String TAG = "NuvioUsbProbe";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String> journal = new ArrayList<>();
    private UsbManager usb;
    private TextView status;
    private TextView reportView;
    private LinearLayout devices;
    private boolean nativeLoaded;
    private boolean probing;
    private boolean permissionPending;
    private int activeDeviceId = -1;
    private String permissionAction;
    private String nativeReport = "";

    private final Runnable progressPoll = new Runnable() {
        @Override public void run() {
            if (probing && !isDestroyed()) {
                status.setText(NativeUsbProbe.progress());
                main.postDelayed(this, 400);
            }
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (permissionAction.equals(intent.getAction())) {
                permissionPending = false;
                UsbDevice device = deviceExtra(intent);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        && device != null && usb.hasPermission(device)) {
                    note("USB permission granted");
                    startProbe(device);
                } else {
                    note("USB permission denied or device removed. Tap the device to retry.");
                    scan();
                }
            } else {
                UsbDevice device = deviceExtra(intent);
                if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())
                        && device != null && device.getDeviceId() == activeDeviceId) {
                    note("Active USB device detached; native probe will release its descriptor on error/deadline.");
                }
                scan();
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        permissionAction = getPackageName() + ".USB_PERMISSION";
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            content.setPadding(padding, padding + insets.getSystemWindowInsetTop(),
                    padding, padding + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(scroll);
        TextView title = new TextView(this);
        title.setText("Nuvio Z — iPhone USB probe");
        title.setTextSize(23);
        content.addView(title);
        TextView scope = new TextView(this);
        scope.setText("Phase 1 · DEBUG ONLY\nReads model and iOS version over USB. No Apple sign-in, pairing, signing or app installation. Unlock the iPhone and keep this screen open. A probe has a 45-second deadline.");
        content.addView(scope);
        status = new TextView(this);
        status.setTextSize(17);
        content.addView(status);
        devices = new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        content.addView(devices);
        button(content, "Scan attached devices", this::scan);
        button(content, "Native self-test (no USB access)", () -> {
            if (probing || permissionPending || !nativeLoaded) return;
            runNative(null);
        });
        button(content, "Copy diagnostic report", () -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Nuvio USB probe", report()));
            status.setText("Diagnostic report copied");
        });
        reportView = new TextView(this);
        reportView.setTextIsSelectable(true);
        content.addView(reportView);
        note("Android " + Build.VERSION.RELEASE + "; SDK " + Build.VERSION.SDK_INT
                + "; ABIs " + TextUtils.join(",", Build.SUPPORTED_ABIS));
        try {
            note("Native loaded: " + NativeUsbProbe.version());
            nativeLoaded = true;
        } catch (LinkageError error) {
            note("BLOCKER native_load: " + error.getMessage());
        }
        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        scan();
    }

    private void button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
    }

    private void scan() {
        devices.removeAllViews();
        if (!getPackageManager().hasSystemFeature("android.hardware.usb.host")) {
            note("BLOCKER usb_host: Android does not report USB host support");
            status.setText("USB host support is unavailable on this Android device.");
            return;
        }
        int appleCount = 0;
        for (UsbDevice device : usb.getDeviceList().values()) {
            String descriptor = String.format(java.util.Locale.ROOT, "USB %04x:%04x; interfaces=%d",
                    device.getVendorId(), device.getProductId(), device.getInterfaceCount());
            note(descriptor);
            if (device.getVendorId() != 0x05ac) continue;
            appleCount++;
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                note(String.format(java.util.Locale.ROOT, "interface=%d class=%02x/%02x/%02x endpoints=%d",
                        intf.getId(), intf.getInterfaceClass(), intf.getInterfaceSubclass(),
                        intf.getInterfaceProtocol(), intf.getEndpointCount()));
            }
            button(devices, String.format(java.util.Locale.ROOT, "Probe Apple device %04x", device.getProductId()),
                    () -> requestProbe(device));
        }
        if (appleCount == 0 && !probing) {
            status.setText("No Apple USB device detected. Connect a data cable, unlock the iPhone, and ensure the Galaxy controls USB.");
        } else if (!probing && !permissionPending) {
            status.setText("Apple USB device detected. Tap Probe to grant USB access.");
        }
    }

    private void requestProbe(UsbDevice device) {
        if (probing || permissionPending || !nativeLoaded) return;
        if (usb.hasPermission(device)) startProbe(device);
        else {
            permissionPending = true;
            note("Requesting Android USB permission");
            Intent permission = new Intent(permissionAction).setPackage(getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(this, device.getDeviceId(), permission,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            try {
                usb.requestPermission(device, pending);
            } catch (RuntimeException error) {
                permissionPending = false;
                note("BLOCKER usb_permission: " + error.getMessage());
            }
        }
    }

    private void startProbe(UsbDevice device) {
        if (probing || device.getVendorId() != 0x05ac) return;
        runNative(device);
    }

    private void runNative(UsbDevice device) {
        probing = true;
        activeDeviceId = device == null ? -1 : device.getDeviceId();
        note(device == null ? "Running native negative-FD self-test" : "Opening granted USB connection");
        status.setText("Starting native probe…");
        main.postDelayed(progressPoll, 400);
        worker.execute(() -> {
            String result;
            try {
                if (device == null) result = NativeUsbProbe.probe(-1);
                else {
                    // Keep Java's descriptor alive throughout JNI; Rust duplicates it before use.
                    UsbDeviceConnection connection = usb.openDevice(device);
                    if (connection == null) throw new IllegalStateException("openDevice returned null; permission or attachment changed");
                    try {
                        result = NativeUsbProbe.probe(connection.getFileDescriptor());
                    } finally {
                        connection.close();
                    }
                }
            } catch (Exception | LinkageError error) {
                result = "BLOCKER android_open_or_jni: " + error.getClass().getSimpleName() + ": " + error.getMessage();
            }
            String finished = result;
            main.post(() -> {
                if (isDestroyed()) return;
                probing = false;
                activeDeviceId = -1;
                main.removeCallbacks(progressPoll);
                nativeReport = finished;
                Log.i(TAG, finished);
                status.setText(device == null ? "Native self-test finished; expected file_descriptor blocker below"
                        : "Probe finished; inspect muxConnected, lockdownConnected, deviceInfo and blocker below");
                reportView.setText(report());
            });
        });
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice deviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private void note(String message) {
        Log.i(TAG, message);
        journal.add(message);
        if (journal.size() > 120) journal.remove(0);
        if (reportView != null) reportView.setText(report());
    }

    String report() { return TextUtils.join("\n", journal) + "\n\n" + nativeReport; }

    @Override protected void onDestroy() {
        unregisterReceiver(receiver);
        main.removeCallbacks(progressPoll);
        worker.shutdown(); // Do not close a borrowed USB FD underneath a running native call.
        super.onDestroy();
    }
}
