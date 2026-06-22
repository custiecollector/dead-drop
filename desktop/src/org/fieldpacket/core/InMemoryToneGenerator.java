package org.fieldpacket.core;

public final class InMemoryToneGenerator {
    public static final int SAMPLE_RATE_HZ = 44100;
    private static final double TWO_PI = Math.PI * 2.0;
    private static final double HEADROOM = 0.85;

    private InMemoryToneGenerator() {}

    public static short[] generateTestTone(RadioTestPlan plan) {
        RadioTestPlan safePlan = plan == null ? RadioTestPlan.fromPreset(RadioPathPreset.at(0)) : plan;
        int leadSamples = samplesForMillis(safePlan.leadInMs);
        int toneSamples = samplesForMillis(RadioTestPlan.TEST_TONE_MS);
        int tailSamples = samplesForMillis(safePlan.tailMs);
        int perRepeat = leadSamples + toneSamples + tailSamples;
        short[] pcm = new short[perRepeat * safePlan.repeats];
        int offset = 0;
        for (int repeat = 0; repeat < safePlan.repeats; repeat++) {
            offset += leadSamples;
            offset = appendTone(pcm, offset, toneSamples, RadioTestPlan.TEST_TONE_HZ, safePlan.levelAsFloat());
            offset += tailSamples;
        }
        return pcm;
    }

    public static int samplesForMillis(int millis) {
        if (millis <= 0) return 0;
        return (int) Math.round((SAMPLE_RATE_HZ * millis) / 1000.0);
    }

    public static int maxAbsSample(short[] pcm) {
        if (pcm == null || pcm.length == 0) return 0;
        int max = 0;
        for (short sample : pcm) {
            int value = Math.abs((int) sample);
            if (value > max) max = value;
        }
        return max;
    }

    private static int appendTone(short[] pcm, int offset, int sampleCount, int frequencyHz, float level) {
        double amplitude = Short.MAX_VALUE * HEADROOM * Math.max(0.0f, Math.min(1.0f, level));
        for (int i = 0; i < sampleCount; i++) {
            double angle = TWO_PI * frequencyHz * i / SAMPLE_RATE_HZ;
            pcm[offset + i] = (short) Math.round(Math.sin(angle) * amplitude);
        }
        return offset + sampleCount;
    }
}
