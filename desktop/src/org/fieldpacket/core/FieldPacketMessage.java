package org.fieldpacket.core;

public final class FieldPacketMessage {
    public enum Type {
        BULLETIN,
        EMERGENCY
    }

    public final Type type;
    public final String id;
    public final String from;
    public final String area;
    public final String expires;
    public final String priority;
    public final String location;
    public final String needs;
    public final String body;

    public FieldPacketMessage(Type type, String id, String from, String area, String expires,
                              String priority, String location, String needs, String body) {
        this.type = type == null ? Type.BULLETIN : type;
        this.id = clean(id);
        this.from = clean(from);
        this.area = clean(area);
        this.expires = clean(expires);
        this.priority = clean(priority);
        this.location = clean(location);
        this.needs = clean(needs);
        this.body = clean(body);
    }

    public static FieldPacketMessage bulletin(String id, String from, String area, String expires, String body) {
        return new FieldPacketMessage(Type.BULLETIN, id, from, area, expires, "", "", "", body);
    }

    public static FieldPacketMessage emergency(String id, String from, String area, String expires,
                                               String priority, String location, String needs, String body) {
        return new FieldPacketMessage(Type.EMERGENCY, id, from, area, expires, priority, location, needs, body);
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(type).append(" ").append(id.isEmpty() ? "(no id)" : id);
        if (!from.isEmpty()) sb.append(" from ").append(from);
        if (!area.isEmpty()) sb.append(" / ").append(area);
        if (!priority.isEmpty()) sb.append(" / priority ").append(priority);
        if (!expires.isEmpty()) sb.append(" / expires ").append(expires);
        if (!location.isEmpty()) sb.append("\nLocation: ").append(location);
        if (!needs.isEmpty()) sb.append("\nNeeds: ").append(needs);
        if (!body.isEmpty()) sb.append("\nBody: ").append(body);
        return sb.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
