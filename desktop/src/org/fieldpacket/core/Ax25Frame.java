package org.fieldpacket.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class Ax25Frame {
    public static final int CONTROL_UI = 0x03;
    public static final int PID_NO_LAYER3 = 0xF0;

    public final AprsTransmitPacket packet;
    public final byte[] frameWithoutFcs;
    public final byte[] frameWithFcs;
    public final int fcs;

    private Ax25Frame(AprsTransmitPacket packet, byte[] frameWithoutFcs, byte[] frameWithFcs, int fcs) {
        this.packet = packet;
        this.frameWithoutFcs = frameWithoutFcs.clone();
        this.frameWithFcs = frameWithFcs.clone();
        this.fcs = fcs & 0xFFFF;
    }

    public static Ax25Frame fromAprs(AprsTransmitPacket packet) {
        AprsTransmitPacket safe = packet == null
                ? AprsTransmitPacket.create(AprsTransmitPacket.DEFAULT_SOURCE, AprsTransmitPacket.DEFAULT_DESTINATION,
                AprsTransmitPacket.DEFAULT_PATH, "FieldPacket")
                : packet;
        List<String> addresses = new ArrayList<>();
        addresses.add(safe.destination);
        addresses.add(safe.source);
        addresses.addAll(safe.pathAddresses());

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        for (int i = 0; i < addresses.size(); i++) {
            byte[] encoded = encodeAddress(addresses.get(i), i == addresses.size() - 1);
            frame.write(encoded, 0, encoded.length);
        }
        frame.write(CONTROL_UI);
        frame.write(PID_NO_LAYER3);
        byte[] info = safe.information.getBytes(StandardCharsets.US_ASCII);
        frame.write(info, 0, info.length);

        byte[] withoutFcs = frame.toByteArray();
        int fcs = crc16Ax25(withoutFcs);
        ByteArrayOutputStream withFcs = new ByteArrayOutputStream();
        withFcs.write(withoutFcs, 0, withoutFcs.length);
        withFcs.write(fcs & 0xFF);
        withFcs.write((fcs >> 8) & 0xFF);
        return new Ax25Frame(safe, withoutFcs, withFcs.toByteArray(), fcs);
    }

    public static ParsedUiFrame parseUiFrame(byte[] frameWithFcs) {
        if (frameWithFcs == null || frameWithFcs.length < 18) {
            throw new IllegalArgumentException("AX.25 frame too short");
        }
        byte[] withoutFcs = Arrays.copyOf(frameWithFcs, frameWithFcs.length - 2);
        int providedFcs = (frameWithFcs[frameWithFcs.length - 2] & 0xFF)
                | ((frameWithFcs[frameWithFcs.length - 1] & 0xFF) << 8);
        int computedFcs = crc16Ax25(withoutFcs);
        if (providedFcs != computedFcs) {
            throw new IllegalArgumentException(String.format(Locale.US,
                    "FCS mismatch: received %04X, expected %04X", providedFcs, computedFcs));
        }
        return parseUiFrameBytes(withoutFcs, frameWithFcs.clone(), providedFcs);
    }

    public static ParsedUiFrame parseUiFrameWithoutFcs(byte[] frameWithoutFcs) {
        if (frameWithoutFcs == null || frameWithoutFcs.length < 16) {
            throw new IllegalArgumentException("AX.25 frame too short");
        }
        return parseUiFrameBytes(frameWithoutFcs.clone(), frameWithoutFcs.clone(), -1);
    }

    private static ParsedUiFrame parseUiFrameBytes(byte[] withoutFcs, byte[] displayedFrameBytes, int providedFcs) {
        List<String> addresses = new ArrayList<>();
        int offset = 0;
        boolean lastAddress = false;
        while (!lastAddress) {
            if (offset + 7 > withoutFcs.length) {
                throw new IllegalArgumentException("AX.25 address field truncated");
            }
            addresses.add(decodeAddress(withoutFcs, offset));
            lastAddress = (withoutFcs[offset + 6] & 0x01) != 0;
            offset += 7;
            if (addresses.size() > 10) {
                throw new IllegalArgumentException("AX.25 address list too long");
            }
        }
        if (addresses.size() < 2) {
            throw new IllegalArgumentException("AX.25 frame missing source/destination");
        }
        if (offset + 2 > withoutFcs.length) {
            throw new IllegalArgumentException("AX.25 UI control/PID truncated");
        }
        int control = withoutFcs[offset++] & 0xFF;
        int pid = withoutFcs[offset++] & 0xFF;
        if (control != CONTROL_UI || pid != PID_NO_LAYER3) {
            throw new IllegalArgumentException(String.format(Locale.US,
                    "not an AX.25 UI/no-layer-3 frame: control=%02X pid=%02X", control, pid));
        }
        byte[] infoBytes = Arrays.copyOfRange(withoutFcs, offset, withoutFcs.length);
        String information = new String(infoBytes, StandardCharsets.US_ASCII);
        List<String> path = new ArrayList<>();
        if (addresses.size() > 2) {
            path.addAll(addresses.subList(2, addresses.size()));
        }
        return new ParsedUiFrame(
                addresses.get(1),
                addresses.get(0),
                path,
                information,
                withoutFcs,
                displayedFrameBytes,
                providedFcs,
                control,
                pid);
    }

    public static byte[] encodeAddress(String address, boolean last) {
        ParsedAddress parsed = ParsedAddress.parse(AprsTransmitPacket.normalizeAddress(address, AprsTransmitPacket.DEFAULT_SOURCE));
        byte[] out = new byte[7];
        for (int i = 0; i < 6; i++) {
            char c = i < parsed.callsign.length() ? parsed.callsign.charAt(i) : ' ';
            out[i] = (byte) ((c & 0x7F) << 1);
        }
        int ssid = 0x60 | ((parsed.ssid & 0x0F) << 1);
        if (last) ssid |= 0x01;
        out[6] = (byte) ssid;
        return out;
    }

    public static int crc16Ax25(byte[] data) {
        int crc = 0xFFFF;
        if (data != null) {
            for (byte datum : data) {
                int value = datum & 0xFF;
                for (int i = 0; i < 8; i++) {
                    boolean xor = ((crc ^ value) & 0x01) != 0;
                    crc >>= 1;
                    if (xor) crc ^= 0x8408;
                    value >>= 1;
                }
            }
        }
        return (~crc) & 0xFFFF;
    }

    public int addressFieldCount() {
        return 2 + packet.pathAddresses().size();
    }

    public int informationLength() {
        return packet.information.length();
    }

    public String fcsHex() {
        return String.format(Locale.US, "%04X", fcs);
    }

    public String frameHex(int maxBytes) {
        int max = maxBytes <= 0 ? frameWithFcs.length : Math.min(maxBytes, frameWithFcs.length);
        StringBuilder sb = new StringBuilder(max * 3 + 16);
        for (int i = 0; i < max; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", frameWithFcs[i] & 0xFF));
        }
        if (max < frameWithFcs.length) sb.append(" ... (").append(frameWithFcs.length).append(" bytes)");
        return sb.toString();
    }

    public String summary() {
        return "AX.25 UI frame: " + addressFieldCount() + " address field(s), "
                + informationLength() + " APRS info byte(s), "
                + frameWithoutFcs.length + " byte(s) before FCS, FCS " + fcsHex();
    }

    private static String decodeAddress(byte[] data, int offset) {
        StringBuilder call = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            int value = (data[offset + i] & 0xFF) >> 1;
            if (value > 0 && value != ' ') call.append((char) value);
        }
        String callsign = call.toString().trim();
        if (callsign.isEmpty()) callsign = "?";
        int ssid = ((data[offset + 6] & 0xFF) >> 1) & 0x0F;
        return ssid == 0 ? callsign : callsign + "-" + ssid;
    }

    public static final class ParsedUiFrame {
        public final String source;
        public final String destination;
        public final List<String> path;
        public final String information;
        public final byte[] frameWithoutFcs;
        public final byte[] frameWithFcs;
        public final int fcs;
        public final int control;
        public final int pid;

        private ParsedUiFrame(String source, String destination, List<String> path, String information,
                              byte[] frameWithoutFcs, byte[] frameWithFcs, int fcs, int control, int pid) {
            this.source = source;
            this.destination = destination;
            this.path = Collections.unmodifiableList(new ArrayList<>(path));
            this.information = information == null ? "" : information;
            this.frameWithoutFcs = frameWithoutFcs.clone();
            this.frameWithFcs = frameWithFcs.clone();
            this.fcs = fcs < 0 ? -1 : (fcs & 0xFFFF);
            this.control = control & 0xFF;
            this.pid = pid & 0xFF;
        }

        public String pathText() {
            if (path.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < path.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(path.get(i));
            }
            return sb.toString();
        }

        public String tnc2Line() {
            StringBuilder sb = new StringBuilder();
            sb.append(source).append('>').append(destination);
            if (!path.isEmpty()) sb.append(',').append(pathText());
            sb.append(':').append(information);
            return sb.toString();
        }

        public boolean hasFcs() {
            return fcs >= 0;
        }

        public String fcsHex() {
            return hasFcs() ? String.format(Locale.US, "%04X", fcs) : "not supplied";
        }

        public String frameHex(int maxBytes) {
            int max = maxBytes <= 0 ? frameWithFcs.length : Math.min(maxBytes, frameWithFcs.length);
            StringBuilder sb = new StringBuilder(max * 3 + 16);
            for (int i = 0; i < max; i++) {
                if (i > 0) sb.append(' ');
                sb.append(String.format(Locale.US, "%02X", frameWithFcs[i] & 0xFF));
            }
            if (max < frameWithFcs.length) sb.append(" ... (").append(frameWithFcs.length).append(" bytes)");
            return sb.toString();
        }

        public String summary() {
            return "AX.25 UI frame decoded: " + source + ">" + destination
                    + (path.isEmpty() ? "" : "," + pathText())
                    + ", " + information.length() + " APRS info byte(s), FCS " + fcsHex();
        }
    }

    private static final class ParsedAddress {
        final String callsign;
        final int ssid;

        private ParsedAddress(String callsign, int ssid) {
            this.callsign = callsign;
            this.ssid = ssid;
        }

        static ParsedAddress parse(String normalized) {
            String value = normalized == null || normalized.isEmpty() ? AprsTransmitPacket.DEFAULT_SOURCE : normalized;
            int dash = value.indexOf('-');
            if (dash < 0) {
                return new ParsedAddress(value, 0);
            }
            int ssid = 0;
            try {
                ssid = Integer.parseInt(value.substring(dash + 1));
            } catch (NumberFormatException ignored) {
                ssid = 0;
            }
            if (ssid < 0) ssid = 0;
            if (ssid > 15) ssid = 15;
            return new ParsedAddress(value.substring(0, dash), ssid);
        }
    }
}
