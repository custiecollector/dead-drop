package org.deaddrop.desktop;

import org.fieldpacket.core.AprsTransmitPacket;
import org.fieldpacket.core.Ax25Frame;
import org.fieldpacket.core.FieldPacketCodec;
import org.fieldpacket.core.FieldPacketMessage;
import org.fieldpacket.core.FieldPacketSamples;
import org.fieldpacket.core.KissFrame;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Locale;

/**
 * Desktop-only FieldPacket utility panel for DeadDrop Desktop.
 *
 * <p>This intentionally keeps FieldPacket Android separate from DeadDrop Android. It only exposes
 * in-memory compose/decode helpers for FP1, APRS/AX.25, and KISS hex workflows; it does not open
 * serial, USB, Bluetooth, network, or SDR devices.</p>
 */
final class FieldPacketToolsPanel extends JPanel {
    private static final Color PANEL = new Color(18, 27, 35);
    private static final Color CARD = new Color(24, 36, 46);
    private static final Color TEXT = new Color(232, 240, 244);
    private static final Color MUTED = new Color(159, 178, 190);
    private static final Color ACCENT = new Color(42, 157, 143);

    private final JComboBox<FieldPacketMessage.Type> typeCombo = new JComboBox<>(FieldPacketMessage.Type.values());
    private final JTextField idField = new JTextField("FP-DESK-0001", 18);
    private final JTextField fromField = new JTextField("FIELD", 18);
    private final JTextField areaField = new JTextField("LOCAL", 18);
    private final JTextField expiresField = new JTextField("", 18);
    private final JTextField priorityField = new JTextField("", 18);
    private final JTextField locationField = new JTextField("", 18);
    private final JTextField needsField = new JTextField("", 18);
    private final JTextArea bodyArea = new JTextArea("Short field bulletin text.", 4, 36);

    private final JTextArea packetArea = new JTextArea(10, 56);
    private final JTextArea resultArea = new JTextArea(8, 56);
    private final JTextArea kissInputArea = new JTextArea(8, 56);
    private final JTextArea kissOutputArea = new JTextArea(10, 56);

    FieldPacketToolsPanel() {
        super(new BorderLayout(8, 8));
        setBackground(PANEL);
        setPreferredSize(new Dimension(760, 620));
        buildUi();
        loadKnownGood();
        applyDarkTheme(this);
    }

    private void buildUi() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Compose / decode", composeTab());
        tabs.addTab("APRS / KISS", kissTab());
        tabs.addTab("Notes", notesTab());
        styleTabs(tabs);
        add(tabs, BorderLayout.CENTER);
    }

    private JPanel composeTab() {
        JPanel root = darkPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel form = darkPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 0;
        int y = 0;
        addRow(form, c, y++, "Type", typeCombo);
        addRow(form, c, y++, "ID", idField);
        addRow(form, c, y++, "From", fromField);
        addRow(form, c, y++, "Area", areaField);
        addRow(form, c, y++, "Expires", expiresField);
        addRow(form, c, y++, "Priority", priorityField);
        addRow(form, c, y++, "Location", locationField);
        addRow(form, c, y++, "Needs", needsField);
        bodyArea.setLineWrap(true);
        bodyArea.setWrapStyleWord(true);
        JScrollPane bodyScroll = scroll(bodyArea, "Body");
        c.gridx = 0; c.gridy = y; c.weightx = 0; form.add(new JLabel("Body"), c);
        c.gridx = 1; c.weightx = 1; c.weighty = 1; c.fill = GridBagConstraints.BOTH; form.add(bodyScroll, c);
        root.add(form, BorderLayout.NORTH);

        packetArea.setLineWrap(true);
        packetArea.setWrapStyleWord(true);
        packetArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        resultArea.setLineWrap(true);
        resultArea.setWrapStyleWord(true);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel center = darkPanel(new GridBagLayout());
        GridBagConstraints cc = new GridBagConstraints();
        cc.insets = new Insets(4, 0, 4, 0);
        cc.gridx = 0;
        cc.gridy = 0;
        cc.weightx = 1;
        cc.weighty = 0.55;
        cc.fill = GridBagConstraints.BOTH;
        center.add(scroll(packetArea, "FP1 packet text"), cc);
        cc.gridy = 1;
        cc.weighty = 0.45;
        center.add(scroll(resultArea, "Result / APRS / AX.25 preview"), cc);
        root.add(center, BorderLayout.CENTER);

        JPanel buttons = darkPanel(new FlowLayout(FlowLayout.LEFT));
        JButton knownGood = new JButton("Known-good sample");
        JButton encode = new JButton("Encode FP1");
        JButton decode = new JButton("Decode FP1");
        JButton preview = new JButton("APRS/KISS preview");
        JButton copyPacket = new JButton("Copy FP1");
        JButton copyResult = new JButton("Copy result");
        knownGood.addActionListener(e -> loadKnownGood());
        encode.addActionListener(e -> encodeFromFields());
        decode.addActionListener(e -> decodePacket());
        preview.addActionListener(e -> previewAprsKiss());
        copyPacket.addActionListener(e -> copy(packetArea.getText()));
        copyResult.addActionListener(e -> copy(resultArea.getText()));
        buttons.add(knownGood);
        buttons.add(encode);
        buttons.add(decode);
        buttons.add(preview);
        buttons.add(copyPacket);
        buttons.add(copyResult);
        root.add(buttons, BorderLayout.SOUTH);
        return root;
    }

    private JPanel kissTab() {
        JPanel root = darkPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        kissInputArea.setLineWrap(true);
        kissInputArea.setWrapStyleWord(true);
        kissInputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        kissOutputArea.setLineWrap(true);
        kissOutputArea.setWrapStyleWord(true);
        kissOutputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel center = darkPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 0, 4, 0);
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.weighty = 0.45;
        c.fill = GridBagConstraints.BOTH;
        center.add(scroll(kissInputArea, "KISS/TNC hex input"), c);
        c.gridy = 1;
        c.weighty = 0.55;
        center.add(scroll(kissOutputArea, "Decoded frames / generated hex"), c);
        root.add(center, BorderLayout.CENTER);

        JPanel buttons = darkPanel(new FlowLayout(FlowLayout.LEFT));
        JButton build = new JButton("Build KISS from compose");
        JButton decode = new JButton("Decode KISS hex");
        JButton copy = new JButton("Copy output");
        build.addActionListener(e -> buildKissFromCompose());
        decode.addActionListener(e -> decodeKissHex());
        copy.addActionListener(e -> copy(kissOutputArea.getText()));
        buttons.add(build);
        buttons.add(decode);
        buttons.add(copy);
        root.add(buttons, BorderLayout.SOUTH);
        return root;
    }

    private JPanel notesTab() {
        JTextArea notes = new JTextArea(
                "FieldPacket tools inside DeadDrop Desktop\n\n"
                        + "• Desktop-only utility surface; FieldPacket Android remains a separate APK.\n"
                        + "• FP1 compose/decode, APRS/AX.25 preview, and KISS/TNC hex helpers run in memory.\n"
                        + "• No serial/USB/Bluetooth/network/SDR device is opened from this panel.\n"
                        + "• Plaintext FieldPacket utilities stay separate from encrypted DeadDrop messages.\n"
                        + "• Runtime hardware checks are still deferred until real devices/radio paths are available.\n");
        notes.setEditable(false);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        JPanel root = darkPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(scroll(notes, "Scope"), BorderLayout.CENTER);
        return root;
    }

    private static void addRow(JPanel panel, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);
    }

    private void loadKnownGood() {
        packetArea.setText(FieldPacketSamples.knownGoodCalibrationPacket());
        FieldPacketCodec.DecodeResult decoded = FieldPacketCodec.decode(packetArea.getText());
        if (decoded.ok) {
            populateFromMessage(decoded.message);
            resultArea.setText("Loaded known-good FieldPacket sample.\n\n" + decoded.message.summary()
                    + "\n\nCHECK: " + decoded.checksum);
        } else {
            resultArea.setText(decoded.error);
        }
    }

    private void encodeFromFields() {
        try {
            FieldPacketMessage message = messageFromFields();
            packetArea.setText(FieldPacketCodec.encode(message));
            resultArea.setText("Encoded FP1 packet.\n\n" + message.summary());
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void decodePacket() {
        FieldPacketCodec.DecodeResult decoded = FieldPacketCodec.decode(packetArea.getText());
        if (!decoded.ok) {
            resultArea.setText("Decode failed: " + decoded.error);
            return;
        }
        populateFromMessage(decoded.message);
        resultArea.setText("Decode OK. CHECK " + decoded.checksum + "\n\n" + decoded.message.summary());
    }

    private void previewAprsKiss() {
        try {
            FieldPacketMessage message = currentMessageForPreview();
            AprsTransmitPacket aprs = AprsTransmitPacket.fromFieldPacket(
                    message,
                    AprsTransmitPacket.DEFAULT_SOURCE,
                    AprsTransmitPacket.DEFAULT_DESTINATION,
                    AprsTransmitPacket.DEFAULT_PATH);
            Ax25Frame ax25 = Ax25Frame.fromAprs(aprs);
            KissFrame kiss = KissFrame.fromAx25(ax25, 0);
            resultArea.setText(aprs.summary()
                    + "\n\n" + ax25.summary()
                    + "\nAX.25 frame with FCS:\n" + ax25.frameHex(256)
                    + "\n\nKISS/TNC data frame hex:\n" + KissFrame.toHex(kiss.toBytes()));
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void buildKissFromCompose() {
        try {
            FieldPacketMessage message = currentMessageForPreview();
            AprsTransmitPacket aprs = AprsTransmitPacket.fromFieldPacket(
                    message,
                    AprsTransmitPacket.DEFAULT_SOURCE,
                    AprsTransmitPacket.DEFAULT_DESTINATION,
                    AprsTransmitPacket.DEFAULT_PATH);
            Ax25Frame ax25 = Ax25Frame.fromAprs(aprs);
            KissFrame kiss = KissFrame.fromAx25(ax25, 0);
            String hex = KissFrame.toHex(kiss.toBytes());
            kissInputArea.setText(hex);
            kissOutputArea.setText("Generated KISS/TNC data frame from compose tab.\n\n"
                    + kiss.summary()
                    + "\n\nHex:\n" + hex);
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void decodeKissHex() {
        try {
            byte[] bytes = KissFrame.parseHex(kissInputArea.getText());
            List<KissFrame> frames = KissFrame.decodeStream(bytes);
            if (frames.isEmpty()) {
                kissOutputArea.setText("No complete KISS frames found.");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Decoded ").append(frames.size()).append(" KISS frame(s).\n");
            for (int i = 0; i < frames.size(); i++) {
                KissFrame frame = frames.get(i);
                sb.append("\n#").append(i + 1).append('\n').append(frame.summary()).append('\n');
                if (frame.isDataFrame()) {
                    try {
                        Ax25Frame.ParsedUiFrame parsed = frame.parseAx25UiFrame();
                        sb.append("TNC2: ").append(parsed.tnc2Line()).append('\n');
                    } catch (Exception ex) {
                        sb.append("TNC2 parse unavailable: ").append(ex.getMessage()).append('\n');
                    }
                }
            }
            kissOutputArea.setText(sb.toString());
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private FieldPacketMessage currentMessageForPreview() {
        FieldPacketCodec.DecodeResult decoded = FieldPacketCodec.decode(packetArea.getText());
        if (decoded.ok) {
            return decoded.message;
        }
        return messageFromFields();
    }

    private FieldPacketMessage messageFromFields() {
        FieldPacketMessage.Type type = (FieldPacketMessage.Type) typeCombo.getSelectedItem();
        return new FieldPacketMessage(
                type == null ? FieldPacketMessage.Type.BULLETIN : type,
                idField.getText(),
                fromField.getText(),
                areaField.getText(),
                expiresField.getText(),
                priorityField.getText(),
                locationField.getText(),
                needsField.getText(),
                bodyArea.getText());
    }

    private void populateFromMessage(FieldPacketMessage message) {
        typeCombo.setSelectedItem(message.type);
        idField.setText(message.id);
        fromField.setText(message.from);
        areaField.setText(message.area);
        expiresField.setText(message.expires);
        priorityField.setText(message.priority);
        locationField.setText(message.location);
        needsField.setText(message.needs);
        bodyArea.setText(message.body);
    }

    private static JPanel darkPanel(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setBackground(PANEL);
        panel.setForeground(TEXT);
        return panel;
    }

    private static JScrollPane scroll(JTextComponent component, String title) {
        component.setBackground(CARD);
        component.setForeground(TEXT);
        component.setCaretColor(TEXT);
        component.setSelectionColor(ACCENT);
        component.setSelectedTextColor(Color.WHITE);
        JScrollPane pane = new JScrollPane(component);
        pane.getViewport().setBackground(CARD);
        pane.setBorder(BorderFactory.createTitledBorder(title));
        return pane;
    }

    private static void styleTabs(JTabbedPane tabs) {
        tabs.setBackground(PANEL);
        tabs.setForeground(TEXT);
        tabs.setOpaque(true);
        tabs.addChangeListener(e -> updateTabColors(tabs));
        updateTabColors(tabs);
    }

    private static void updateTabColors(JTabbedPane tabs) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            tabs.setForegroundAt(i, TEXT);
            tabs.setBackgroundAt(i, i == tabs.getSelectedIndex() ? CARD : PANEL);
        }
    }

    private static void applyDarkTheme(Component root) {
        if (root instanceof JPanel || root instanceof JScrollPane) {
            root.setBackground(PANEL);
        }
        if (root instanceof JLabel) {
            root.setForeground(TEXT);
        }
        if (root instanceof JButton) {
            JButton button = (JButton) root;
            button.setBackground(CARD);
            button.setForeground(TEXT);
            button.setOpaque(true);
            button.setContentAreaFilled(true);
            button.setFocusPainted(false);
            button.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(61, 82, 96)),
                    BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        }
        if (root instanceof JComboBox) {
            JComboBox<?> combo = (JComboBox<?>) root;
            combo.setBackground(CARD);
            combo.setForeground(TEXT);
            combo.setOpaque(true);
            combo.setBorder(BorderFactory.createLineBorder(new Color(61, 82, 96)));
        }
        if (root instanceof JTabbedPane) {
            root.setBackground(PANEL);
            root.setForeground(TEXT);
        }
        if (root instanceof JTextComponent) {
            JTextComponent text = (JTextComponent) root;
            text.setBackground(CARD);
            text.setForeground(TEXT);
            text.setCaretColor(TEXT);
            text.setSelectionColor(ACCENT);
            text.setSelectedTextColor(Color.WHITE);
        }
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) {
                applyDarkTheme(child);
            }
        }
    }

    private void showError(Exception ex) {
        String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        JOptionPane.showMessageDialog(this, message, "FieldPacket tools", JOptionPane.ERROR_MESSAGE);
    }

    private static void copy(String text) {
        String safe = text == null ? "" : text;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(safe), null);
    }
}
