package org.fieldpacket.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Bell202AfskModulator {
    public static final int SAMPLE_RATE_HZ = InMemoryToneGenerator.SAMPLE_RATE_HZ;
    public static final int BAUD = 1200;
    public static final int MARK_HZ = 1200;
    public static final int SPACE_HZ = 2200;
    public static final int HDLC_FLAG = 0x7E;
    private static final double TWO_PI = Math.PI * 2.0;
    private static final double HEADROOM = 0.85;

    private Bell202AfskModulator() {}

    public static AfskTransmission generate(AprsTransmitPacket packet, RadioTestPlan plan) {
        RadioTestPlan safePlan = plan == null ? RadioTestPlan.fromPreset(RadioPathPreset.at(0)) : plan;
        Ax25Frame frame = Ax25Frame.fromAprs(packet);
        int leadFlags = flagCountForMillis(safePlan.leadInMs, true);
        int tailFlags = flagCountForMillis(safePlan.tailMs, true);
        List<Integer> oneFrameBits = frameBits(frame, leadFlags, tailFlags);
        List<Integer> allBits = new ArrayList<>(oneFrameBits.size() * safePlan.repeats);
        for (int repeat = 0; repeat < safePlan.repeats; repeat++) {
            allBits.addAll(oneFrameBits);
        }
        short[] pcm = renderNrziBell202(allBits, safePlan.levelAsFloat());
        return new AfskTransmission(frame, pcm, allBits.size(), oneFrameBits.size(), leadFlags, tailFlags, safePlan.repeats, safePlan.levelPercent);
    }

    public static int flagCountForMillis(int millis, boolean atLeastOne) {
        if (millis <= 0) return atLeastOne ? 1 : 0;
        int flags = (int) Math.round((millis / 1000.0) * BAUD / 8.0);
        if (flags < 1 && atLeastOne) return 1;
        return flags;
    }

    public static List<Integer> frameBits(Ax25Frame frame, int leadFlags, int tailFlags) {
        List<Integer> bits = new ArrayList<>();
        int safeLeadFlags = Math.max(1, leadFlags);
        int safeTailFlags = Math.max(1, tailFlags);
        for (int i = 0; i < safeLeadFlags; i++) appendByteLsb(bits, HDLC_FLAG);
        appendStuffedBytes(bits, frame.frameWithFcs);
        for (int i = 0; i < safeTailFlags; i++) appendByteLsb(bits, HDLC_FLAG);
        return bits;
    }

    public static short[] renderNrziBell202(List<Integer> bits, float level) {
        if (bits == null || bits.isEmpty()) return new short[0];
        int sampleCount = (int) Math.round(bits.size() * SAMPLE_RATE_HZ / (double) BAUD);
        short[] pcm = new short[sampleCount];
        boolean mark = true;
        int previousBitIndex = -1;
        int currentFrequency = MARK_HZ;
        double phase = 0.0;
        double amplitude = Short.MAX_VALUE * HEADROOM * Math.max(0.0f, Math.min(1.0f, level));
        for (int sample = 0; sample < sampleCount; sample++) {
            int bitIndex = (int) Math.floor(sample * BAUD / (double) SAMPLE_RATE_HZ);
            if (bitIndex >= bits.size()) bitIndex = bits.size() - 1;
            if (bitIndex != previousBitIndex) {
                int bit = bits.get(bitIndex);
                if (bit == 0) {
                    mark = !mark;
                }
                currentFrequency = mark ? MARK_HZ : SPACE_HZ;
                previousBitIndex = bitIndex;
            }
            pcm[sample] = (short) Math.round(Math.sin(phase) * amplitude);
            phase += TWO_PI * currentFrequency / SAMPLE_RATE_HZ;
            if (phase >= TWO_PI) phase -= TWO_PI;
        }
        return pcm;
    }

    private static void appendStuffedBytes(List<Integer> bits, byte[] data) {
        int consecutiveOnes = 0;
        for (byte b : data) {
            int value = b & 0xFF;
            for (int i = 0; i < 8; i++) {
                int bit = (value >> i) & 0x01;
                bits.add(bit);
                if (bit == 1) {
                    consecutiveOnes++;
                    if (consecutiveOnes == 5) {
                        bits.add(0);
                        consecutiveOnes = 0;
                    }
                } else {
                    consecutiveOnes = 0;
                }
            }
        }
    }

    private static void appendByteLsb(List<Integer> bits, int value) {
        for (int i = 0; i < 8; i++) {
            bits.add((value >> i) & 0x01);
        }
    }

    public static final class AfskTransmission {
        public final Ax25Frame frame;
        public final short[] pcm;
        public final int totalBits;
        public final int oneRepeatBits;
        public final int leadFlags;
        public final int tailFlags;
        public final int repeats;
        public final int levelPercent;

        private AfskTransmission(Ax25Frame frame, short[] pcm, int totalBits, int oneRepeatBits,
                                 int leadFlags, int tailFlags, int repeats, int levelPercent) {
            this.frame = frame;
            this.pcm = pcm;
            this.totalBits = totalBits;
            this.oneRepeatBits = oneRepeatBits;
            this.leadFlags = leadFlags;
            this.tailFlags = tailFlags;
            this.repeats = repeats;
            this.levelPercent = levelPercent;
        }

        public double durationSeconds() {
            return pcm.length / (double) SAMPLE_RATE_HZ;
        }

        public String summary() {
            return String.format(Locale.US,
                    "Bell 202 AFSK TX: %d baud, mark %d Hz, space %d Hz, %d lead flag(s), %d tail flag(s), %d repeat(s), level %d%%, %.2f s, %d sample(s) @ %d Hz, peak %d",
                    BAUD,
                    MARK_HZ,
                    SPACE_HZ,
                    leadFlags,
                    tailFlags,
                    repeats,
                    levelPercent,
                    durationSeconds(),
                    pcm.length,
                    SAMPLE_RATE_HZ,
                    InMemoryToneGenerator.maxAbsSample(pcm));
        }
    }
}
