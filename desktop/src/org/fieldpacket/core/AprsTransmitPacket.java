package org.fieldpacket.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AprsTransmitPacket {
    public static final String DEFAULT_SOURCE = "NOCALL-7";
    public static final String DEFAULT_DESTINATION = "APFP03";
    public static final String DEFAULT_PATH = "WIDE1-1";
    public static final int MAX_INFO_CHARS = 220;
    public static final int MAX_DIGIPEATER_PATHS = 8;

    public final String source;
    public final String destination;
    public final String path;
    public final String information;

    private AprsTransmitPacket(String source, String destination, String path, String information) {
        this.source = normalizeAddress(source, DEFAULT_SOURCE);
        this.destination = normalizeAddress(destination, DEFAULT_DESTINATION);
        this.path = normalizePath(path);
        this.information = cleanInfo(information);
    }

    public static AprsTransmitPacket create(String source, String destination, String path, String information) {
        return new AprsTransmitPacket(source, destination, path, information);
    }

    public static AprsTransmitPacket fromFieldPacket(FieldPacketMessage message, String source, String destination, String path) {
        return create(source, destination, path, infoFromFieldPacket(message));
    }

    public static String infoFromFieldPacket(FieldPacketMessage message) {
        FieldPacketMessage safe = message == null
                ? FieldPacketMessage.bulletin("FP-UNKNOWN", "FIELD", "LOCAL", "", "")
                : message;
        StringBuilder sb = new StringBuilder();
        sb.append(">FieldPacket ").append(safe.type.name());
        if (!safe.id.isEmpty()) sb.append(' ').append(safe.id);
        if (!safe.from.isEmpty()) sb.append(" from ").append(safe.from);
        if (!safe.area.isEmpty()) sb.append(" / ").append(safe.area);
        if (!safe.priority.isEmpty()) sb.append(" / priority ").append(safe.priority);
        if (!safe.location.isEmpty()) sb.append(" / loc ").append(safe.location);
        if (!safe.needs.isEmpty()) sb.append(" / needs ").append(safe.needs);
        if (!safe.body.isEmpty()) sb.append(": ").append(safe.body.replace('\n', ' '));
        return cleanInfo(sb.toString());
    }

    public List<String> pathAddresses() {
        List<String> result = new ArrayList<>();
        if (path.isEmpty()) return result;
        String[] parts = path.split(",");
        for (String part : parts) {
            String normalized = normalizeAddress(part, "");
            if (!normalized.isEmpty()) result.add(normalized);
        }
        return result;
    }

    public String tnc2Line() {
        StringBuilder sb = new StringBuilder();
        sb.append(source).append('>').append(destination);
        if (!path.isEmpty()) sb.append(',').append(path);
        sb.append(':').append(information);
        return sb.toString();
    }

    public String summary() {
        return tnc2Line() + "\nInfo length: " + information.length() + " char(s)"
                + "\nAddress fields: destination + source + " + pathAddresses().size() + " path hop(s)";
    }

    public static String normalizeAddress(String raw, String fallback) {
        String fallbackClean = fallback == null ? "" : fallback.trim().toUpperCase(Locale.US);
        if (raw == null) return fallbackClean;
        String value = raw.trim().toUpperCase(Locale.US);
        if (value.endsWith("*")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.isEmpty()) return fallbackClean;

        String call = value;
        int ssid = -1;
        int dash = value.indexOf('-');
        if (dash >= 0) {
            call = value.substring(0, dash);
            try {
                ssid = Integer.parseInt(value.substring(dash + 1));
            } catch (NumberFormatException ex) {
                return fallbackClean;
            }
        }

        StringBuilder cleanCall = new StringBuilder(6);
        for (int i = 0; i < call.length() && cleanCall.length() < 6; i++) {
            char c = call.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                cleanCall.append(c);
            }
        }
        if (cleanCall.length() == 0) return fallbackClean;
        if (ssid < 0) return cleanCall.toString();
        if (ssid > 15) ssid = 15;
        return cleanCall + "-" + ssid;
    }

    public static String normalizePath(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String[] parts = raw.split(",");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String part : parts) {
            String normalized = normalizeAddress(part, "");
            if (normalized.isEmpty()) continue;
            if (count >= MAX_DIGIPEATER_PATHS) break;
            if (sb.length() > 0) sb.append(',');
            sb.append(normalized);
            count++;
        }
        return sb.toString();
    }

    public static String cleanInfo(String raw) {
        if (raw == null) return "";
        String value = raw.replace('\r', ' ').replace('\n', ' ').trim();
        StringBuilder ascii = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 32 && c <= 126) ascii.append(c);
            else ascii.append('?');
        }
        if (ascii.length() <= MAX_INFO_CHARS) return ascii.toString();
        return ascii.substring(0, MAX_INFO_CHARS - 3) + "...";
    }
}
