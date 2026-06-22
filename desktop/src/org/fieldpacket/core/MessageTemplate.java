package org.fieldpacket.core;

/**
 * Reusable message templates for field operators.
 *
 * <p>Templates are plain Java metadata. They do not perform I/O, request network access,
 * or store anything by themselves. Android UI code may copy a template into the
 * composer or save a user-edited template in app-private preferences.</p>
 */
public final class MessageTemplate {
    public enum Id {
        STATUS_CHECK,
        SUPPLY_REQUEST,
        ROUTE_HAZARD,
        EMERGENCY_NEEDS
    }

    private static final MessageTemplate[] TEMPLATES = new MessageTemplate[]{
            new MessageTemplate(
                    Id.STATUS_CHECK,
                    "Status check bulletin",
                    FieldPacketMessage.Type.BULLETIN,
                    "LOCAL",
                    "",
                    "",
                    "",
                    "",
                    "Status check: station is operational. Update time, area, and any limits before transmit."
            ),
            new MessageTemplate(
                    Id.SUPPLY_REQUEST,
                    "Supply request bulletin",
                    FieldPacketMessage.Type.BULLETIN,
                    "LOCAL",
                    "",
                    "",
                    "",
                    "",
                    "Supply request: item, quantity, destination, contact, and time window."
            ),
            new MessageTemplate(
                    Id.ROUTE_HAZARD,
                    "Route hazard bulletin",
                    FieldPacketMessage.Type.BULLETIN,
                    "LOCAL",
                    "",
                    "",
                    "",
                    "",
                    "Route hazard: describe location, direction of travel, obstruction, and workaround."
            ),
            new MessageTemplate(
                    Id.EMERGENCY_NEEDS,
                    "Emergency needs broadcast",
                    FieldPacketMessage.Type.EMERGENCY,
                    "LOCAL",
                    "",
                    "HIGH",
                    "Grid/local landmark",
                    "Water, battery, medical check",
                    "Emergency-format broadcast: replace with exact condition, count, callback, and requested action."
            )
    };

    public final Id id;
    public final String label;
    public final FieldPacketMessage.Type type;
    public final String area;
    public final String expires;
    public final String priority;
    public final String location;
    public final String needs;
    public final String body;

    private MessageTemplate(Id id, String label, FieldPacketMessage.Type type, String area,
                            String expires, String priority, String location, String needs,
                            String body) {
        this.id = id;
        this.label = label;
        this.type = type;
        this.area = area;
        this.expires = expires;
        this.priority = priority;
        this.location = location;
        this.needs = needs;
        this.body = body;
    }

    public static MessageTemplate[] templates() {
        return TEMPLATES.clone();
    }

    public static String[] labels() {
        String[] labels = new String[TEMPLATES.length];
        for (int i = 0; i < TEMPLATES.length; i++) labels[i] = TEMPLATES[i].label;
        return labels;
    }

    public static MessageTemplate at(int index) {
        if (index < 0 || index >= TEMPLATES.length) return TEMPLATES[0];
        return TEMPLATES[index];
    }

    public FieldPacketMessage toMessage(String packetId, String from) {
        String idText = clean(packetId, "FP-TEMPLATE");
        String fromText = clean(from, "FIELD");
        if (type == FieldPacketMessage.Type.EMERGENCY) {
            return FieldPacketMessage.emergency(idText, fromText, area, expires, priority, location, needs, body);
        }
        return FieldPacketMessage.bulletin(idText, fromText, area, expires, body);
    }

    public String details() {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append("\nType: ").append(type == FieldPacketMessage.Type.EMERGENCY ? "Emergency" : "Bulletin");
        sb.append("\nArea: ").append(area.isEmpty() ? "operator-filled" : area);
        if (type == FieldPacketMessage.Type.EMERGENCY) {
            sb.append("\nPriority: ").append(priority.isEmpty() ? "operator-filled" : priority);
            sb.append("\nLocation: ").append(location.isEmpty() ? "operator-filled" : location);
            sb.append("\nNeeds: ").append(needs.isEmpty() ? "operator-filled" : needs);
        }
        sb.append("\nMessage: ").append(body);
        return sb.toString();
    }

    private static String clean(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
