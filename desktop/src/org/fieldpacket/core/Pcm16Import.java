package org.fieldpacket.core;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * User-started PCM/WAV import helpers for FieldPacket.
 *
 * <p>The helper accepts caller-provided bytes from a file picker or SDR/audio pipeline and
 * returns normalized mono signed 16-bit PCM for the Bell 202 decoder. It does not read paths, write
 * files, open microphones, or tie the app to a specific SDR brand.</p>
 */
public final class Pcm16Import {
    public static final int TARGET_SAMPLE_RATE_HZ = Bell202AfskDecoder.SAMPLE_RATE_HZ;

    private Pcm16Import() {}

    public static ImportedPcm fromWavOrRaw(byte[] bytes, int rawSampleRateHz) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("PCM import is empty");
        }
        if (looksLikeWav(bytes)) {
            return fromWav(bytes);
        }
        return fromRawLittleEndian(bytes, rawSampleRateHz);
    }

    public static ImportedPcm fromRawLittleEndian(byte[] bytes, int sampleRateHz) {
        if (bytes == null || bytes.length < 2) {
            throw new IllegalArgumentException("raw PCM import needs at least one 16-bit sample");
        }
        int safeRate = sanitizeSampleRate(sampleRateHz);
        int samples = bytes.length / 2;
        short[] mono = new short[samples];
        for (int i = 0; i < samples; i++) {
            mono[i] = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
        }
        boolean resampled = safeRate != TARGET_SAMPLE_RATE_HZ;
        short[] normalized = resampled ? resampleLinear(mono, safeRate, TARGET_SAMPLE_RATE_HZ) : mono;
        return new ImportedPcm("raw signed 16-bit little-endian PCM", safeRate, TARGET_SAMPLE_RATE_HZ,
                1, 16, normalized, resampled);
    }

    public static ImportedPcm fromWav(byte[] bytes) {
        if (!looksLikeWav(bytes)) {
            throw new IllegalArgumentException("not a RIFF/WAVE file");
        }
        int offset = 12;
        int audioFormat = -1;
        int channels = -1;
        int sampleRate = -1;
        int blockAlign = -1;
        int bitsPerSample = -1;
        int dataStart = -1;
        int dataSize = -1;
        while (offset + 8 <= bytes.length) {
            String id = ascii(bytes, offset, 4);
            int size = readLe32(bytes, offset + 4);
            int body = offset + 8;
            if (size < 0 || body + size > bytes.length) break;
            if ("fmt ".equals(id) && size >= 16) {
                audioFormat = readLe16(bytes, body);
                channels = readLe16(bytes, body + 2);
                sampleRate = readLe32(bytes, body + 4);
                blockAlign = readLe16(bytes, body + 12);
                bitsPerSample = readLe16(bytes, body + 14);
            } else if ("data".equals(id)) {
                dataStart = body;
                dataSize = size;
            }
            offset = body + size + (size & 1);
        }
        if (audioFormat != 1) {
            throw new IllegalArgumentException("WAV import supports PCM format only");
        }
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("WAV import supports mono or stereo PCM");
        }
        if (bitsPerSample != 8 && bitsPerSample != 16) {
            throw new IllegalArgumentException("WAV import supports 8-bit or 16-bit PCM");
        }
        if (sampleRate <= 0 || blockAlign <= 0 || dataStart < 0 || dataSize <= 0) {
            throw new IllegalArgumentException("WAV import is missing usable fmt/data chunks");
        }
        int bytesPerSample = bitsPerSample / 8;
        int frameSize = channels * bytesPerSample;
        if (frameSize <= 0) throw new IllegalArgumentException("WAV frame size is invalid");
        int frames = dataSize / frameSize;
        short[] mono = new short[frames];
        for (int frame = 0; frame < frames; frame++) {
            int frameOffset = dataStart + frame * frameSize;
            int sum = 0;
            for (int ch = 0; ch < channels; ch++) {
                int sampleOffset = frameOffset + ch * bytesPerSample;
                sum += readPcmSample(bytes, sampleOffset, bitsPerSample);
            }
            mono[frame] = (short) (sum / channels);
        }
        boolean resampled = sampleRate != TARGET_SAMPLE_RATE_HZ;
        short[] normalized = resampled ? resampleLinear(mono, sampleRate, TARGET_SAMPLE_RATE_HZ) : mono;
        return new ImportedPcm("WAV PCM", sampleRate, TARGET_SAMPLE_RATE_HZ, channels, bitsPerSample,
                normalized, resampled);
    }

    public static short[] resampleLinear(short[] input, int fromRate, int toRate) {
        if (input == null || input.length == 0) return new short[0];
        int safeFrom = sanitizeSampleRate(fromRate);
        int safeTo = sanitizeSampleRate(toRate);
        if (safeFrom == safeTo) return input.clone();
        int outLen = Math.max(1, (int) Math.round(input.length * (safeTo / (double) safeFrom)));
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double src = i * (safeFrom / (double) safeTo);
            int i0 = (int) Math.floor(src);
            int i1 = Math.min(input.length - 1, i0 + 1);
            double frac = src - i0;
            double value = input[Math.min(i0, input.length - 1)] * (1.0 - frac) + input[i1] * frac;
            if (value > Short.MAX_VALUE) value = Short.MAX_VALUE;
            if (value < Short.MIN_VALUE) value = Short.MIN_VALUE;
            out[i] = (short) Math.round(value);
        }
        return out;
    }

    private static boolean looksLikeWav(byte[] bytes) {
        return bytes.length >= 12
                && "RIFF".equals(ascii(bytes, 0, 4))
                && "WAVE".equals(ascii(bytes, 8, 4));
    }

    private static int readPcmSample(byte[] bytes, int offset, int bitsPerSample) {
        if (bitsPerSample == 8) {
            return ((bytes[offset] & 0xFF) - 128) << 8;
        }
        return (short) ((bytes[offset] & 0xFF) | (bytes[offset + 1] << 8));
    }

    private static int sanitizeSampleRate(int sampleRateHz) {
        if (sampleRateHz < 8000 || sampleRateHz > 192000) return TARGET_SAMPLE_RATE_HZ;
        return sampleRateHz;
    }

    private static int readLe16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int readLe32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        if (offset < 0 || offset + length > bytes.length) return "";
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    public static final class ImportedPcm {
        public final String sourceType;
        public final int originalSampleRateHz;
        public final int sampleRateHz;
        public final int channels;
        public final int bitsPerSample;
        public final short[] samples;
        public final boolean resampled;

        private ImportedPcm(String sourceType, int originalSampleRateHz, int sampleRateHz, int channels,
                            int bitsPerSample, short[] samples, boolean resampled) {
            this.sourceType = sourceType;
            this.originalSampleRateHz = originalSampleRateHz;
            this.sampleRateHz = sampleRateHz;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.samples = samples == null ? new short[0] : samples.clone();
            this.resampled = resampled;
        }

        public double seconds() {
            return samples.length / (double) Math.max(1, sampleRateHz);
        }

        public String summary() {
            return String.format(Locale.US,
                    "%s import: %d sample(s), %.2f s, source %d Hz/%d channel(s)/%d-bit, decoder %d Hz%s",
                    sourceType, samples.length, seconds(), originalSampleRateHz, channels, bitsPerSample,
                    sampleRateHz, resampled ? " after in-memory resample" : "");
        }
    }
}
