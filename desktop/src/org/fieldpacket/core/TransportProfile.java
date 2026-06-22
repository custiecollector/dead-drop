package org.fieldpacket.core;

/**
 * Hardware-neutral receive/transmit workflow descriptions for FieldPacket transport paths.
 *
 * <p>Profiles are planning metadata only. They do not open devices or request Android permissions;
 * Android UI code decides which user-started action to run.</p>
 */
public final class TransportProfile {
    public enum Id {
        LIVE_MIC_APRS_AFSK,
        SDR_OR_MANUAL_PCM_IMPORT,
        KISS_TNC_FRAMES,
        MANUAL_TEXT_PACKET
    }

    private static final TransportProfile[] PROFILES = new TransportProfile[]{
            new TransportProfile(
                    Id.LIVE_MIC_APRS_AFSK,
                    "Live mic APRS/AFSK RX",
                    "User-started microphone receive using the Bell 202 decoder. The audio buffer clears when stopped.",
                    true,
                    "RECORD_AUDIO only when live RX starts",
                    false
            ),
            new TransportProfile(
                    Id.SDR_OR_MANUAL_PCM_IMPORT,
                    "SDR/manual PCM import",
                    "User-selected WAV or raw signed 16-bit PCM from any SDR/audio pipeline. Decoded in memory; no receiver brand is assumed.",
                    true,
                    "No extra Android permission via system file picker",
                    false
            ),
            new TransportProfile(
                    Id.KISS_TNC_FRAMES,
                    "KISS/TNC frame helper",
                    "KISS encode/decode helpers for AX.25 UI payloads. Serial/USB/Bluetooth device control is not included.",
                    true,
                    "No serial/USB/Bluetooth permission in current app",
                    false
            ),
            new TransportProfile(
                    Id.MANUAL_TEXT_PACKET,
                    "Manual text packet import",
                    "Paste FP1 or APRS/TNC2 text for offline decode/inspection. Useful when another tool handles radio capture.",
                    true,
                    "No Android permission",
                    false
            )
    };

    public final Id id;
    public final String label;
    public final String summary;
    public final boolean userStarted;
    public final String permissionModel;
    public final boolean storesAudio;

    private TransportProfile(Id id, String label, String summary, boolean userStarted,
                             String permissionModel, boolean storesAudio) {
        this.id = id;
        this.label = label;
        this.summary = summary;
        this.userStarted = userStarted;
        this.permissionModel = permissionModel;
        this.storesAudio = storesAudio;
    }

    public static TransportProfile[] profiles() {
        return PROFILES.clone();
    }

    public static String[] labels() {
        String[] labels = new String[PROFILES.length];
        for (int i = 0; i < PROFILES.length; i++) labels[i] = PROFILES[i].label;
        return labels;
    }

    public static TransportProfile at(int index) {
        if (index < 0 || index >= PROFILES.length) return PROFILES[0];
        return PROFILES[index];
    }

    public String details() {
        return label + "\n" + summary + "\nAction: " + (userStarted ? "tap to start" : "automatic")
                + "\nPermission: " + permissionModel
                + "\nAudio: " + (storesAudio ? "may save audio" : "not saved");
    }
}
