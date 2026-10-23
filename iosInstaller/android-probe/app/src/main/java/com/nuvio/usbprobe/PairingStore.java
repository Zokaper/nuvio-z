package com.nuvio.usbprobe;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** JNI-only secret store. Pair records never enter diagnostic JSON, clipboard or preferences.
 * Versioned authenticated envelopes use app-private no-backup files and an Android Keystore key. */
final class PairingStore {
    private static final byte[] MAGIC = {'N', 'Z', 'P', '1'};
    private static final int MAX_BYTES = 1024 * 1024;
    private final File directory;
    private final String alias;

    static final class Failure extends RuntimeException {
        Failure(String code) { super(code); }
    }

    PairingStore(Context context) { this(context, "usb-pairing-v1"); }
    PairingStore(Context context, String namespace) {
        if (!namespace.matches("[a-zA-Z0-9-]+")) throw new IllegalArgumentException("namespace");
        directory = new File(context.getNoBackupFilesDir(), namespace);
        alias = context.getPackageName() + "." + namespace;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new Failure("storage_unavailable");
    }

    private static String binding(String udid) throws Exception {
        if (!udid.matches("[a-fA-F0-9-]{16,64}")) throw new Failure("invalid_device_identity");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(udid.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        File[] existing = directory.listFiles();
        if (existing == null) throw new Failure("storage_unavailable");
        // Never create a replacement key over unreadable existing records after restore/key loss.
        for (File file : existing) if (file.getName().contains(".sealed")) throw new Failure("key_unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return generator.generateKey();
    }

    private JSONObject read(String name) throws Exception {
        AtomicFile file = new AtomicFile(new File(directory, name + ".sealed"));
        byte[] envelope;
        try {
            if (file.getBaseFile().length() > MAX_BYTES) throw new Failure("store_corrupt");
            envelope = file.readFully(); // AtomicFile restores its last-good backup before reading.
        } catch (FileNotFoundException missing) {
            if (file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists())
                throw new Failure("storage_unavailable");
            return null;
        }
        if (envelope.length < 32 || envelope.length > MAX_BYTES
                || !Arrays.equals(MAGIC, Arrays.copyOf(envelope, 4))) throw new Failure("store_corrupt");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(envelope, 4, 16)));
        cipher.updateAAD(("NuvioZPairing-v1:" + name).getBytes(StandardCharsets.UTF_8));
        byte[] plain = cipher.doFinal(envelope, 16, envelope.length - 16);
        try {
            JSONObject result = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            if (result.getInt("schema") != 1) throw new Failure("store_corrupt");
            return result;
        } finally { Arrays.fill(plain, (byte) 0); }
    }

    private void write(String name, JSONObject value) throws Exception {
        value.put("schema", 1);
        byte[] plain = value.toString().getBytes(StandardCharsets.UTF_8);
        if (plain.length > MAX_BYTES - 32) throw new Failure("record_too_large");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(("NuvioZPairing-v1:" + name).getBytes(StandardCharsets.UTF_8));
        byte[] encrypted;
        try { encrypted = cipher.doFinal(plain); }
        finally { Arrays.fill(plain, (byte) 0); }
        if (cipher.getIV().length != 12) throw new Failure("storage_unavailable");
        AtomicFile file = new AtomicFile(new File(directory, name + ".sealed"));
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(MAGIC);
            output.write(cipher.getIV());
            output.write(encrypted);
            output.getFD().sync();
            file.finishWrite(output); // Syncs before atomic replacement; retains last good file on failure.
            output = null;
        } finally { if (output != null) file.failWrite(output); }
    }

    private JSONObject device(String udid, boolean create) throws Exception {
        JSONObject record = read(binding(udid));
        if (record == null && create) {
            record = new JSONObject().put("udid", udid).put("hostId", UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT));
            write(binding(udid), record); // Identity is durable before requesting Trust.
        }
        if (record != null && (!udid.equals(record.getString("udid")) || !record.has("hostId")))
            throw new Failure("store_corrupt");
        return record;
    }

    // These methods are called on the JNI worker thread. Errors expose only fixed non-sensitive codes.
    synchronized String identity(String udid) {
        try {
            JSONObject host = read("host");
            if (host == null) {
                host = new JSONObject().put("systemBuid", UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT));
                write("host", host);
            }
            return device(udid, true).getString("hostId") + "\n" + host.getString("systemBuid");
        } catch (Failure failure) { throw failure; }
        catch (Exception error) { throw new Failure("store_unreadable"); }
    }

    synchronized byte[] loadRecord(String udid) {
        try {
            JSONObject record = device(udid, false);
            if (record == null || !record.has("lockdown")) return null;
            return Base64.decode(record.getString("lockdown"), Base64.NO_WRAP);
        } catch (Failure failure) { throw failure; }
        catch (Exception error) { throw new Failure("store_unreadable"); }
    }

    synchronized void saveRecord(String udid, byte[] lockdown) {
        try {
            if (lockdown.length == 0 || lockdown.length > MAX_BYTES / 2) throw new Failure("record_too_large");
            JSONObject record = device(udid, false);
            if (record == null) throw new Failure("identity_missing");
            // Update only Lockdown bytes: retain optional remote-pairing/unknown envelope fields.
            record.put("lockdown", Base64.encodeToString(lockdown, Base64.NO_WRAP));
            write(binding(udid), record);
            byte[] saved = loadRecord(udid);
            if (saved == null || !MessageDigest.isEqual(saved, lockdown)) throw new Failure("store_verify_failed");
            Arrays.fill(saved, (byte) 0);
        } catch (Failure failure) { throw failure; }
        catch (Exception error) { throw new Failure("store_unreadable"); }
    }

    // 0=query, 1=arm expected reboot, 2=clear after authenticated reconnect. No phone reboot is initiated.
    synchronized int rebootState(String udid, int action) {
        try {
            String expected = binding(udid);
            JSONObject host = read("host");
            if (host == null) throw new Failure("identity_missing");
            String pending = host.optString("rebootDevice", "");
            if (!pending.isEmpty() && !pending.equals(expected)) return 2;
            if (action == 1) { host.put("rebootDevice", expected); write("host", host); return 1; }
            if (action == 2) { host.remove("rebootDevice"); write("host", host); return 0; }
            return pending.isEmpty() ? 0 : 1;
        } catch (Failure failure) { throw failure; }
        catch (Exception error) { throw new Failure("store_unreadable"); }
    }

    private static String secretName(String key) throws Exception {
        if (key.length() > 1024) throw new Failure("invalid_secret_key");
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder("secret-");
        for (byte b : hash) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
    synchronized byte[] getSecret(String key) {
        try {
            JSONObject value = read(secretName(key));
            return value == null ? null : Base64.decode(value.getString("value"), Base64.NO_WRAP);
        } catch (Failure failure) { throw failure; }
        catch (Exception error) { throw new Failure("store_unreadable"); }
    }
    synchronized void putSecret(String key, byte[] bytes) {
        try { write(secretName(key), new JSONObject().put("value", Base64.encodeToString(bytes, Base64.NO_WRAP))); }
        catch (Failure failure) { throw failure; }
        catch (Exception error) { throw new Failure("store_unreadable"); }
    }
    synchronized void deleteSecret(String key) {
        try { new AtomicFile(new File(directory, secretName(key) + ".sealed")).delete(); }
        catch (Exception error) { throw new Failure("storage_unavailable"); }
    }
}
