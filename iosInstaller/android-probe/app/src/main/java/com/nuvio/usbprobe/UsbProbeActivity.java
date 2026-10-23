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
import android.widget.EditText;
import android.app.AlertDialog;

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
    private EditText appleEmail;
    private EditText applePassword;
    private EditText appleCode;
    private InstallRequest pendingInstall;
    private long certificatePrompt;

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
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        scope.setText("Experimental Android installer · Nuvio Z Debug\nEnter your Apple Account here, never in chat. Signing uses isideload, as on desktop. The password is used for this attempt; signing keys are encrypted on Android. Keep both phones unlocked and Android online. After an iPhone reboot, re-seat the Galaxy end of the cable if USB is missing.");
        content.addView(scope);
        status = new TextView(this);
        status.setTextSize(17);
        content.addView(status);
        appleEmail = input(content, "Apple Account email", android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        applePassword = input(content, "Apple Account password", android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        appleCode = input(content, "Apple verification code (when requested)", android.text.InputType.TYPE_CLASS_NUMBER);
        button(content, "Submit verification code", () -> answer(appleCode.getText().toString()));
        button(content, "Send code to Apple devices", () -> answer("devices"));
        button(content, "Send SMS to first trusted number", () -> answer("sms"));
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
            showCertificateChoice();
        };
        session.observe(sessionListener);
        note("Installer proof 0.5.2; Android " + Build.VERSION.RELEASE + "; SDK " + Build.VERSION.SDK_INT
                + "; ABIs " + TextUtils.join(",", Build.SUPPORTED_ABIS));
        try {
            NativeUsbProbe.initialize(getApplicationContext());
            note("Native loaded: " + NativeUsbProbe.version());
            nativeLoaded = true;
        } catch (LinkageError | RuntimeException error) {
            note("BLOCKER native_load: " + error.getMessage());
        }
        permission = new UsbPermissionController(this, usb, new UsbPermissionController.Listener() {
            @Override public void state(String stage, String message) {
                if (pendingInstall != null && java.util.Arrays.asList("permission_denied", "permission_grant_lost",
                        "permission_wrong_device", "permission_callback_incomplete", "permission_callback_timeout",
                        "permission_request_expired", "device_changed", "usb_disconnected", "usb_reenumerated",
                        "usb_identity_changed", "permission_request_failed", "permission_state_failed").contains(stage)) {
                    pendingInstall.erase(); pendingInstall = null;
                }
                UsbProbeActivity.this.stage(stage, message);
            }
            @Override public void granted(UsbDevice device) {
                if (isDestroyed()) return;
                if (requestedMode == 6 && pendingInstall == null) {
                    stage("install_input", "USB access granted. Re-enter the Apple password and tap Install.");
                    return;
                }
                session.start(device, requestedMode, pendingInstall);
                pendingInstall = null;
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

    private EditText input(LinearLayout parent, String hint, int type) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(type);
        field.setSingleLine(true);
        field.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) field.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        parent.addView(field);
        return field;
    }
    private void answer(String value) {
        if (!nativeLoaded) return;
        boolean accepted = NativeUsbProbe.answerChallenge(value);
        appleCode.getText().clear();
        if (!accepted) status.setText("No matching Apple verification request is waiting.");
    }
    private void showCertificateChoice() {
        if (!nativeLoaded) return;
        try {
            String text = NativeUsbProbe.certificateChoices();
            if (text == null || text.isEmpty()) return;
            org.json.JSONObject prompt = new org.json.JSONObject(text);
            if (prompt.getLong("id") == certificatePrompt) return;
            certificatePrompt = prompt.getLong("id");
            org.json.JSONArray choices = prompt.getJSONArray("choices");
            String[] labels = new String[choices.length()];
            for (int i = 0; i < labels.length; i++) labels[i] = choices.getString(i);
            int[] selected = {-1};
            new AlertDialog.Builder(this).setTitle("Apple limit: revoking can stop other signed apps")
                .setSingleChoiceItems(labels, -1, (dialog, which) -> selected[0] = which)
                .setPositiveButton("Revoke selected and continue", (dialog, which) -> {
                    NativeUsbProbe.answerChallenge(selected[0] < 0 ? "abort" : "cert:" + selected[0]);
                }).setNegativeButton("Cancel", (dialog, which) -> NativeUsbProbe.answerChallenge("abort"))
                .setOnCancelListener(dialog -> NativeUsbProbe.answerChallenge("abort")).show();
        } catch (org.json.JSONException ignored) { status.setText("Could not display certificate choice; no certificate was revoked."); }
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
            button(devices, "Sign and install Nuvio Z Debug", () -> {
                if (session.busy() || permission.pending() || !nativeLoaded) return;
                if (appleEmail.getText().toString().trim().isEmpty() || applePassword.length() == 0) {
                    status.setText("Enter your Apple Account and password directly above."); return;
                }
                char[] password = new char[applePassword.length()];
                applePassword.getText().getChars(0, password.length, password, 0);
                if (pendingInstall != null) pendingInstall.erase();
                pendingInstall = new InstallRequest(this, appleEmail.getText().toString(), password);
                applePassword.getText().clear();
                requestProbe(device, 6);
            });
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
        if (pendingInstall != null) pendingInstall.erase();
        permission.stop();
        unregisterReceiver(receiver);
        session.unobserve(sessionListener);
        super.onDestroy();
    }
}
