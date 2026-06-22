package org.fieldpacket.core;

public final class FieldPacketSamples {
    public static final String CALIBRATION_PACKET_ID = "FP-CAL-0001";

    private FieldPacketSamples() {}

    public static FieldPacketMessage knownGoodCalibrationMessage() {
        return FieldPacketMessage.bulletin(
                CALIBRATION_PACKET_ID,
                "FIELD-CAL",
                "LOCAL",
                "",
                "FieldPacket known-good radio calibration packet. Decode this text, verify CHECK, then use test tone controls to set a clean transmit level before real traffic."
        );
    }

    public static String knownGoodCalibrationPacket() {
        return FieldPacketCodec.encode(knownGoodCalibrationMessage());
    }
}
