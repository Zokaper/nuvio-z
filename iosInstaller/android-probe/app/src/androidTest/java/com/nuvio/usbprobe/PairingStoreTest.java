package com.nuvio.usbprobe;

import static org.junit.Assert.*;
import android.content.Context;
import android.util.AtomicFile;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Android Keystore/AtomicFile tests using an isolated namespace and synthetic bytes only. */
@RunWith(AndroidJUnit4.class)
public final class PairingStoreTest {
    private static final String DEVICE = "00000000-0000000000000001";
    private static final String OTHER = "00000000-0000000000000002";
    private static final byte[] RECORD = "synthetic-pair-record-not-a-real-credential".getBytes(StandardCharsets.UTF_8);
    private Context context;
    private String namespace;
    private File directory;
    private PairingStore store;

    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        namespace = "pairing-tests-" + UUID.randomUUID();
        directory = new File(context.getNoBackupFilesDir(), namespace);
        store = new PairingStore(context, namespace);
    }

    @After public void cleanup() throws Exception {
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        keys.deleteEntry(context.getPackageName() + "." + namespace);
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) assertTrue(file.delete());
        assertTrue(directory.delete());
    }

    private File recordFile(String device) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(device.getBytes(StandardCharsets.UTF_8));
        StringBuilder name = new StringBuilder();
        for (byte b : hash) name.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return new File(directory, name + ".sealed");
    }
    private void initialize() { store.identity(DEVICE); store.saveRecord(DEVICE, RECORD); }
    private void assertUnreadable() {
        try { store.loadRecord(DEVICE); fail("Corrupt/undecryptable records must not be treated as missing"); }
        catch (PairingStore.Failure expected) {
            assertTrue(Arrays.asList("store_corrupt", "store_unreadable", "key_unavailable").contains(expected.getMessage()));
        }
    }

    @Test public void identitiesAndRecordsSurviveStoreRecreation() {
        String identity = store.identity(DEVICE);
        assertNull(store.loadRecord(DEVICE));
        store.saveRecord(DEVICE, RECORD);
        PairingStore reopened = new PairingStore(context, namespace);
        assertEquals(identity, reopened.identity(DEVICE));
        assertArrayEquals(RECORD, reopened.loadRecord(DEVICE));
        String other = reopened.identity(OTHER);
        assertNotEquals(identity.split("\n")[0], other.split("\n")[0]);
        assertEquals(identity.split("\n")[1], other.split("\n")[1]);
        assertNull(reopened.loadRecord(OTHER));
    }

    @Test public void filesAreEncryptedBoundToDeviceAndExcludedFromBackup() throws Exception {
        initialize();
        assertTrue(directory.getCanonicalPath().startsWith(context.getNoBackupFilesDir().getCanonicalPath() + File.separator));
        for (File file : directory.listFiles()) {
            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
            assertTrue(raw.startsWith("NZP1"));
            assertFalse(raw.contains(DEVICE));
            assertFalse(raw.contains(new String(RECORD, StandardCharsets.UTF_8)));
            assertFalse(file.getName().contains(DEVICE));
        }
    }

    @Test public void corruptCiphertextIsSurfacedAndNotOverwritten() throws Exception {
        initialize();
        File file = recordFile(DEVICE);
        byte[] corrupt = Files.readAllBytes(file.toPath());
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(file.toPath(), corrupt);
        assertUnreadable();
        assertArrayEquals(corrupt, Files.readAllBytes(file.toPath()));
    }

    @Test public void missingKeystoreKeyCannotSilentlyReplaceAnExistingIdentity() throws Exception {
        initialize();
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        keys.deleteEntry(context.getPackageName() + "." + namespace);
        assertUnreadable();
        assertFalse(keys.containsAlias(context.getPackageName() + "." + namespace));
    }

    @Test public void movingCiphertextToAnotherDeviceFileFailsAuthentication() throws Exception {
        initialize();
        store.identity(OTHER);
        Files.write(recordFile(OTHER).toPath(), Files.readAllBytes(recordFile(DEVICE).toPath()));
        try { store.loadRecord(OTHER); fail("Record file swapping must fail AAD authentication"); }
        catch (PairingStore.Failure expected) { assertEquals("store_unreadable", expected.getMessage()); }
    }

    @Test public void eachWriteUsesANewNonceAndStillReadsBack() throws Exception {
        initialize();
        byte[] before = Files.readAllBytes(recordFile(DEVICE).toPath());
        store.saveRecord(DEVICE, RECORD);
        byte[] after = Files.readAllBytes(recordFile(DEVICE).toPath());
        assertFalse(Arrays.equals(Arrays.copyOfRange(before, 4, 16), Arrays.copyOfRange(after, 4, 16)));
        assertArrayEquals(RECORD, store.loadRecord(DEVICE));
    }

    @Test public void interruptedAtomicWriteRetainsLastGoodRecord() throws Exception {
        initialize();
        AtomicFile atomic = new AtomicFile(recordFile(DEVICE));
        FileOutputStream partial = atomic.startWrite();
        partial.write(new byte[] {1, 2, 3});
        atomic.failWrite(partial);
        assertArrayEquals(RECORD, new PairingStore(context, namespace).loadRecord(DEVICE));
    }

    @Test public void expectedRebootSurvivesRecreationAndRejectsAnotherDevice() {
        initialize();
        assertEquals(0, store.rebootState(DEVICE, 0));
        assertEquals(1, store.rebootState(DEVICE, 1));
        PairingStore reopened = new PairingStore(context, namespace);
        assertEquals(1, reopened.rebootState(DEVICE, 0));
        assertEquals(2, reopened.rebootState(OTHER, 2));
        assertEquals(1, reopened.rebootState(DEVICE, 0));
        assertEquals(0, reopened.rebootState(DEVICE, 2));
    }

    @Test public void jniBridgeRoundTripsOnlySyntheticSecrets() {
        assertEquals("ok", NativeUsbProbe.testStoreBridge(store));
        assertArrayEquals(RECORD, store.loadRecord(DEVICE));
    }

    @Test public void signingSecretsAreEncryptedAndSurviveRecreation() throws Exception {
        store.putSecret("synthetic-account/key", RECORD);
        assertArrayEquals(RECORD, new PairingStore(context, namespace).getSecret("synthetic-account/key"));
        for (File file : directory.listFiles()) {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
            assertFalse(content.contains(new String(RECORD, StandardCharsets.UTF_8)));
        }
        store.deleteSecret("synthetic-account/key");
        assertNull(store.getSecret("synthetic-account/key"));
    }

    @Test public void jniBridgeClearsJavaExceptionsAndReturnsOnlyAFixedCode() throws Exception {
        initialize();
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        keys.deleteEntry(context.getPackageName() + "." + namespace);
        assertEquals("pairing_store_key", NativeUsbProbe.testStoreBridge(store));
        assertTrue(NativeUsbProbe.probe(-1).contains("file_descriptor:"));
    }
}
