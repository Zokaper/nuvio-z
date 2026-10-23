package com.nuvio.usbprobe;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Isolated USB/pairing experiment. No Apple credentials, signing or installation controls. */
public final class UsbProbeActivity extends Activity {
    private UsbManager usb;
    private TextView status;
    private TextView reportView;
    private LinearLayout devices;
    private boolean nativeLoaded;
    private UsbPermissionController permission;
    private UsbProbeSession session;
    private UsbProbeSession.Listener sessionListener;
    private int requestedMode;
    private String lastStage = "";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = UsbPermissionReceiver.deviceExtra(intent);
            note("usb_event: " + intent.getAction() + " device="
                    + (device == null ? "missing" : UsbPermissionReceiver.snapshot(device)));
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction()) && device != null) permission.detached(device);
            permission.devicesChanged();
            scan();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        session = UsbProbeSession.get(this);
        if (state != null) requestedMode = state.getInt("requestedMode", 0);
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
        scope.setText("Phase 2 · DEBUG ONLY\nPair iPhone requests Trust and saves the pairing securely on this Android. Saved pairing checks an authenticated connection. No Apple sign-in, signing or app installation. Unlock the iPhone. Trust can wait 90 seconds; the complete pairing operation is bounded by 180 seconds.");
        content.addView(scope);
        status = new TextView(this);
        status.setTextSize(17);
        content.addView(status);
        devices = new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        content.addView(devices);
        button(content, "Scan attached devices", this::scan);
        button(content, "Native self-test (no USB access)", () -> {
            if (session.busy() || permission.pending() || !nativeLoaded) return;
            session.start(null, 0);
        });
        button(content, "Cancel active probe", () -> session.cancel("user_cancelled"));
        button(content, "Copy diagnostic report", () -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Nuvio USB probe", report()));
            status.setText("Diagnostic report copied");
        });
        reportView = new TextView(this);
        reportView.setTextIsSelectable(true);
        content.addView(reportView);
        sessionListener = () -> {
            reportView.setText(report());
            status.setText(session.status());
        };
        session.observe(sessionListener);
        note("Probe phase2-0.4; Android " + Build.VERSION.RELEASE + "; SDK " + Build.VERSION.SDK_INT
                + "; ABIs " + TextUtils.join(",", Build.SUPPORTED_ABIS));
        try {
            note("Native loaded: " + NativeUsbProbe.version());
            nativeLoaded = true;
        } catch (LinkageError error) {
            note("BLOCKER native_load: " + error.getMessage());
        }
        permission = new UsbPermissionController(this, usb, new UsbPermissionController.Listener() {
            @Override public void state(String stage, String message) { UsbProbeActivity.this.stage(stage, message); }
            @Override public void granted(UsbDevice device) {
                if (!isDestroyed()) session.start(device, requestedMode);
            }
        });
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        scan();
        permission.resume();
        if (!session.status().isEmpty()) status.setText(session.status());
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
            String descriptor = String.format(java.util.Locale.ROOT, "USB %04x:%04x; instance=%s interfaces=%d hasPermission=%s",
                    device.getVendorId(), device.getProductId(), UsbPermissionReceiver.key(device),
                    device.getInterfaceCount(), usb.hasPermission(device));
            note(descriptor);
            if (device.getVendorId() != 0x05ac) continue;
            appleCount++;
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface intf = device.getInterface(i);
                note(String.format(java.util.Locale.ROOT, "interface=%d class=%02x/%02x/%02x endpoints=%d",
                        intf.getId(), intf.getInterfaceClass(), intf.getInterfaceSubclass(),
                        intf.getInterfaceProtocol(), intf.getEndpointCount()));
            }
            button(devices, "Pair iPhone / Trust", () -> requestProbe(device, 3));
            button(devices, "Saved pairing: authenticated reconnect", () -> requestProbe(device, 4));
            button(devices, "Prepare Developer Mode reboot recovery", () -> requestProbe(device, 5));
            button(devices, String.format(java.util.Locale.ROOT, "USB info only (Phase 1): Apple %04x", device.getProductId()),
                    () -> requestProbe(device, 0));
            button(devices, "Unplug recovery test (10-second hold after mux)", () -> requestProbe(device, 1));
        }
        if (appleCount == 0 && !session.busy() && lastStage.isEmpty()) {
            status.setText("No Apple USB device detected. Connect a data cable, unlock the iPhone, and ensure the Galaxy controls USB.");
        } else if (appleCount > 0 && !session.busy() && !permission.pending() && lastStage.isEmpty()) {
            status.setText("iPhone detected. Tap Pair iPhone / Trust to begin, or Saved pairing to reconnect.");
        }
    }

    private void requestProbe(UsbDevice device, int mode) {
        if (session.busy() || !nativeLoaded || permission.pending()) return;
        requestedMode = mode;
        permission.request(device);
    }

    private void stage(String stage, String message) {
        lastStage = stage;
        note(stage + ": " + message);
        status.setText(stage + "\n" + message);
    }

    private void note(String message) {
        session.note(message);
    }

    String report() { return session.report(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("requestedMode", requestedMode);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        permission.stop();
        unregisterReceiver(receiver);
        session.unobserve(sessionListener);
        super.onDestroy();
    }
}
