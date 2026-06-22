package org.deaddrop.app;

import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class DeadDropLog {
    public static final String LOG_KEY = "log_v1";

    public static final class Entry {
        public final long expiresAt;
        public final String dedupeKey;
        public final String groupLabel;
        public final String senderLabel;
        public final String text;
        public final String mode;
        public final long receivedAt;
        public final String direction;

        Entry(long expiresAt, String dedupeKey, String groupLabel, String senderLabel, String text, String mode, long receivedAt, String direction) {
            this.expiresAt = expiresAt;
            this.dedupeKey = dedupeKey;
            this.groupLabel = groupLabel;
            this.senderLabel = senderLabel;
            this.text = text;
            this.mode = mode;
            this.receivedAt = receivedAt;
            this.direction = direction;
        }

        public boolean isSent() {
            return "out".equals(direction);
        }
    }

    private DeadDropLog() {}

    public static synchronized boolean append(SharedPreferences prefs, DeadDropCrypto.ReceivedMessage msg) throws Exception {
        List<Entry> entries = readAndPrune(prefs);
        for (Entry e : entries) {
            if (e.dedupeKey.equals(msg.dedupeKey())) return false;
        }
        appendLine(prefs, msg.expiresAtMillis, msg.dedupeKey(), msg.groupLabel(), msg.displaySender(), msg.text, msg.mode, System.currentTimeMillis(), "in");
        return true;
    }

    public static synchronized void appendSent(SharedPreferences prefs, String groupLabel, String text, String mode, int ttlHours) throws Exception {
        long now = System.currentTimeMillis();
        long expires = now + Math.max(1, ttlHours) * 60L * 60L * 1000L;
        String key = "sent:" + shortHash(now + "|" + groupLabel + "|" + mode + "|" + text);
        appendLine(prefs, expires, key, groupLabel, "You", text, mode, now, "out");
    }

    public static synchronized List<Entry> readAndPrune(SharedPreferences prefs) throws Exception {
        String log = SecurePrefs.getString(prefs, LOG_KEY, "");
        long now = System.currentTimeMillis();
        StringBuilder kept = new StringBuilder();
        List<Entry> entries = new ArrayList<>();
        for (String line : log.split("\\n")) {
            if (line.trim().isEmpty()) continue;
            Entry e = parse(line);
            if (e == null || e.expiresAt <= now) continue;
            kept.append(line).append('\n');
            entries.add(e);
        }
        SecurePrefs.putString(prefs, LOG_KEY, kept.toString());
        return entries;
    }

    public static synchronized void clear(SharedPreferences prefs) {
        SecurePrefs.remove(prefs, LOG_KEY);
    }

    private static void appendLine(SharedPreferences prefs, long expiresAt, String dedupeKey, String groupLabel,
                                   String senderLabel, String text, String mode, long receivedAt, String direction) throws Exception {
        String existing = SecurePrefs.getString(prefs, LOG_KEY, "");
        String line = expiresAt + "|" + dedupeKey
                + "|" + DeadDropCrypto.b64(groupLabel.getBytes(StandardCharsets.UTF_8))
                + "|" + DeadDropCrypto.b64(senderLabel.getBytes(StandardCharsets.UTF_8))
                + "|" + DeadDropCrypto.b64(text.getBytes(StandardCharsets.UTF_8))
                + "|" + DeadDropCrypto.b64(mode.getBytes(StandardCharsets.UTF_8))
                + "|" + receivedAt
                + "|" + direction;
        SecurePrefs.putString(prefs, LOG_KEY, existing + line + "\n");
    }

    private static Entry parse(String line) {
        try {
            String[] p = line.split("\\|", -1);
            if (p.length == 4) {
                // Legacy log format: expires|dedupe|group|text
                return new Entry(Long.parseLong(p[0]), p[1], b64s(p[2]), "Anonymous", b64s(p[3]), "encrypted-anonymous", 0, "in");
            }
            if (p.length >= 6) {
                long received = p.length >= 7 ? parseLong(p[6], 0) : 0;
                String direction = p.length >= 8 && "out".equals(p[7]) ? "out" : "in";
                return new Entry(Long.parseLong(p[0]), p[1], b64s(p[2]), b64s(p[3]), b64s(p[4]), b64s(p[5]), received, direction);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String b64s(String s) {
        return new String(DeadDropCrypto.b64d(s), StandardCharsets.UTF_8);
    }

    private static long parseLong(String s, long d) {
        try { return Long.parseLong(s); } catch (Exception e) { return d; }
    }

    private static String shortHash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 8; i++) sb.append(String.format("%02x", digest[i] & 0xff));
        return sb.toString();
    }
}
