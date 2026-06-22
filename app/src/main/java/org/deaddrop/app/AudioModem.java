package org.deaddrop.app;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class AudioModem {
    public static final int SAMPLE_RATE = 44_100;
    private static final byte[] SYNC = new byte[]{0x2d, (byte)0xd4, (byte)0xe5, (byte)0xb8};
    private static final int MAX_PAYLOAD = 8192;

    public enum Profile {
        FAST("Fast/Nearby", 48, 1400.0, 2400.0, 18, 1, 918.8),
        NORMAL("Normal", 60, 1200.0, 2200.0, 24, 1, 735.0),
        ROBUST("Robust+FEC", 120, 1000.0, 2000.0, 32, 3, 122.5),
        VOICE_BRIDGE("Voice Bridge/Narrowband", 140, 700.0, 1700.0, 56, 5, 63.0),
        ULTRA("Ultra/Noisy", 160, 900.0, 1900.0, 48, 5, 55.1);

        public final String label;
        public final int samplesPerBit;
        public final double zeroHz;
        public final double oneHz;
        public final int preambleBytes;
        public final int bitRepeat;
        public final double rawBps;

        Profile(String label, int samplesPerBit, double zeroHz, double oneHz, int preambleBytes, int bitRepeat, double rawBps) {
            this.label = label;
            this.samplesPerBit = samplesPerBit;
            this.zeroHz = zeroHz;
            this.oneHz = oneHz;
            this.preambleBytes = preambleBytes;
            this.bitRepeat = bitRepeat;
            this.rawBps = rawBps;
        }

        @Override public String toString() { return label; }
    }

    public enum RadioPreset {
        STANDARD("Standard / direct audio", false, Profile.NORMAL, 1, 0, 0, 1.0, false),
        NO_CABLE("No cable / acoustic handset", false, Profile.ROBUST, 5, 1500, 500, 0.75, true),
        GENERIC_CABLE("Generic audio cable", false, Profile.ROBUST, 3, 1000, 250, 0.50, true),
        HANDHELD_ACCESSORY("Handheld accessory cable", false, Profile.ROBUST, 3, 1000, 250, 0.50, true),
        USB_RADIO_INTERFACE("USB radio interface", false, Profile.NORMAL, 3, 500, 250, 0.75, false),
        SDR_RECEIVE("SDR receive", true, Profile.NORMAL, 1, 0, 0, 1.0, false),
        VOICE_BRIDGE("Voice call / narrowband bridge", false, Profile.VOICE_BRIDGE, 5, 1800, 700, 0.60, true);

        public final String label;
        public final boolean receiveOnly;
        public final Profile defaultProfile;
        public final int defaultRepeats;
        public final int leadInMs;
        public final int tailMs;
        public final double gain;
        public final boolean warmupTone;

        RadioPreset(String label, boolean receiveOnly, Profile defaultProfile, int defaultRepeats,
                    int leadInMs, int tailMs, double gain, boolean warmupTone) {
            this.label = label;
            this.receiveOnly = receiveOnly;
            this.defaultProfile = defaultProfile;
            this.defaultRepeats = defaultRepeats;
            this.leadInMs = leadInMs;
            this.tailMs = tailMs;
            this.gain = gain;
            this.warmupTone = warmupTone;
        }

        @Override public String toString() { return label; }
    }

    public static final class DecodeResult {
        public final byte[] payload;
        public final Profile profile;
        public DecodeResult(byte[] payload, Profile profile) {
            this.payload = payload;
            this.profile = profile;
        }
    }

    public static final class DecodeReport {
        public final boolean signalPresent;
        public final boolean syncSeen;
        public final boolean lengthSeen;
        public final boolean crcFailed;
        public final Profile profile;
        public final int payloadLength;
        public final int sampleCount;

        DecodeReport(boolean signalPresent, boolean syncSeen, boolean lengthSeen, boolean crcFailed,
                     Profile profile, int payloadLength, int sampleCount) {
            this.signalPresent = signalPresent;
            this.syncSeen = syncSeen;
            this.lengthSeen = lengthSeen;
            this.crcFailed = crcFailed;
            this.profile = profile;
            this.payloadLength = payloadLength;
            this.sampleCount = sampleCount;
        }

        public String shortStatus() {
            String suffix = profile == null ? "" : " — " + profile.label;
            if (!signalPresent) return "no signal yet";
            if (!syncSeen) return "signal heard, no DeadDrop sync";
            if (!lengthSeen) return "DeadDrop sync seen, waiting for full frame" + suffix;
            if (crcFailed) return "packet-like signal, checksum failed" + suffix;
            return "packet-like signal seen" + suffix;
        }
    }

    private AudioModem() {}

    public static short[] encode(byte[] payload, Profile profile) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("Invalid payload length.");
        byte[] frame = makeFrame(payload, profile);
        int silence = SAMPLE_RATE / 8;
        int totalBits = frame.length * 8 * profile.bitRepeat;
        short[] samples = new short[silence + totalBits * profile.samplesPerBit + silence];
        double phase = 0.0;
        int idx = silence;
        final double amp = 0.72 * Short.MAX_VALUE;
        for (byte b : frame) {
            for (int bit = 7; bit >= 0; bit--) {
                boolean one = ((b >> bit) & 1) == 1;
                for (int rep = 0; rep < profile.bitRepeat; rep++) {
                    double hz = one ? profile.oneHz : profile.zeroHz;
                    double step = 2.0 * Math.PI * hz / SAMPLE_RATE;
                    for (int i = 0; i < profile.samplesPerBit; i++) {
                        samples[idx++] = (short)Math.round(Math.sin(phase) * amp);
                        phase += step;
                        if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI;
                    }
                }
            }
        }
        return samples;
    }

    public static short[] prepareTransmit(short[] encoded, RadioPreset preset) {
        if (preset == null) preset = RadioPreset.STANDARD;
        return prepareTransmit(encoded, preset.leadInMs, preset.tailMs, preset.gain, preset.warmupTone);
    }

    public static short[] prepareTransmit(short[] encoded, int leadInMs, int tailMs, double gain, boolean warmupTone) {
        if (encoded == null) return new short[0];
        int lead = millisToSamples(leadInMs);
        int tail = millisToSamples(tailMs);
        double scale = Math.max(0.0, Math.min(1.0, gain));
        short[] out = new short[lead + encoded.length + tail];
        if (warmupTone && lead > 0) {
            int fade = Math.min(lead / 4, SAMPLE_RATE / 40);
            for (int i = 0; i < lead; i++) {
                double env = 1.0;
                if (fade > 0) env = Math.min(1.0, Math.min((i + 1) / (double)fade, (lead - i) / (double)fade));
                double tone = Math.sin(2.0 * Math.PI * 880.0 * i / SAMPLE_RATE);
                out[i] = (short)Math.round(tone * Short.MAX_VALUE * Math.min(0.35, scale) * env);
            }
        }
        for (int i = 0; i < encoded.length; i++) {
            out[lead + i] = (short)Math.round(encoded[i] * scale);
        }
        return out;
    }

    private static int millisToSamples(int ms) {
        if (ms <= 0) return 0;
        return (int)Math.min(Integer.MAX_VALUE, Math.round(ms * (SAMPLE_RATE / 1000.0)));
    }

    public static String transmitPresetSummary(RadioPreset preset) {
        if (preset == null || preset == RadioPreset.STANDARD) return "standard direct audio";
        if (preset.receiveOnly) return preset.label + " receive-only";
        return preset.label + " lead-in " + preset.leadInMs + "ms, tail " + preset.tailMs
                + "ms, gain " + Math.round(preset.gain * 100.0) + "%";
    }

    public static DecodeResult tryDecodeAny(short[] samples) {
        for (Profile profile : Profile.values()) {
            byte[] decoded = tryDecode(samples, profile);
            if (decoded != null) return new DecodeResult(decoded, profile);
        }
        return null;
    }

    public static DecodeReport analyzeDecode(short[] samples) {
        int sampleCount = samples == null ? 0 : samples.length;
        if (samples == null || samples.length < SAMPLE_RATE / 4) {
            return new DecodeReport(false, false, false, false, null, -1, sampleCount);
        }
        boolean signal = hasSignal(samples);
        boolean sync = false;
        boolean length = false;
        boolean crc = false;
        Profile bestProfile = null;
        int bestLen = -1;
        for (Profile profile : Profile.values()) {
            int maxOffsets = profile.samplesPerBit;
            int step = profile.samplesPerBit <= 60 ? 3 : 6;
            for (int offset = 0; offset < maxOffsets; offset += step) {
                boolean[] rawBits = demodulate(samples, offset, profile);
                boolean[] bits = profile.bitRepeat == 1 ? rawBits : collapseRepeatedBits(rawBits, profile.bitRepeat);
                FrameStatus st = scanBitsForFrameStatus(bits);
                if (st.syncSeen) { sync = true; bestProfile = profile; }
                if (st.lengthSeen) { length = true; bestProfile = profile; bestLen = st.payloadLength; }
                if (st.crcFailed) { crc = true; bestProfile = profile; bestLen = st.payloadLength; }
                if (st.valid) return new DecodeReport(signal, true, true, false, profile, st.payloadLength, sampleCount);
            }
        }
        return new DecodeReport(signal, sync, length, crc, bestProfile, bestLen, sampleCount);
    }

    public static byte[] tryDecode(short[] samples, Profile profile) {
        if (samples == null || samples.length < SAMPLE_RATE / 2) return null;
        int maxOffsets = profile.samplesPerBit;
        int step = profile.samplesPerBit <= 60 ? 3 : 6;
        for (int offset = 0; offset < maxOffsets; offset += step) {
            boolean[] rawBits = demodulate(samples, offset, profile);
            boolean[] bits = profile.bitRepeat == 1 ? rawBits : collapseRepeatedBits(rawBits, profile.bitRepeat);
            byte[] decoded = scanBitsForFrame(bits);
            if (decoded != null) return decoded;
        }
        return null;
    }

    public static double estimateSeconds(byte[] payload, Profile profile) {
        if (payload == null) return 0.0;
        return encode(payload, profile).length / (double)SAMPLE_RATE;
    }

    private static byte[] makeFrame(byte[] payload, Profile profile) {
        CRC32 crc = new CRC32();
        crc.update(payload);
        long crcValue = crc.getValue();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < profile.preambleBytes; i++) out.write(0x55);
        out.write(SYNC, 0, SYNC.length);
        out.write((payload.length >> 8) & 0xff);
        out.write(payload.length & 0xff);
        out.write(payload, 0, payload.length);
        out.write((int)((crcValue >> 24) & 0xff));
        out.write((int)((crcValue >> 16) & 0xff));
        out.write((int)((crcValue >> 8) & 0xff));
        out.write((int)(crcValue & 0xff));
        return out.toByteArray();
    }

    private static boolean[] demodulate(short[] samples, int offset, Profile profile) {
        int bits = (samples.length - offset) / profile.samplesPerBit;
        if (bits <= 0) return new boolean[0];
        boolean[] out = new boolean[bits];
        for (int i = 0; i < bits; i++) {
            int start = offset + i * profile.samplesPerBit;
            double p0 = goertzel(samples, start, profile.samplesPerBit, profile.zeroHz);
            double p1 = goertzel(samples, start, profile.samplesPerBit, profile.oneHz);
            out[i] = p1 > p0;
        }
        return out;
    }

    private static boolean[] collapseRepeatedBits(boolean[] raw, int repeat) {
        int n = raw.length / repeat;
        boolean[] out = new boolean[n];
        for (int i = 0; i < n; i++) {
            int ones = 0;
            for (int j = 0; j < repeat; j++) if (raw[i * repeat + j]) ones++;
            out[i] = ones > repeat / 2;
        }
        return out;
    }

    private static byte[] scanBitsForFrame(boolean[] bits) {
        if (bits.length < (SYNC.length + 2 + 4) * 8) return null;
        int syncBits = SYNC.length * 8;
        for (int pos = 0; pos <= bits.length - syncBits - 48; pos++) {
            if (!matchesBytes(bits, pos, SYNC)) continue;
            int cursor = pos + syncBits;
            if (cursor + 16 > bits.length) continue;
            int len = (readByte(bits, cursor) << 8) | readByte(bits, cursor + 8);
            cursor += 16;
            if (len <= 0 || len > MAX_PAYLOAD) continue;
            int needed = (len + 4) * 8;
            if (cursor + needed > bits.length) continue;
            byte[] payload = new byte[len];
            for (int i = 0; i < len; i++) payload[i] = (byte)readByte(bits, cursor + i * 8);
            cursor += len * 8;
            long expected = ((long)readByte(bits, cursor) << 24)
                    | ((long)readByte(bits, cursor + 8) << 16)
                    | ((long)readByte(bits, cursor + 16) << 8)
                    | (long)readByte(bits, cursor + 24);
            CRC32 crc = new CRC32();
            crc.update(payload);
            if ((crc.getValue() & 0xffffffffL) == (expected & 0xffffffffL)) return payload;
        }
        return null;
    }

    private static FrameStatus scanBitsForFrameStatus(boolean[] bits) {
        if (bits.length < (SYNC.length + 2) * 8) return FrameStatus.NONE;
        int syncBits = SYNC.length * 8;
        FrameStatus best = FrameStatus.NONE;
        for (int pos = 0; pos <= bits.length - syncBits; pos++) {
            if (!matchesBytes(bits, pos, SYNC)) continue;
            best = FrameStatus.sync();
            int cursor = pos + syncBits;
            if (cursor + 16 > bits.length) continue;
            int len = (readByte(bits, cursor) << 8) | readByte(bits, cursor + 8);
            cursor += 16;
            if (len <= 0 || len > MAX_PAYLOAD) continue;
            best = FrameStatus.length(len);
            int needed = (len + 4) * 8;
            if (cursor + needed > bits.length) continue;
            byte[] payload = new byte[len];
            for (int i = 0; i < len; i++) payload[i] = (byte)readByte(bits, cursor + i * 8);
            cursor += len * 8;
            long expected = ((long)readByte(bits, cursor) << 24)
                    | ((long)readByte(bits, cursor + 8) << 16)
                    | ((long)readByte(bits, cursor + 16) << 8)
                    | (long)readByte(bits, cursor + 24);
            CRC32 crc = new CRC32();
            crc.update(payload);
            if ((crc.getValue() & 0xffffffffL) == (expected & 0xffffffffL)) return FrameStatus.valid(len);
            best = FrameStatus.crc(len);
        }
        return best;
    }

    private static boolean hasSignal(short[] samples) {
        long sumSquares = 0L;
        int peak = 0;
        for (short sample : samples) {
            int v = sample;
            peak = Math.max(peak, Math.abs(v));
            sumSquares += (long)v * (long)v;
        }
        double rms = Math.sqrt(sumSquares / Math.max(1.0, samples.length)) / 32767.0;
        return peak >= 900 || rms >= 0.015;
    }

    private static final class FrameStatus {
        static final FrameStatus NONE = new FrameStatus(false, false, false, false, -1);
        final boolean syncSeen;
        final boolean lengthSeen;
        final boolean crcFailed;
        final boolean valid;
        final int payloadLength;
        private FrameStatus(boolean syncSeen, boolean lengthSeen, boolean crcFailed, boolean valid, int payloadLength) {
            this.syncSeen = syncSeen;
            this.lengthSeen = lengthSeen;
            this.crcFailed = crcFailed;
            this.valid = valid;
            this.payloadLength = payloadLength;
        }
        static FrameStatus sync() { return new FrameStatus(true, false, false, false, -1); }
        static FrameStatus length(int len) { return new FrameStatus(true, true, false, false, len); }
        static FrameStatus crc(int len) { return new FrameStatus(true, true, true, false, len); }
        static FrameStatus valid(int len) { return new FrameStatus(true, true, false, true, len); }
    }

    private static boolean matchesBytes(boolean[] bits, int pos, byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (readByte(bits, pos + i * 8) != (bytes[i] & 0xff)) return false;
        }
        return true;
    }

    private static int readByte(boolean[] bits, int pos) {
        int v = 0;
        for (int i = 0; i < 8; i++) v = (v << 1) | (bits[pos + i] ? 1 : 0);
        return v & 0xff;
    }

    private static double goertzel(short[] samples, int start, int len, double hz) {
        double normalized = hz / SAMPLE_RATE;
        double coeff = 2.0 * Math.cos(2.0 * Math.PI * normalized);
        double q0, q1 = 0.0, q2 = 0.0;
        int end = Math.min(samples.length, start + len);
        for (int i = start; i < end; i++) {
            double sample = samples[i] / 32768.0;
            q0 = coeff * q1 - q2 + sample;
            q2 = q1;
            q1 = q0;
        }
        return q1 * q1 + q2 * q2 - coeff * q1 * q2;
    }

    public static short[] concat(short[] a, short[] b) {
        short[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public static final class HopSlot {
        public final String current;
        public final String next;
        public final int secondsRemaining;
        public final long slotIndex;

        HopSlot(String current, String next, int secondsRemaining, long slotIndex) {
            this.current = current;
            this.next = next;
            this.secondsRemaining = secondsRemaining;
            this.slotIndex = slotIndex;
        }
    }

    public static HopSlot manualHopSlot(byte[] groupKey, byte[] groupId, String[] channels, int intervalSeconds, long nowMillis) {
        if (groupKey == null || groupKey.length == 0) throw new IllegalArgumentException("Group key required for hop assist.");
        if (channels == null || channels.length == 0) throw new IllegalArgumentException("At least one channel is required.");
        int interval = Math.max(5, intervalSeconds);
        long slot = Math.max(0L, nowMillis / 1000L / interval);
        int currentIdx = hopIndex(groupKey, groupId, slot, channels.length);
        int nextIdx = hopIndex(groupKey, groupId, slot + 1, channels.length);
        int remaining = interval - (int)((nowMillis / 1000L) % interval);
        return new HopSlot(channels[currentIdx], channels[nextIdx], remaining, slot);
    }

    public static String[] defaultManualHopChannels() {
        String[] out = new String[16];
        for (int i = 0; i < out.length; i++) out[i] = "Channel " + (i + 1);
        return out;
    }

    private static int hopIndex(byte[] groupKey, byte[] groupId, long slot, int channelCount) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(groupKey, "HmacSHA256"));
            mac.update("DeadDrop manual hop v1".getBytes("UTF-8"));
            if (groupId != null) mac.update(groupId);
            mac.update(ByteBuffer.allocate(8).putLong(slot).array());
            byte[] digest = mac.doFinal();
            int value = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16) | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
            return (value & 0x7fffffff) % channelCount;
        } catch (Exception e) {
            throw new IllegalStateException("Hop schedule failed: " + e.getMessage(), e);
        }
    }
}
