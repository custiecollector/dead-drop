package org.deaddrop.app;

import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class DeadDropReplayCache {
    private static final String KEY = "replay_cache_v1";
    private static final int MAX_ENTRIES = 512;

    public static final class Result {
        public final boolean duplicate;
        public final int keptEntries;
        private Result(boolean duplicate, int keptEntries) {
            this.duplicate = duplicate;
            this.keptEntries = keptEntries;
        }
    }

    private static final class Entry {
        final long expiresAt;
        final String dedupeKey;
        final String groupLabel;
        Entry(long expiresAt, String dedupeKey, String groupLabel) {
            this.expiresAt = expiresAt;
            this.dedupeKey = dedupeKey;
            this.groupLabel = groupLabel;
        }
    }

    private DeadDropReplayCache() {}

    public static synchronized Result remember(SharedPreferences prefs, DeadDropCrypto.ReceivedMessage msg) throws Exception {
        long now = System.currentTimeMillis();
        List<Entry> entries = readKept(prefs, now);
        for (Entry e : entries) {
            if (e.dedupeKey.equals(msg.dedupeKey())) {
                write(prefs, entries);
                return new Result(true, entries.size());
            }
        }
        entries.add(new Entry(Math.max(msg.expiresAtMillis, now + 60_000L), msg.dedupeKey(), msg.groupLabel()));
        trim(entries);
        write(prefs, entries);
        return new Result(false, entries.size());
    }

    public static synchronized int prune(SharedPreferences prefs) throws Exception {
        List<Entry> entries = readKept(prefs, System.currentTimeMillis());
        write(prefs, entries);
        return entries.size();
    }

    public static synchronized void clear(SharedPreferences prefs) {
        SecurePrefs.remove(prefs, KEY);
    }

    private static List<Entry> readKept(SharedPreferences prefs, long now) {
        String raw = SecurePrefs.getString(prefs, KEY, "");
        List<Entry> entries = new ArrayList<>();
        for (String line : raw.split("\\n")) {
            if (line.trim().isEmpty()) continue;
            try {
                String[] p = line.split("\\|", -1);
                if (p.length < 3) continue;
                long expires = Long.parseLong(p[0]);
                if (expires <= now) continue;
                entries.add(new Entry(expires, b64s(p[1]), b64s(p[2])));
            } catch (Exception ignored) {}
        }
        trim(entries);
        return entries;
    }

    private static void trim(List<Entry> entries) {
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
    }

    private static void write(SharedPreferences prefs, List<Entry> entries) throws Exception {
        StringBuilder out = new StringBuilder();
        for (Entry e : entries) {
            out.append(e.expiresAt).append('|')
                    .append(DeadDropCrypto.b64(e.dedupeKey.getBytes(StandardCharsets.UTF_8))).append('|')
                    .append(DeadDropCrypto.b64(e.groupLabel.getBytes(StandardCharsets.UTF_8))).append('\n');
        }
        SecurePrefs.putString(prefs, KEY, out.toString());
    }

    private static String b64s(String s) {
        return new String(DeadDropCrypto.b64d(s), StandardCharsets.UTF_8);
    }
}
