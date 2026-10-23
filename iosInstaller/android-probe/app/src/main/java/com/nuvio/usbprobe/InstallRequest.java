package com.nuvio.usbprobe;

import android.content.Context;
import java.util.Arrays;

/** Ephemeral input, passed directly to native code. Never serialized into a diagnostic or Bundle. */
final class InstallRequest {
    final String email;
    final char[] password;
    final String cacheDirectory;
    final PairingStore signingStore;
    InstallRequest(Context context, String email, char[] password) {
        this.email = email.trim().toLowerCase(java.util.Locale.ROOT);
        this.password = password;
        this.cacheDirectory = new java.io.File(context.getCacheDir(), "install-proof").getAbsolutePath();
        this.signingStore = new PairingStore(context, "usb-signing-v1");
    }
    void erase() { Arrays.fill(password, '\0'); }
}
