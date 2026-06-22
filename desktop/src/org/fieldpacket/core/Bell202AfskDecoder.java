package org.fieldpacket.core;

import java.util.Locale;

/**
 * Bell 202 / AX.25 APRS receive path for FieldPacket.
 *
 * <p>The decoder operates on caller-owned in-memory PCM samples only. It does not read or write
 * audio files, does not open devices, and does not retain audio after returning a report.</p>
 */
public final class Bell202AfskDecoder {
    public static final int SAMPLE_RATE_HZ = Bell202AfskModulator.SAMPLE_RATE_HZ;
    public static final int BAUD = Bell202AfskModulator.BAUD;
    public static final int MARK_HZ = Bell202AfskModulator.MARK_HZ;
    public static final int SPACE_HZ = Bell202AfskModulator.SPACE_HZ;

    private static final double TWO_PI = Math.PI * 2.0;
    private static final double SAMPLES_PER_BIT = SAMPLE_RATE_HZ / (double) BAUD;
    private static final int MAX_ANALYSIS_SAMPLES = SAMPLE_RATE_HZ * 12;
    private static final int MIN_SIGNAL_PEAK = 500;
    private static final int FLAG_BITS = 8;
    private static final int MIN_FRAME_BITS = 18 * 8;

    private Bell202AfskDecoder() {}

    public static DecodeResult decode(short[] pcm) {
        return analyze(pcm).result;
    }

    public static DecodeReport analyze(short[] pcm) {
        if (pcm == null) return DecodeReport.noInput();
        return analyze(pcm, pcm.length);
    }

    public static DecodeReport analyze(short[] pcm, int length) {
        if (pcm == null || length <= 0) return DecodeReport.noInput();
        int safeLength = Math.min(length, pcm.length);
        SignalMetrics metrics = analyzeSignal(pcm, safeLength);
        if (!metrics.signalPresent) {
            return new DecodeReport(metrics, false, false, false, false, false,
                    0, 0, -1, null, "no signal above receive threshold");
        }

        int start = Math.max(0, safeLength - MAX_ANALYSIS_SAMPLES);
        int analysisLength = safeLength - start;
        Candidate best = null;
        int phaseStep = Math.max(1, (int) Math.round(SAMPLES_PER_BIT / 9.0));
        for (int phase = 0; phase < (int) Math.ceil(SAMPLES_PER_BIT); phase += phaseStep) {
            Candidate candidate = decodeForPhase(pcm, start, analysisLength, phase);
            if (candidate.result != null) {
                return new DecodeReport(metrics, true, true, false, false, true,
                        candidate.flagsSeen, candidate.frameCandidates, candidate.phase,
                        candidate.result, "decoded packet accepted");
            }
            if (best == null || candidate.score() > best.score()) {
                best = candidate;
            }
        }

        if (best == null || best.flagsSeen == 0) {
            return new DecodeReport(metrics, true, false, false, false, false,
                    0, 0, -1, null, "signal present, no HDLC/APRS sync flag found");
        }
        boolean waiting = best.frameCandidates == 0;
        String detail = best.fcsFailures > 0
                ? "HDLC sync seen, frame rejected by AX.25 FCS/checksum"
                : "HDLC sync seen, waiting for a complete AX.25 UI frame";
        return new DecodeReport(metrics, true, true, waiting, best.fcsFailures > 0, false,
                best.flagsSeen, best.frameCandidates, best.phase, null, detail);
    }

    public static SignalMetrics analyzeSignal(short[] pcm, int length) {
        if (pcm == null || length <= 0) return new SignalMetrics(0, 0, 0.0, 0, false);
        int n = Math.min(length, pcm.length);
        int peak = 0;
        int clipped = 0;
        double sumSq = 0.0;
        for (int i = 0; i < n; i++) {
            int sample = pcm[i];
            int abs = Math.abs(sample);
            if (abs > peak) peak = abs;
            if (abs >= 32000) clipped++;
            sumSq += sample * (double) sample;
        }
        double rms = Math.sqrt(sumSq / Math.max(1, n));
        return new SignalMetrics(n, peak, rms, clipped, peak >= MIN_SIGNAL_PEAK);
    }

    private static Candidate decodeForPhase(short[] pcm, int start, int length, int phase) {
        Candidate candidate = new Candidate(phase);
        if (length <= phase + SAMPLES_PER_BIT * 16) return candidate;
        int bitCount = (int) Math.floor((length - phase) * BAUD / (double) SAMPLE_RATE_HZ);
        if (bitCount < FLAG_BITS * 2) return candidate;

        boolean[] tones = new boolean[bitCount];
        for (int bit = 0; bit < bitCount; bit++) {
            int s0 = start + phase + (int) Math.round(bit * SAMPLES_PER_BIT);
            int s1 = start + phase + (int) Math.round((bit + 1) * SAMPLES_PER_BIT);
            if (s1 <= s0) s1 = s0 + 1;
            if (s1 > start + length) s1 = start + length;
            double mark = toneEnergy(pcm, s0, s1, MARK_HZ);
            double space = toneEnergy(pcm, s0, s1, SPACE_HZ);
            tones[bit] = mark >= space;
        }

        int decodedLen = bitCount - 1;
        int[] bits = new int[decodedLen];
        for (int i = 1; i < bitCount; i++) {
            bits[i - 1] = tones[i] == tones[i - 1] ? 1 : 0;
        }

        int[] flags = findFlags(bits, decodedLen);
        candidate.flagsSeen = flags.length;
        for (int i = 0; i + 1 < flags.length; i++) {
            int frameStart = flags[i] + FLAG_BITS;
            int frameEnd = flags[i + 1];
            if (frameEnd - frameStart < MIN_FRAME_BITS) continue;
            candidate.frameCandidates++;
            byte[] frameBytes = unstuffAndPack(bits, frameStart, frameEnd);
            if (frameBytes == null || frameBytes.length < 18) continue;
            try {
                Ax25Frame.ParsedUiFrame parsed = Ax25Frame.parseUiFrame(frameBytes);
                candidate.result = new DecodeResult(parsed, phase, candidate.flagsSeen,
                        candidate.frameCandidates, frameBytes.length, decodedLen);
                return candidate;
            } catch (IllegalArgumentException ex) {
                String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.US);
                if (msg.contains("fcs")) candidate.fcsFailures++;
                else candidate.parseFailures++;
            }
        }
        return candidate;
    }

    private static double toneEnergy(short[] pcm, int start, int end, int frequency) {
        int n = Math.max(1, end - start);
        double omega = TWO_PI * frequency / SAMPLE_RATE_HZ;
        double coeff = 2.0 * Math.cos(omega);
        double q0;
        double q1 = 0.0;
        double q2 = 0.0;
        for (int i = start; i < end; i++) {
            q0 = coeff * q1 - q2 + pcm[i];
            q2 = q1;
            q1 = q0;
        }
        return (q1 * q1 + q2 * q2 - coeff * q1 * q2) / n;
    }

    private static int[] findFlags(int[] bits, int bitLen) {
        int[] flags = new int[Math.max(4, bitLen / 64)];
        int count = 0;
        for (int i = 0; i <= bitLen - FLAG_BITS; i++) {
            if (bits[i] == 0
                    && bits[i + 1] == 1
                    && bits[i + 2] == 1
                    && bits[i + 3] == 1
                    && bits[i + 4] == 1
                    && bits[i + 5] == 1
                    && bits[i + 6] == 1
                    && bits[i + 7] == 0) {
                if (count >= flags.length) {
                    int[] bigger = new int[flags.length * 2];
                    System.arraycopy(flags, 0, bigger, 0, flags.length);
                    flags = bigger;
                }
                flags[count++] = i;
                i += FLAG_BITS - 1;
            }
        }
        int[] out = new int[count];
        System.arraycopy(flags, 0, out, 0, count);
        return out;
    }

    private static byte[] unstuffAndPack(int[] bits, int start, int end) {
        int[] unstuffed = new int[Math.max(0, end - start)];
        int count = 0;
        int ones = 0;
        for (int i = start; i < end; i++) {
            int bit = bits[i];
            if (bit == 1) {
                ones++;
                if (ones > 5) return null;
                unstuffed[count++] = 1;
            } else {
                if (ones == 5) {
                    ones = 0;
                    continue;
                }
                unstuffed[count++] = 0;
                ones = 0;
            }
        }
        if (count < MIN_FRAME_BITS || (count % 8) != 0) return null;
        byte[] out = new byte[count / 8];
        for (int byteIndex = 0; byteIndex < out.length; byteIndex++) {
            int value = 0;
            for (int bit = 0; bit < 8; bit++) {
                value |= (unstuffed[byteIndex * 8 + bit] & 0x01) << bit;
            }
            out[byteIndex] = (byte) value;
        }
        return out;
    }

    public static final class SignalMetrics {
        public final int samples;
        public final int peak;
        public final double rms;
        public final int clippedSamples;
        public final boolean signalPresent;

        private SignalMetrics(int samples, int peak, double rms, int clippedSamples, boolean signalPresent) {
            this.samples = samples;
            this.peak = peak;
            this.rms = rms;
            this.clippedSamples = clippedSamples;
            this.signalPresent = signalPresent;
        }

        public int peakPercent() {
            return Math.min(100, (int) Math.round(peak * 100.0 / 32768.0));
        }

        public int rmsPercent() {
            return Math.min(100, (int) Math.round(rms * 100.0 / 32768.0));
        }

        public boolean clipping() {
            return clippedSamples > 0;
        }

        public String shortStatus() {
            return String.format(Locale.US, "peak %d%% / rms %d%% / clipping %s",
                    peakPercent(), rmsPercent(), clipping() ? "yes" : "no");
        }
    }

    public static final class DecodeReport {
        public final SignalMetrics metrics;
        public final boolean signalPresent;
        public final boolean syncSeen;
        public final boolean waitingFrame;
        public final boolean fcsFailed;
        public final boolean decodedAccepted;
        public final int flagsSeen;
        public final int frameCandidates;
        public final int bestPhase;
        public final DecodeResult result;
        public final String detail;

        private DecodeReport(SignalMetrics metrics, boolean signalPresent, boolean syncSeen,
                             boolean waitingFrame, boolean fcsFailed, boolean decodedAccepted,
                             int flagsSeen, int frameCandidates, int bestPhase,
                             DecodeResult result, String detail) {
            this.metrics = metrics;
            this.signalPresent = signalPresent;
            this.syncSeen = syncSeen;
            this.waitingFrame = waitingFrame;
            this.fcsFailed = fcsFailed;
            this.decodedAccepted = decodedAccepted;
            this.flagsSeen = flagsSeen;
            this.frameCandidates = frameCandidates;
            this.bestPhase = bestPhase;
            this.result = result;
            this.detail = detail == null ? "" : detail;
        }

        private static DecodeReport noInput() {
            SignalMetrics metrics = new SignalMetrics(0, 0, 0.0, 0, false);
            return new DecodeReport(metrics, false, false, false, false, false,
                    0, 0, -1, null, "no audio samples available");
        }

        public String shortStatus() {
            if (decodedAccepted) return "decoded packet accepted";
            if (!signalPresent) return "signal absent / no sync";
            if (!syncSeen) return "signal present / no sync";
            if (fcsFailed) return "sync seen / FCS-checksum fail";
            if (waitingFrame) return "sync seen / waiting frame";
            return "signal present / waiting frame";
        }

        public String diagnosticLine() {
            return metrics.shortStatus() + " — " + shortStatus();
        }
    }

    public static final class DecodeResult {
        public final Ax25Frame.ParsedUiFrame frame;
        public final int phase;
        public final int flagsSeen;
        public final int frameCandidates;
        public final int frameBytes;
        public final int decodedBits;

        private DecodeResult(Ax25Frame.ParsedUiFrame frame, int phase, int flagsSeen,
                             int frameCandidates, int frameBytes, int decodedBits) {
            this.frame = frame;
            this.phase = phase;
            this.flagsSeen = flagsSeen;
            this.frameCandidates = frameCandidates;
            this.frameBytes = frameBytes;
            this.decodedBits = decodedBits;
        }

        public String summary() {
            return frame.summary()
                    + String.format(Locale.US,
                    "\nTNC2: %s\nRX: %d byte frame, phase %d sample(s), %d flag(s), %d candidate frame(s)",
                    frame.tnc2Line(), frameBytes, phase, flagsSeen, frameCandidates);
        }
    }

    private static final class Candidate {
        final int phase;
        int flagsSeen;
        int frameCandidates;
        int fcsFailures;
        int parseFailures;
        DecodeResult result;

        Candidate(int phase) {
            this.phase = phase;
        }

        int score() {
            return flagsSeen * 1000 + frameCandidates * 100 + fcsFailures * 10 - parseFailures;
        }
    }
}
