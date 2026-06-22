package org.fieldpacket.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal KISS/TNC framing helpers for FieldPacket.
 *
 * <p>This class intentionally does not open serial, USB, Bluetooth, or network devices. It only
 * converts caller-supplied in-memory bytes to/from KISS frames without opening hardware-specific
 * control paths.</p>
 */
public final class KissFrame {
    public static final int FEND = 0xC0;
    public static final int FESC = 0xDB;
    public static final int TFEND = 0xDC;
    public static final int TFESC = 0xDD;
    public static final int COMMAND_DATA = 0x00;
    public static final int MAX_HEX_TEXT_CHARS = 65536;
    public static final int MAX_HEX_DIGITS = 32768;

    public final int port;
    public final int command;
    public final byte[] payload;

    public KissFrame(int port, int command, byte[] payload) {
        this.port = clamp(port, 0, 15);
        this.command = command & 0x0F;
        this.payload = payload == null ? new byte[0] : payload.clone();
    }

    public static KissFrame fromAx25(Ax25Frame frame, int port) {
        if (frame == null) {
            throw new IllegalArgumentException("AX.25 frame is required");
        }
        // KISS data frames carry the AX.25 frame body without HDLC flags or FCS; the TNC/radio path
        // handles the over-the-air FCS. The local AX.25 parser can decode this no-FCS payload.
        return new KissFrame(port, COMMAND_DATA, frame.frameWithoutFcs);
    }

    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 4);
        out.write(FEND);
        out.write(((port & 0x0F) << 4) | (command & 0x0F));
        writeEscaped(out, payload);
        out.write(FEND);
        return out.toByteArray();
    }

    public boolean isDataFrame() {
        return command == COMMAND_DATA;
    }

    public Ax25Frame.ParsedUiFrame parseAx25UiFrame() {
        if (!isDataFrame()) {
            throw new IllegalArgumentException(String.format(Locale.US, "KISS command %X is not a data frame", command));
        }
        return Ax25Frame.parseUiFrameWithoutFcs(payload);
    }

    public String summary() {
        String base = String.format(Locale.US, "KISS frame: port %d, command 0x%X, payload %d byte(s)",
                port, command, payload.length);
        if (!isDataFrame()) return base;
        try {
            return base + "\n" + parseAx25UiFrame().summary();
        } catch (IllegalArgumentException ex) {
            return base + "\nAX.25 parse: " + ex.getMessage();
        }
    }

    public static List<KissFrame> decodeStream(byte[] stream) {
        List<KissFrame> frames = new ArrayList<>();
        if (stream == null || stream.length == 0) return frames;
        ByteArrayOutputStream current = null;
        boolean escaped = false;
        for (byte b : stream) {
            int value = b & 0xFF;
            if (value == FEND) {
                if (current != null && current.size() > 0) {
                    frames.add(fromUnescapedFrameBytes(current.toByteArray()));
                }
                current = new ByteArrayOutputStream();
                escaped = false;
                continue;
            }
            if (current == null) {
                // Ignore noise before the first frame delimiter.
                continue;
            }
            if (escaped) {
                if (value == TFEND) current.write(FEND);
                else if (value == TFESC) current.write(FESC);
                else current.write(value);
                escaped = false;
            } else if (value == FESC) {
                escaped = true;
            } else {
                current.write(value);
            }
        }
        if (current != null && current.size() > 0) {
            frames.add(fromUnescapedFrameBytes(current.toByteArray()));
        }
        return frames;
    }

    public static byte[] parseHex(String text) {
        if (text == null) return new byte[0];
        if (text.length() > MAX_HEX_TEXT_CHARS) {
            throw new IllegalArgumentException("hex input is too large; limit is " + MAX_HEX_TEXT_CHARS + " characters");
        }
        String cleaned = text.replace("0x", "")
                .replace("0X", "")
                .replaceAll("[^0-9A-Fa-f]", "");
        if (cleaned.length() > MAX_HEX_DIGITS) {
            throw new IllegalArgumentException("hex input is too large; limit is " + MAX_HEX_DIGITS + " hex digits");
        }
        if ((cleaned.length() & 1) != 0) {
            throw new IllegalArgumentException("hex input has an odd number of digits");
        }
        byte[] out = new byte[cleaned.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(cleaned.charAt(i * 2), 16);
            int lo = Character.digit(cleaned.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("invalid hex byte");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    public static String toHex(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private static KissFrame fromUnescapedFrameBytes(byte[] frameBytes) {
        if (frameBytes.length == 0) {
            throw new IllegalArgumentException("empty KISS frame");
        }
        int commandByte = frameBytes[0] & 0xFF;
        int port = (commandByte >> 4) & 0x0F;
        int command = commandByte & 0x0F;
        byte[] payload = new byte[Math.max(0, frameBytes.length - 1)];
        if (payload.length > 0) System.arraycopy(frameBytes, 1, payload, 0, payload.length);
        return new KissFrame(port, command, payload);
    }

    private static void writeEscaped(ByteArrayOutputStream out, byte[] bytes) {
        for (byte b : bytes) {
            int value = b & 0xFF;
            if (value == FEND) {
                out.write(FESC);
                out.write(TFEND);
            } else if (value == FESC) {
                out.write(FESC);
                out.write(TFESC);
            } else {
                out.write(value);
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
