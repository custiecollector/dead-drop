package org.fieldpacket.core;

import java.util.Locale;

public final class RadioTestPlan {
    public static final int TEST_TONE_HZ = 1000;
    public static final int TEST_TONE_MS = 1000;
    public static final int MAX_LEAD_IN_MS = 3000;
    public static final int MAX_TAIL_MS = 3000;
    public static final int MAX_REPEATS = 5;

    public final RadioPathPreset preset;
    public final int leadInMs;
    public final int tailMs;
    public final int repeats;
    public final int levelPercent;

    private RadioTestPlan(RadioPathPreset preset, int leadInMs, int tailMs, int repeats, int levelPercent) {
        this.preset = preset == null ? RadioPathPreset.at(0) : preset;
        this.leadInMs = clamp(leadInMs, 0, MAX_LEAD_IN_MS);
        this.tailMs = clamp(tailMs, 0, MAX_TAIL_MS);
        this.repeats = clamp(repeats, 1, MAX_REPEATS);
        this.levelPercent = clamp(levelPercent, 0, 100);
    }

    public static RadioTestPlan fromPreset(RadioPathPreset preset) {
        RadioPathPreset p = preset == null ? RadioPathPreset.at(0) : preset;
        return new RadioTestPlan(p, p.defaultLeadInMs, p.defaultTailMs, p.defaultRepeats, p.defaultLevelPercent);
    }

    public static RadioTestPlan create(RadioPathPreset preset, int leadInMs, int tailMs, int repeats, int levelPercent) {
        return new RadioTestPlan(preset, leadInMs, tailMs, repeats, levelPercent);
    }

    public int oneRepeatDurationMs() {
        return leadInMs + TEST_TONE_MS + tailMs;
    }

    public int totalDurationMs() {
        return oneRepeatDurationMs() * repeats;
    }

    public float levelAsFloat() {
        return levelPercent / 100.0f;
    }

    public String summary() {
        return String.format(Locale.US,
                "%s | %d Hz test tone, %d ms lead-in, %d ms tone, %d ms tail, %d repeat(s), level %d%%, total %.1f s",
                preset.label,
                TEST_TONE_HZ,
                leadInMs,
                TEST_TONE_MS,
                tailMs,
                repeats,
                levelPercent,
                totalDurationMs() / 1000.0);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
