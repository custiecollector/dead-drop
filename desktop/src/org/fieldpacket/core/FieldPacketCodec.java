package org.fieldpacket.core;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

public final class FieldPacketCodec {
    public static final String VERSION = "FP1";
    public static final int MAX_PACKET_CHARS = 8192;

    private FieldPacketCodec() {}

    public static String encode(FieldPacketMessage message) {
        String canonical = canonicalWithoutCheck(message);
        return canonical + "CHECK:" + checksumHex(canonical) + "\nEND\n";
    }

    public static DecodeResult decode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DecodeResult.error("No packet text provided.");
        }
        if (raw.length() > MAX_PACKET_CHARS) {
            return DecodeResult.error("Packet text is too large; limit is " + MAX_PACKET_CHARS + " characters.");
        }
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        String[] lines = normalized.split("\n");
        if (lines.length < 2 || !VERSION.equals(lines[0].trim())) {
            return DecodeResult.error("Not a FieldPacket FP1 packet.");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        String providedCheck = "";
        StringBuilder canonical = new StringBuilder();
        canonical.append(VERSION).append('\n');
        boolean sawEnd = false;
        boolean sawCheck = false;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if ("END".equals(line)) {
                sawEnd = true;
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                return DecodeResult.error("Malformed line " + (i + 1) + ": " + line);
            }
            String key = line.substring(0, colon).trim().toUpperCase(Locale.US);
            String value = line.substring(colon + 1);
            if ("CHECK".equals(key)) {
                providedCheck = value.trim().toUpperCase(Locale.US);
                sawCheck = true;
                continue;
            }
            if (sawCheck) {
                return DecodeResult.error("Fields cannot appear after CHECK.");
            }
            canonical.append(key).append(':').append(value).append('\n');
            fields.put(key, unescape(value));
        }

        if (!sawEnd) {
            return DecodeResult.error("Missing END marker.");
        }
        if (!sawCheck || providedCheck.isEmpty()) {
            return DecodeResult.error("Missing CHECK field.");
        }
        String computed = checksumHex(canonical.toString());
        boolean valid = computed.equals(providedCheck);
        if (!valid) {
            return DecodeResult.error("Checksum mismatch: expected " + computed + " but packet has " + providedCheck + ".");
        }

        String typeText = value(fields, "TYPE", "BULLETIN").toUpperCase(Locale.US);
        FieldPacketMessage.Type type;
        try {
            type = FieldPacketMessage.Type.valueOf(typeText);
        } catch (IllegalArgumentException ex) {
            return DecodeResult.error("Unknown TYPE: " + typeText);
        }

        FieldPacketMessage message = new FieldPacketMessage(
                type,
                value(fields, "ID", ""),
                value(fields, "FROM", ""),
                value(fields, "AREA", ""),
                value(fields, "EXPIRES", ""),
                value(fields, "PRIORITY", ""),
                value(fields, "LOCATION", ""),
                value(fields, "NEEDS", ""),
                value(fields, "BODY", "")
        );
        return DecodeResult.ok(message, computed);
    }

    public static String canonicalWithoutCheck(FieldPacketMessage message) {
        StringBuilder sb = new StringBuilder();
        sb.append(VERSION).append('\n');
        append(sb, "TYPE", message.type.name());
        append(sb, "ID", message.id);
        append(sb, "FROM", message.from);
        append(sb, "AREA", message.area);
        append(sb, "EXPIRES", message.expires);
        if (message.type == FieldPacketMessage.Type.EMERGENCY || !message.priority.isEmpty()) append(sb, "PRIORITY", message.priority);
        if (message.type == FieldPacketMessage.Type.EMERGENCY || !message.location.isEmpty()) append(sb, "LOCATION", message.location);
        if (message.type == FieldPacketMessage.Type.EMERGENCY || !message.needs.isEmpty()) append(sb, "NEEDS", message.needs);
        append(sb, "BODY", message.body);
        return sb.toString();
    }

    public static String checksumHex(String canonical) {
        CRC32 crc = new CRC32();
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return String.format(Locale.US, "%08X", crc.getValue());
    }

    private static void append(StringBuilder sb, String key, String value) {
        sb.append(key).append(':').append(escape(value)).append('\n');
    }

    public static String escape(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
        return sb.toString();
    }

    public static String unescape(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(value.length());
        boolean escaping = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!escaping) {
                if (c == '\\') escaping = true;
                else sb.append(c);
            } else {
                if (c == 'n') sb.append('\n');
                else if (c == 'r') sb.append('\r');
                else sb.append(c);
                escaping = false;
            }
        }
        if (escaping) sb.append('\\');
        return sb.toString();
    }

    private static String value(Map<String, String> fields, String key, String fallback) {
        String value = fields.get(key);
        return value == null ? fallback : value;
    }

    public static final class DecodeResult {
        public final boolean ok;
        public final FieldPacketMessage message;
        public final String checksum;
        public final String error;

        private DecodeResult(boolean ok, FieldPacketMessage message, String checksum, String error) {
            this.ok = ok;
            this.message = message;
            this.checksum = checksum;
            this.error = error;
        }

        public static DecodeResult ok(FieldPacketMessage message, String checksum) {
            return new DecodeResult(true, message, checksum, "");
        }

        public static DecodeResult error(String error) {
            return new DecodeResult(false, null, "", error == null ? "Decode failed." : error);
        }
    }
}
