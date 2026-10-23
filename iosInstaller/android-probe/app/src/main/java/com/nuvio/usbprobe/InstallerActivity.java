package com.nuvio.usbprobe;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Nuvio Z Installer for Android: desktop's installer screens (iosInstaller/ui, copied into the APK's assets by
 * Gradle) in a WebView, driven through {@link InstallerBridge}. This phone is the USB host for the iPhone.
 */
public final class InstallerActivity extends Activity {
    private WebView web;
    private InstallerBridge bridge;

    private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = UsbPermissionReceiver.deviceExtra(intent);
            UsbProbeSession.get(context).note("usb_event: " + intent.getAction() + " device="
                    + (device == null ? "missing" : UsbPermissionReceiver.snapshot(device)));
            if (bridge != null) bridge.devicesChanged(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction()) ? device : null);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Both phones stay awake through the sign-in, the download and the install.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        try {
            NativeUsbProbe.initialize(getApplicationContext());
        } catch (LinkageError | RuntimeException error) {
            UsbProbeSession.get(this).note("BLOCKER native_load: " + error.getMessage());
        }
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(false);
        settings.setSaveFormData(false);
        settings.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            // The pages are ours and local; anything else (a link in a step) opens in the browser.
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("file:///android_asset/")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl())); } catch (RuntimeException ignored) { }
                return true;
            }
        });
        // Android 15+ draws every app edge to edge, and a WebView ignores its own padding: a plain container keeps
        // the page clear of the status bar, the navigation bar and the keyboard (the password and code fields).
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(web);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                return android.view.WindowInsets.CONSUMED;
            }
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        bridge = new InstallerBridge(getApplicationContext(), web);
        web.addJavascriptInterface(bridge, "NuvioAndroid");
        setContentView(root);
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbEvents, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbEvents, filter);
        web.loadUrl("file:///android_asset/ui/index.html");
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::back);
        }
    }

    // The installer has its own Back; the system one (button or gesture) presses it instead of closing mid-install.
    private void back() {
        web.evaluateJavascript("document.querySelector('[data-action=back]')?.click()", null);
    }

    @SuppressLint("GestureBackNavigation") // API 33+ uses the OnBackInvokedCallback registered in onCreate.
    @Override public void onBackPressed() { back(); }

    @Override protected void onDestroy() {
        unregisterReceiver(usbEvents);
        if (bridge != null) bridge.stop();
        web.destroy();
        super.onDestroy();
    }
}
