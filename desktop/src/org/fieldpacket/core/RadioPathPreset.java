package org.fieldpacket.core;

public final class RadioPathPreset {
    public enum Id {
        ACOUSTIC_NO_CABLE,
        GENERIC_AUDIO_CABLE,
        HANDHELD_ACCESSORY_CABLE,
        USB_RADIO_INTERFACE,
        KISS_TNC_PLANNING,
        SDR_MANUAL_PCM_IMPORT
    }

    private static final RadioPathPreset[] PRESETS = new RadioPathPreset[]{
            new RadioPathPreset(
                    Id.ACOUSTIC_NO_CABLE,
                    "Acoustic / no cable",
                    "Speaker near radio microphone. Start low, raise slowly, and leave room noise as the main variable.",
                    800,
                    400,
                    2,
                    55,
                    true,
                    false
            ),
            new RadioPathPreset(
                    Id.GENERIC_AUDIO_CABLE,
                    "Generic audio cable",
                    "Phone speaker/headphone output into radio audio input. Use conservative level to avoid overdriving.",
                    500,
                    300,
                    2,
                    35,
                    true,
                    false
            ),
            new RadioPathPreset(
                    Id.HANDHELD_ACCESSORY_CABLE,
                    "Handheld accessory cable",
                    "Handheld speaker-mic/accessory lead. Longer lead-in helps VOX/PTT settle before payload audio.",
                    900,
                    500,
                    2,
                    30,
                    true,
                    false
            ),
            new RadioPathPreset(
                    Id.USB_RADIO_INTERFACE,
                    "USB radio interface",
                    "External USB audio/PTT interface. FieldPacket does not provide USB control; use this as an audio-level checklist.",
                    350,
                    250,
                    1,
                    45,
                    true,
                    false
            ),
            new RadioPathPreset(
                    Id.KISS_TNC_PLANNING,
                    "KISS / TNC planning",
                    "Serial KISS/TNC frame workflow. FieldPacket can encode/decode KISS frames from pasted bytes but does not open serial, USB, Bluetooth, or network devices.",
                    0,
                    0,
                    1,
                    0,
                    false,
                    true
            ),
            new RadioPathPreset(
                    Id.SDR_MANUAL_PCM_IMPORT,
                    "SDR/manual PCM import",
                    "Receive-only path for WAV/raw PCM produced by any SDR or audio pipeline. Import is user-started and decoded in memory.",
                    0,
                    0,
                    1,
                    0,
                    false,
                    true
            )
    };

    public final Id id;
    public final String label;
    public final String summary;
    public final int defaultLeadInMs;
    public final int defaultTailMs;
    public final int defaultRepeats;
    public final int defaultLevelPercent;
    public final boolean transmitCapable;
    public final boolean receivePlaceholder;

    private RadioPathPreset(Id id, String label, String summary, int defaultLeadInMs, int defaultTailMs,
                            int defaultRepeats, int defaultLevelPercent, boolean transmitCapable,
                            boolean receivePlaceholder) {
        this.id = id;
        this.label = label;
        this.summary = summary;
        this.defaultLeadInMs = defaultLeadInMs;
        this.defaultTailMs = defaultTailMs;
        this.defaultRepeats = defaultRepeats;
        this.defaultLevelPercent = defaultLevelPercent;
        this.transmitCapable = transmitCapable;
        this.receivePlaceholder = receivePlaceholder;
    }

    public static RadioPathPreset[] presets() {
        return PRESETS.clone();
    }

    public static String[] labels() {
        String[] labels = new String[PRESETS.length];
        for (int i = 0; i < PRESETS.length; i++) {
            labels[i] = PRESETS[i].label;
        }
        return labels;
    }

    public static RadioPathPreset at(int index) {
        if (index < 0 || index >= PRESETS.length) {
            return PRESETS[0];
        }
        return PRESETS[index];
    }

    public String details() {
        String mode = transmitCapable ? "TX calibration" : "RX/import planning";
        return label + " — " + mode + "\n" + summary + "\nDefaults: lead-in " + defaultLeadInMs
                + " ms, tail " + defaultTailMs + " ms, repeats " + defaultRepeats
                + ", level " + defaultLevelPercent + "%";
    }
}
