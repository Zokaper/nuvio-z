package com.nuvio.usbprobe;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Desktop's Tauri commands and events, for the shared installer UI (iosInstaller/ui) running in a WebView.
 * android-bridge.js turns `__TAURI__.core.invoke(cmd, args)` into {@link #invoke}; replies come back through
 * `__nuvioResolve` and events through `__nuvioEmit`, with the payloads desktop's app/src/main.rs sends.
 *
 * Every USB operation is one native call on {@link UsbProbeSession} (the proven proof path): mode 3 pairs,
 * 7 reads the phone's status, 8 reveals Developer Mode, 6 signs in, signs and installs. The UI's two-step
 * sign_in then install_nuvio is one mode-6 call: sign_in answers when native reports `apple_authenticated`,
 * install_nuvio when the call ends. All state belongs to the main thread.
 */
final class InstallerBridge {
    private static final int APPLE = 0x05ac;
    private static final List<String> NO_ACCESS = Arrays.asList("permission_denied", "permission_grant_lost",
            "permission_callback_incomplete", "permission_callback_timeout", "permission_request_failed",
            "permission_state_failed");

    private final Context context;
    private final WebView web;
    private final UsbManager usb;
    private final UsbProbeSession session;
    private final UsbPermissionController permission;
    private final PairingStore account;
    private final PairingStore signing;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> errors = new ArrayList<>();

    /** The last mode-7 status for the attached phone; cleared when it is unplugged. */
    private JSONObject phone;
    /** null, or the Trust request's state: waiting | denied | timedOut | locked | failed. */
    private String trustState;
    private boolean usbDenied;
    /** Asked for USB access once already: ask again by itself after a re-plug (the Developer Mode restart). */
    private boolean wantUsb;

    /** The mode-6 call in progress: who is waiting for its sign-in and its end. */
    private int signInCall;
    private int installCall;
    private boolean installing;
    private boolean authenticated;
    /** What sign_in answers with: the account, lower-cased, as desktop's sign_in returns it. */
    private String pendingEmail;
    private String lastStatus = "";
    private long certificatePrompt;
    /** A failure after sign_in answered but before install_nuvio asked: install_nuvio reports it. */
    private JSONObject lateFailure;

    InstallerBridge(Context context, WebView web) {
        this.context = context;
        this.web = web;
        usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        session = UsbProbeSession.get(context);
        account = new PairingStore(context, "installer-account-v1");
        signing = new PairingStore(context, "usb-signing-v1");
        permission = new UsbPermissionController(context, usb, new UsbPermissionController.Listener() {
            @Override public void state(String stage, String message) {
                session.note("usb_permission " + stage + ": " + message);
                if (NO_ACCESS.contains(stage)) usbDenied = true;
            }
            @Override public void granted(UsbDevice device) { usbDenied = false; }
        });
        session.observe(this::progress);
        permission.resume();
    }

    void stop() { permission.stop(); }

    /** USB attach/detach, from the Activity's receiver. */
    void devicesChanged(UsbDevice detached) {
        if (detached != null && detached.getVendorId() == APPLE) {
            permission.detached(detached);
            phone = null;
            if ("waiting".equals(trustState)) trustState = null;
        }
        permission.devicesChanged();
    }

    // ------------------------------------------------------------------------------------------- JS entry points

    @JavascriptInterface public void invoke(int id, String command, String args) {
        main.post(() -> {
            try { dispatch(id, command, args == null || args.isEmpty() ? new JSONObject() : new JSONObject(args)); }
            catch (Exception error) {
                session.note("bridge " + command + " failed: " + error.getClass().getSimpleName());
                reject(id, error.getClass().getSimpleName());
            }
        });
    }

    @JavascriptInterface public void copy(String text) {
        main.post(() -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Nuvio Z Installer report", text));
        });
    }

    private void dispatch(int id, String command, JSONObject args) throws JSONException {
        switch (command) {
            case "saved_account": resolve(id, savedEmail()); return;
            case "forget_account": account.deleteSecret("email"); resolve(id, null); return;
            case "local_nuvio": resolve(id, null); return;
            case "probe_device": probe(id); return;
            case "request_usb": requestUsb(); resolve(id, null); return;
            case "pair_device": pair(); resolve(id, null); return;
            case "reveal_developer_mode": reveal(id); return;
            case "sign_in": signIn(id, args); return;
            case "answer_two_factor": answerTwoFactor(id, args.getJSONObject("answer")); return;
            case "answer_certificates": answerCertificates(id, args.optJSONArray("serials")); return;
            case "install_nuvio": install(id); return;
            case "reset_sign_in":
                signing.deleteSecret("anisette_state");
                signing.deleteSecret("anisette_server");
                resolve(id, null);
                return;
            case "diagnostics": resolve(id, diagnostics()); return;
            case "place_nuvio_pairing":
                reject(id, "Not on Android yet: go back and use Repair, which adds it again.");
                return;
            default: reject(id, "Not available on Android: " + command);
        }
    }

    // ------------------------------------------------------------------------------------------------ the phone

    private UsbDevice apple() {
        for (UsbDevice device : usb.getDeviceList().values()) if (device.getVendorId() == APPLE) return device;
        return null;
    }

    private int appleCount() {
        int count = 0;
        for (UsbDevice device : usb.getDeviceList().values()) if (device.getVendorId() == APPLE) count++;
        return count;
    }

    /** Desktop's DeviceSnapshot, plus the Android fields the Android steps read. */
    private JSONObject snapshot() throws JSONException {
        UsbDevice device = apple();
        JSONObject snap = new JSONObject()
                .put("usbHost", context.getPackageManager().hasSystemFeature("android.hardware.usb.host"))
                .put("usbDevices", appleCount())
                .put("usbPermission", device != null && usb.hasPermission(device))
                .put("usbDenied", usbDenied)
                .put("usbAsking", permission.pending())
                .put("errors", new JSONArray());
        if (trustState != null) snap.put("trustState", trustState);
        if (phone != null) {
            for (String key : new String[] {"trusted", "iosVersion", "developerMode", "apps", "nuvioPairing"}) {
                if (phone.has(key)) snap.put(key, phone.get(key));
            }
        }
        return snap;
    }

    private void probe(int id) throws JSONException {
        UsbDevice device = apple();
        if (device != null && wantUsb && !usb.hasPermission(device) && !permission.pending() && !usbDenied) requestUsb();
        if (device == null || !usb.hasPermission(device) || session.busy() || permission.pending()) {
            resolve(id, snapshot());
            return;
        }
        boolean started = session.start(device, 7, null, report -> {
            try {
                JSONObject result = new JSONObject(report);
                JSONObject status = result.optJSONObject("status");
                if (result.optBoolean("ok") && status != null) phone = status;
                else note("status", result);
                resolve(id, snapshot());
            } catch (JSONException error) { reject(id, "status report unreadable"); }
        });
        if (!started) resolve(id, snapshot());
    }

    private void requestUsb() {
        wantUsb = true;
        usbDenied = false;
        UsbDevice device = apple();
        if (device != null && !usb.hasPermission(device) && !permission.pending()) permission.request(device);
    }

    private void pair() {
        UsbDevice device = apple();
        if (device == null || !usb.hasPermission(device) || "waiting".equals(trustState)) return;
        String previous = trustState;
        trustState = "waiting";
        boolean started = session.start(device, 3, null, report -> {
            try {
                JSONObject result = new JSONObject(report);
                if (result.optBoolean("ok")) {
                    trustState = null;
                    if (phone == null) phone = new JSONObject();
                    phone.put("trusted", true);
                    return;
                }
                String stage = stage(result);
                trustState = stage.equals("trust_denied") ? "denied" : stage.equals("trust_timeout") ? "timedOut"
                        : stage.equals("iphone_locked") ? "locked" : "failed";
                note("pairing", result);
            } catch (JSONException error) { trustState = "failed"; }
        });
        if (!started) trustState = previous;
    }

    private void reveal(int id) {
        UsbDevice device = apple();
        if (device == null || !usb.hasPermission(device)) { reject(id, "Plug the iPhone into this phone first."); return; }
        whenIdle(() -> session.start(device, 8, null, report -> {
            try {
                JSONObject result = new JSONObject(report);
                if (result.optBoolean("ok")) resolve(id, null);
                else { note("reveal", result); reject(id, message(result)); }
            } catch (JSONException error) { reject(id, "report unreadable"); }
        }), () -> reject(id, "The installer is busy with the iPhone. Try again in a moment."));
    }

    /** Runs `start` once no USB operation is running (a status read takes a second or two), or gives up. */
    private void whenIdle(Runnable start, Runnable busy) {
        long deadline = android.os.SystemClock.elapsedRealtime() + 20_000;
        Runnable attempt = new Runnable() {
            @Override public void run() {
                if (!session.busy() && !permission.pending()) { start.run(); return; }
                if (android.os.SystemClock.elapsedRealtime() > deadline) { busy.run(); return; }
                main.postDelayed(this, 250);
            }
        };
        attempt.run();
    }

    // --------------------------------------------------------------------------------- sign in, sign, install

    private Object savedEmail() {
        byte[] bytes = account.getSecret("email");
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private void signIn(int id, JSONObject args) throws JSONException {
        String email = args.optString("email", "").trim().toLowerCase(Locale.ROOT);
        String typed = args.isNull("password") ? "" : args.optString("password", "");
        boolean remember = args.optBoolean("remember");
        boolean debug = "debug".equals(args.optString("build"));
        if (email.isEmpty() || typed.isEmpty()) {
            reject(id, signInError("other", "Enter your Apple Account email and password."));
            return;
        }
        UsbDevice device = apple();
        if (device == null || !usb.hasPermission(device)) {
            reject(id, signInError("other", "Plug the iPhone into this phone and unlock it first."));
            return;
        }
        char[] password = typed.toCharArray();
        InstallRequest request = new InstallRequest(context, email, password, debug);
        whenIdle(() -> {
            signInCall = id;
            pendingEmail = email;
            installCall = 0;
            installing = true;
            authenticated = false;
            lateFailure = null;
            lastStatus = "";
            boolean started = session.start(device, 6, request, report -> finished(email, remember, report));
            if (!started) {
                request.erase();
                installing = false;
                reject(id, signInError("other", "The installer is busy with the iPhone. Try again in a moment."));
            }
        }, () -> {
            request.erase();
            reject(id, signInError("other", "The installer is busy with the iPhone. Try again in a moment."));
        });
    }

    private void install(int id) {
        if (lateFailure != null) {
            reject(id, lateFailure);
            lateFailure = null;
            return;
        }
        if (!installing || !authenticated) {
            reject(id, installError("sign", "other", "not signed in"));
            return;
        }
        installCall = id;
    }

    /** Native progress ("stage: message"), polled by the session: sign-in milestones, prompts, install progress. */
    private void progress() {
        if (!installing || !session.busy()) return;
        String status = session.status();
        if (status.equals(lastStatus)) return;
        lastStatus = status;
        int colon = status.indexOf(':');
        if (colon < 0) return;
        String stage = status.substring(0, colon);
        try {
            switch (stage) {
                case "apple_two_factor": emit("two-factor", new JSONObject()); break;
                case "certificate_choice": certificates(); break;
                case "apple_authenticated": signedIn(); break;
                case "download": signedIn(); installProgress("download", percent(status) / 100f); break;
                case "signing": signedIn(); installProgress("sign", percent(status) / 200f); break;
                case "installing": signedIn(); installProgress("sign", 0.5f + percent(status) / 200f); break;
                case "self_refresh_handoff": case "installed": signedIn(); installProgress("pairing", 0.5f); break;
                default: break;
            }
        } catch (JSONException ignored) { }
    }

    private void signedIn() {
        if (authenticated) return;
        authenticated = true;
        if (signInCall != 0) {
            resolve(signInCall, pendingEmail);
            signInCall = 0;
        }
    }

    private void certificates() throws JSONException {
        String text = NativeUsbProbe.certificateChoices();
        if (text == null || text.isEmpty()) return;
        JSONObject prompt = new JSONObject(text);
        if (prompt.getLong("id") == certificatePrompt) return;
        certificatePrompt = prompt.getLong("id");
        JSONArray certs = prompt.optJSONArray("certs");
        JSONArray list = new JSONArray();
        for (int i = 0; certs != null && i < certs.length(); i++) {
            JSONObject cert = certs.getJSONObject(i);
            list.put(new JSONObject().put("serial", String.valueOf(i)).put("madeBy", cert.optString("madeBy"))
                    .put("name", cert.optString("name")).put("expires", cert.opt("expires")));
        }
        emit("certificates", list);
    }

    private void answerCertificates(int id, JSONArray serials) {
        StringBuilder picked = new StringBuilder();
        for (int i = 0; serials != null && i < serials.length(); i++) {
            if (picked.length() > 0) picked.append(',');
            picked.append(serials.optString(i));
        }
        boolean accepted = NativeUsbProbe.answerChallenge(picked.length() == 0 ? "abort" : "cert:" + picked);
        if (accepted) resolve(id, null); else reject(id, "nothing is waiting for certificates");
    }

    private void answerTwoFactor(int id, JSONObject answer) {
        String reply;
        switch (answer.optString("kind")) {
            case "code": reply = answer.optString("code").replaceAll("[^0-9]", ""); break;
            case "resend": reply = "resend"; break;
            case "sendToDevices": reply = "devices"; break;
            case "sms": reply = "sms"; break;
            default: reply = "abort";
        }
        if (NativeUsbProbe.answerChallenge(reply)) resolve(id, null);
        else reject(id, "no sign-in is waiting for a code");
    }

    private void finished(String email, boolean remember, String report) {
        installing = false;
        try {
            JSONObject result = new JSONObject(report);
            JSONObject installation = result.optJSONObject("installation");
            boolean signedIn = installation != null && installation.optBoolean("appleAuthenticated");
            if (signedIn) {
                if (remember) account.putSecret("email", email.getBytes(StandardCharsets.UTF_8));
                else account.deleteSecret("email");
            }
            if (result.optBoolean("ok")) {
                signedIn();
                if (installCall != 0) resolve(installCall, new JSONObject()
                        .put("debug", installation != null && installation.optBoolean("debug"))
                        .put("source", "hosted").put("signingKey", true).put("updateTest", false));
                // Hand-off complete: the next status read sees the app and its connection file.
                phone = null;
            } else {
                note("install", result);
                String kind = installation == null ? "other" : installation.optString("errorKind", "other");
                String why = message(result);
                if (signInCall != 0) reject(signInCall, signInError(kind, why));
                else if (installCall != 0) reject(installCall, installError(uiStage(result), kind, why));
                else if (authenticated) lateFailure = installError(uiStage(result), kind, why);
            }
        } catch (JSONException error) {
            if (signInCall != 0) reject(signInCall, "report unreadable");
            else if (installCall != 0) reject(installCall, "report unreadable");
        }
        signInCall = 0;
        installCall = 0;
    }

    /** The install screen's stage for where a failed call stopped. */
    private static String uiStage(JSONObject result) {
        String stage = stage(result);
        if (stage.startsWith("download")) return "download";
        if (stage.startsWith("handoff")) return "pairing";
        return "sign";
    }

    // ---------------------------------------------------------------------------------------------- reporting

    private JSONObject diagnostics() throws JSONException {
        boolean anisette;
        try { anisette = signing.getSecret("anisette_state") != null; } catch (RuntimeException error) { anisette = false; }
        String version;
        try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception error) { version = "?"; }
        JSONArray noted = new JSONArray();
        for (String error : errors) noted.put(error);
        return new JSONObject()
                .put("installer", version)
                .put("os", "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ") "
                        + Build.MANUFACTURER + " " + Build.MODEL)
                .put("device", snapshot())
                .put("rememberedAccount", savedEmail() != null)
                .put("anisetteIdentity", anisette)
                .put("signingKey", JSONObject.NULL)
                .put("localIpa", JSONObject.NULL)
                .put("anisetteServers", new JSONArray())
                .put("errors", noted)
                // The proof's own journal and reports: stages, USB descriptors, booleans; never credentials.
                .put("androidLog", session.report());
    }

    private static String stage(JSONObject result) {
        String blocker = result.optString("blocker", "");
        int colon = blocker.indexOf(':');
        return colon < 0 ? blocker : blocker.substring(0, colon);
    }

    private static String message(JSONObject result) {
        String blocker = result.optString("blocker", "");
        int colon = blocker.indexOf(':');
        return colon < 0 ? blocker : blocker.substring(colon + 1).trim();
    }

    private void note(String what, JSONObject result) {
        errors.add((System.currentTimeMillis() / 1000) + " " + what + ": " + result.optString("blocker", "failed"));
        if (errors.size() > 40) errors.remove(0);
    }

    private static float percent(String status) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)%").matcher(status);
        float last = 0;
        while (m.find()) last = Float.parseFloat(m.group(1));
        return Math.max(0, Math.min(100, last));
    }

    private static JSONObject signInError(String kind, String summary) {
        try { return new JSONObject().put("kind", kind).put("code", JSONObject.NULL).put("summary", summary).put("detail", ""); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static JSONObject installError(String stage, String kind, String summary) {
        try { return new JSONObject().put("stage", stage).put("error", signInError(kind, summary)); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private void installProgress(String stage, float fraction) throws JSONException {
        emit("install-progress", new JSONObject().put("stage", stage).put("fraction", (double) fraction));
    }

    // ------------------------------------------------------------------------------------------ to the page

    private void resolve(int id, Object value) { reply(id, true, value); }
    private void reject(int id, Object value) { reply(id, false, value); }

    private void reply(int id, boolean ok, Object value) {
        String json = value == null ? "" : value instanceof String ? JSONObject.quote((String) value) : value.toString();
        web.evaluateJavascript("window.__nuvioResolve(" + id + "," + ok + "," + JSONObject.quote(json) + ")", null);
    }

    private void emit(String event, Object payload) {
        web.evaluateJavascript("window.__nuvioEmit(" + JSONObject.quote(event) + ","
                + JSONObject.quote(payload == null ? "" : payload.toString()) + ")", null);
    }
}
