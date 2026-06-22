package org.deaddrop.app;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small Android-Keystore-backed encrypted preference helper.
 *
 * DeadDrop intentionally avoids network/services. This protects local group keys
 * and text logs at rest with a non-exportable AES key generated in Android
 * Keystore. Audio is never stored here or anywhere else.
 */
public final class SecurePrefs {
    private static final String KEY_ALIAS = "deaddrop-local-store-v1";
    private static final String ENC_SUFFIX = ".enc";
    private static final int NONCE_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    private SecurePrefs() {}

    public static String getString(SharedPreferences prefs, String key, String defValue) {
        try {
            String encrypted = prefs.getString(key + ENC_SUFFIX, null);
            if (encrypted != null) return decryptString(key, encrypted);

            // One-time migration from the first prototype's plaintext prefs.
            String legacy = prefs.getString(key, null);
            if (legacy != null) {
                putString(prefs, key, legacy);
                prefs.edit().remove(key).apply();
                return legacy;
            }
        } catch (Exception ignored) {
            // If keystore is unavailable or data is corrupt, fail closed to the
            // caller's default instead of returning stale plaintext.
        }
        return defValue;
    }

    public static void putString(SharedPreferences prefs, String key, String value) throws Exception {
        prefs.edit()
                .putString(key + ENC_SUFFIX, encryptString(key, value == null ? "" : value))
                .remove(key)
                .apply();
    }

    public static void remove(SharedPreferences prefs, String key) {
        prefs.edit().remove(key).remove(key + ENC_SUFFIX).apply();
    }

    public static void deleteMasterKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {}
    }

    private static String encryptString(String prefKey, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // Android Keystore keys created with randomized encryption enabled must
        // generate their own GCM IV. Passing a caller-provided IV in ENCRYPT_MODE
        // throws "Caller-provided IV not permitted" on real devices.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        cipher.updateAAD(prefKey.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] nonce = cipher.getIV();
        if (nonce == null || nonce.length != NONCE_LEN) throw new IllegalStateException("Unexpected GCM IV length.");
        ByteBuffer out = ByteBuffer.allocate(NONCE_LEN + ciphertext.length);
        out.put(nonce);
        out.put(ciphertext);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.array());
    }

    private static String decryptString(String prefKey, String encoded) throws Exception {
        byte[] raw = Base64.getUrlDecoder().decode(encoded);
        if (raw.length <= NONCE_LEN + 16) throw new IllegalArgumentException("Encrypted value too short");
        byte[] nonce = new byte[NONCE_LEN];
        byte[] ciphertext = new byte[raw.length - NONCE_LEN];
        System.arraycopy(raw, 0, nonce, 0, NONCE_LEN);
        System.arraycopy(raw, NONCE_LEN, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(prefKey.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }
}
