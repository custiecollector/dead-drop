package org.deaddrop.desktop;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;

import org.deaddrop.app.AudioDiagnostics;
import org.deaddrop.app.AudioModem;
import org.deaddrop.app.DeadDropCrypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultComboBoxModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JViewport;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
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
import java.awt.LayoutManager;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.imageio.ImageIO;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class DeadDropDesktopGui extends JFrame {
    private static final Color BG = new Color(8, 13, 18);
    private static final Color PANEL = new Color(18, 27, 35);
    private static final Color CARD = new Color(24, 36, 46);
    private static final Color TEXT = new Color(232, 240, 244);
    private static final Color MUTED = new Color(159, 178, 190);
    private static final Color ACCENT = new Color(42, 157, 143);

    private final DesktopState state;
    private final Set<String> seen = new HashSet<>();
    private final DesktopAudio audio = new DesktopAudio();

    private final JComboBox<String> groupCombo = new JComboBox<>();
    private final JComboBox<String> modeCombo = new JComboBox<>(new String[]{"Anonymous encrypted", "Signed handle encrypted", "Signed plaintext"});
    private final JComboBox<AudioModem.Profile> profileCombo = new JComboBox<>(AudioModem.Profile.values());
    private final JComboBox<ListenSource> listenSourceCombo = new JComboBox<>(ListenSource.values());
    private final JComboBox<String> inputDeviceCombo = new JComboBox<>();
    private final JComboBox<String> outputDeviceCombo = new JComboBox<>();
    private final JComboBox<String> repeatCombo = new JComboBox<>(new String[]{"1x", "3x", "5x"});
    private final JComboBox<AudioModem.RadioPreset> radioPresetCombo = new JComboBox<>(AudioModem.RadioPreset.values());
    private final JTextArea logArea = new JTextArea();
    private final JTextArea messageArea = new JTextArea(3, 56);
    private final JLabel statusLabel = new JLabel("Ready.");
    private final JButton listenButton = new JButton("Start listening");

    private enum ListenSource {
        MICROPHONE("Microphone / line-in"),
        SYSTEM_OUTPUT("System output / what this computer is playing");

        final String label;
        ListenSource(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private DeadDropDesktopGui(DesktopState state) {
        super("DeadDrop Desktop");
        this.state = state;
        buildUi();
        refreshGroups();
        setStatus("Ready. Mic is off until you start listening.");
    }

    public static void main(String[] args) throws Exception {
        installDarkLookAndFeel();
        if (args.length > 0 && "--self-test".equals(args[0])) {
            selfTest();
            return;
        }
        if (args.length > 0 && "--audio-devices".equals(args[0])) {
            System.out.println("Input devices:");
            for (String label : DesktopAudio.deviceLabels(true)) System.out.println("  " + label);
            System.out.println("Output devices:");
            for (String label : DesktopAudio.deviceLabels(false)) System.out.println("  " + label);
            System.out.println("System output capture:");
            System.out.println("  " + DesktopAudio.systemOutputStatus());
            return;
        }
        if (args.length > 0 && "--launch-check".equals(args[0])) {
            final int launchCheckMillis = args.length > 1 ? Math.max(1200, Integer.parseInt(args[1])) : 1200;
            SwingUtilities.invokeLater(() -> {
                try {
                    DeadDropDesktopGui gui = new DeadDropDesktopGui(DesktopState.sessionOnly());
                    gui.setVisible(true);
                    new javax.swing.Timer(launchCheckMillis, e -> {
                        gui.dispose();
                        System.out.println("DeadDrop Desktop launch check passed.");
                        System.exit(0);
                    }).start();
                } catch (Exception e) {
                    e.printStackTrace();
                    System.exit(1);
                }
            });
            return;
        }
        if (args.length > 0 && "--play-test-ping".equals(args[0])) {
            playTestPing(args);
            return;
        }
        if (args.length > 0 && "--audio-loopback-test".equals(args[0])) {
            audioLoopbackTest(args);
            return;
        }
        if (args.length > 0 && "--decode-pcm-stdin".equals(args[0])) {
            decodePcmStdin(args);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                DesktopState state = DesktopState.openWithPrompt(null);
                DeadDropDesktopGui gui = new DeadDropDesktopGui(state);
                gui.setVisible(true);
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null, "DeadDrop Desktop could not start:\n" + e.getMessage(), "DeadDrop startup error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private static void installDarkLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Exception ignored) {}
        UIManager.put("Panel.background", PANEL);
        UIManager.put("OptionPane.background", PANEL);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Button.background", CARD);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("Button.select", ACCENT.darker());
        UIManager.put("Button.disabledText", MUTED.darker());
        UIManager.put("ComboBox.background", CARD);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.buttonBackground", CARD);
        UIManager.put("ComboBox.buttonForeground", TEXT);
        UIManager.put("ComboBox.selectionBackground", ACCENT);
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);
        UIManager.put("List.background", CARD);
        UIManager.put("List.foreground", TEXT);
        UIManager.put("List.selectionBackground", ACCENT);
        UIManager.put("List.selectionForeground", Color.WHITE);
        UIManager.put("TextField.background", CARD);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("TextArea.background", CARD);
        UIManager.put("TextArea.foreground", TEXT);
        UIManager.put("TextArea.caretForeground", TEXT);
        UIManager.put("PasswordField.background", CARD);
        UIManager.put("PasswordField.foreground", TEXT);
        UIManager.put("PasswordField.caretForeground", TEXT);
        UIManager.put("ScrollPane.background", PANEL);
        UIManager.put("Viewport.background", PANEL);
        UIManager.put("TabbedPane.background", PANEL);
        UIManager.put("TabbedPane.foreground", TEXT);
        UIManager.put("TabbedPane.selected", CARD);
        UIManager.put("TabbedPane.contentAreaColor", PANEL);
        UIManager.put("TabbedPane.darkShadow", new Color(61, 82, 96));
        UIManager.put("TabbedPane.focus", ACCENT.darker());
        UIManager.put("TitledBorder.titleColor", MUTED);
    }

    private static void selfTest() throws Exception {
        DeadDropCrypto.Group group = DeadDropCrypto.createGroup("desktop-self-test");
        String invite = DeadDropCrypto.exportInvite(group, "second-factor");
        BufferedImage qr = qrImage(invite, 320);
        if (qr.getWidth() != 320 || qr.getHeight() != 320) throw new IllegalStateException("QR generation failed.");
        if (!invite.equals(decodeQrImage(qr))) throw new IllegalStateException("QR decode failed.");
        DeadDropCrypto.Group joined = DeadDropCrypto.importInvite(invite, "second-factor");
        List<DeadDropCrypto.Group> groups = new ArrayList<>();
        groups.add(joined);
        KeyPair kp = DesktopState.generateIdentity();
        byte[][] packets = new byte[][]{
                DeadDropCrypto.createEncryptedPacket(group, "desktop anon", 24, 280),
                DeadDropCrypto.createSignedEncryptedPacket(group, "desktop signed", 24, 280, "Desktop", kp.getPublic(), kp.getPrivate()),
                DeadDropCrypto.createSignedPlaintextPacket("desktop plain", 24, 280, "Desktop", kp.getPublic(), kp.getPrivate())
        };
        for (AudioModem.Profile profile : AudioModem.Profile.values()) {
            for (byte[] packet : packets) {
                short[] samples = AudioModem.encode(packet, profile);
                AudioModem.DecodeResult decoded = AudioModem.tryDecodeAny(samples);
                if (decoded == null) throw new IllegalStateException("Decode failed for " + profile.label);
                DeadDropCrypto.ReceivedMessage msg = DeadDropCrypto.openAnyPacket(groups, decoded.payload);
                if (!msg.verified) throw new IllegalStateException("Message not verified: " + msg.mode);
                System.out.println("OK profile=" + decoded.profile.label + " mode=" + msg.mode + " packetBytes=" + packet.length + " samples=" + samples.length);
            }
        }
        System.out.println("DeadDrop Desktop self-test passed.");
    }

    private static void playTestPing(String[] args) throws Exception {
        String outputDevice = "System default";
        AudioModem.Profile profile = AudioModem.Profile.NORMAL;
        AudioModem.RadioPreset radioPreset = AudioModem.RadioPreset.STANDARD;
        int repeats = 1;
        String text = "DeadDrop test " + timestamp();
        for (int i = 1; i < args.length; i++) {
            if ("--output".equals(args[i]) && i + 1 < args.length) {
                outputDevice = args[++i];
            } else if ("--profile".equals(args[i]) && i + 1 < args.length) {
                profile = parseProfile(args[++i]);
            } else if ("--radio-preset".equals(args[i]) && i + 1 < args.length) {
                radioPreset = parseRadioPreset(args[++i]);
                if (!radioPreset.receiveOnly) {
                    profile = radioPreset.defaultProfile;
                    repeats = radioPreset.defaultRepeats;
                }
            } else if ("--repeats".equals(args[i]) && i + 1 < args.length) {
                repeats = clamp(Integer.parseInt(args[++i]), 1, 10);
            } else if ("--text".equals(args[i]) && i + 1 < args.length) {
                text = args[++i];
            } else {
                throw new IllegalArgumentException("Usage: --play-test-ping [--output <device label>] [--profile Normal|Fast/Nearby|Robust+FEC|Ultra/Noisy] [--radio-preset <name>] [--repeats N] [--text <message>]");
            }
        }
        DesktopState state = DesktopState.sessionOnly();
        if (radioPreset.receiveOnly) throw new IllegalArgumentException("SDR receive preset is receive-only.");
        byte[] packet = DeadDropCrypto.createSignedPlaintextPacket(text, state.ttlHours, state.maxChars, state.handle, state.identity.getPublic(), state.identity.getPrivate());
        short[] encoded = AudioModem.encode(packet, profile);
        short[] samples = AudioModem.prepareTransmit(encoded, radioPreset);
        DesktopAudio audio = new DesktopAudio();
        audio.setOutputDevice(outputDevice);
        double seconds = samples.length * repeats / (double) AudioModem.SAMPLE_RATE;
        System.out.println("Playing signed plaintext test ping: profile=" + profile.label + " radioPreset=" + radioPreset.label + " repeats=" + repeats + " output=" + outputDevice + " packetBytes=" + packet.length + " samples=" + samples.length + " seconds=" + String.format(Locale.US, "%.1f", seconds) + " text=" + text);
        audio.play(samples, repeats);
        System.out.println("DeadDrop Desktop test ping playback complete.");
    }

    private static AudioModem.Profile parseProfile(String value) {
        for (AudioModem.Profile profile : AudioModem.Profile.values()) {
            if (profile.name().equalsIgnoreCase(value) || profile.label.equalsIgnoreCase(value)) return profile;
        }
        throw new IllegalArgumentException("Unknown profile: " + value);
    }

    private static AudioModem.RadioPreset parseRadioPreset(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.US).replace('_', ' ').replace('-', ' ');
        for (AudioModem.RadioPreset preset : AudioModem.RadioPreset.values()) {
            String label = preset.label.toLowerCase(Locale.US).replace('_', ' ').replace('-', ' ');
            String name = preset.name().toLowerCase(Locale.US).replace('_', ' ').replace('-', ' ');
            if (label.equals(normalized) || name.equals(normalized) || label.startsWith(normalized)) return preset;
        }
        throw new IllegalArgumentException("Unknown radio preset: " + value);
    }

    private static void audioLoopbackTest(String[] args) throws Exception {
        String inputDevice = "System default";
        String outputDevice = "System default";
        int millis = 3000;
        for (int i = 1; i < args.length; i++) {
            if ("--input".equals(args[i]) && i + 1 < args.length) {
                inputDevice = args[++i];
            } else if ("--output".equals(args[i]) && i + 1 < args.length) {
                outputDevice = args[++i];
            } else if ("--millis".equals(args[i]) && i + 1 < args.length) {
                millis = clamp(Integer.parseInt(args[++i]), 500, 15000);
            } else {
                throw new IllegalArgumentException("Usage: --audio-loopback-test [--input <device label>] [--output <device label>] [--millis N]");
            }
        }
        DesktopAudio audio = new DesktopAudio();
        audio.setInputDevice(inputDevice);
        audio.setOutputDevice(outputDevice);
        Thread player = new Thread(() -> {
            try {
                Thread.sleep(250);
                audio.playTestTone();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "deaddrop-loopback-tone");
        System.out.println("Running audio loopback: input=" + inputDevice + " output=" + outputDevice + " millis=" + millis);
        player.start();
        AudioDiagnostics diag = audio.recordDiagnostics(millis);
        player.join(Math.max(1000, millis));
        System.out.println("Audio loopback result: " + diag.shortStatus());
    }

    private static void decodePcmStdin(String[] args) throws Exception {
        int rate = AudioModem.SAMPLE_RATE;
        int channels = 1;
        String format = "s16le";
        int windowSeconds = 45;
        String passEnv = null;
        for (int i = 1; i < args.length; i++) {
            if ("--rate".equals(args[i]) && i + 1 < args.length) {
                rate = Integer.parseInt(args[++i]);
            } else if ("--channels".equals(args[i]) && i + 1 < args.length) {
                channels = Integer.parseInt(args[++i]);
            } else if ("--format".equals(args[i]) && i + 1 < args.length) {
                format = args[++i];
            } else if ("--window-seconds".equals(args[i]) && i + 1 < args.length) {
                windowSeconds = clamp(Integer.parseInt(args[++i]), 5, 180);
            } else if ("--vault-passphrase-env".equals(args[i]) && i + 1 < args.length) {
                passEnv = args[++i];
            } else {
                throw new IllegalArgumentException("Usage: --decode-pcm-stdin [--rate 44100] [--format s16le] [--channels 1] [--window-seconds N] [--vault-passphrase-env ENV]");
            }
        }
        if (!"s16le".equalsIgnoreCase(format)) throw new IllegalArgumentException("Only s16le PCM is currently supported.");
        if (channels != 1) throw new IllegalArgumentException("Only mono PCM is currently supported.");
        if (rate != AudioModem.SAMPLE_RATE) {
            throw new IllegalArgumentException("Input rate " + rate + " Hz is not supported directly yet; resample to " + AudioModem.SAMPLE_RATE + " Hz before piping to DeadDrop.");
        }

        DesktopState state;
        if (passEnv != null && System.getenv(passEnv) != null && !System.getenv(passEnv).isEmpty()) {
            state = DesktopState.openWithPassphrase(System.getenv(passEnv).toCharArray());
        } else {
            state = DesktopState.sessionOnly();
        }
        Set<String> cliSeen = new HashSet<>();
        RingSamples ring = new RingSamples(AudioModem.SAMPLE_RATE * windowSeconds);
        InputStream in = System.in;
        byte[] buf = new byte[4096];
        byte[] carry = new byte[1];
        boolean hasCarry = false;
        int reads = 0;
        int decoded = 0;
        System.out.println("DeadDrop PCM decoder started: rate=" + rate + " format=" + format + " channels=" + channels + " windowSeconds=" + windowSeconds);
        while (true) {
            int n = in.read(buf);
            if (n < 0) break;
            if (n == 0) continue;
            byte[] chunk = buf;
            int off = 0;
            int len = n;
            if (hasCarry) {
                byte[] merged = new byte[n + 1];
                merged[0] = carry[0];
                System.arraycopy(buf, 0, merged, 1, n);
                chunk = merged;
                len = merged.length;
                hasCarry = false;
            }
            if ((len & 1) == 1) {
                carry[0] = chunk[len - 1];
                hasCarry = true;
                len--;
            }
            if (len <= 0) continue;
            short[] samples = DesktopAudio.bytesToShorts(chunk, len);
            ring.append(samples, samples.length);
            reads++;
            if (tryReportPcmDecode(state, cliSeen, ring.snapshot())) {
                decoded++;
                ring.clear();
            } else if (reads % 32 == 0) {
                AudioModem.DecodeReport report = AudioModem.analyzeDecode(ring.snapshot());
                if (report.signalPresent || report.syncSeen || report.crcFailed) {
                    System.out.println("STATUS " + report.shortStatus() + " samples=" + report.sampleCount);
                }
            }
        }
        if (tryReportPcmDecode(state, cliSeen, ring.snapshot())) decoded++;
        System.out.println("DeadDrop PCM decoder stopped. decoded=" + decoded);
    }

    private static boolean tryReportPcmDecode(DesktopState state, Set<String> seen, short[] samples) {
        AudioModem.DecodeResult result = AudioModem.tryDecodeAny(samples);
        if (result == null) return false;
        try {
            DeadDropCrypto.ReceivedMessage msg = DeadDropCrypto.openAnyPacket(state.groups, result.payload);
            String key = msg.dedupeKey();
            if (!seen.add(key)) {
                System.out.println("DUPLICATE profile=" + result.profile.label + " mode=" + msg.mode + " group=" + msg.groupLabel());
                return true;
            }
            try { state.rememberReplay(msg); } catch (Exception ignored) {}
            System.out.println("DECODE profile=" + result.profile.label
                    + " mode=" + msg.mode
                    + " group=" + msg.groupLabel()
                    + " sender=" + msg.displaySender()
                    + " text=" + msg.text.replace('\n', ' '));
        } catch (Exception e) {
            System.out.println("DECODE profile=" + result.profile.label + " payloadBytes=" + result.payload.length + " openError=" + e.getMessage());
        }
        return true;
    }

    private void buildUi() {
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 640));
        setLayout(new BorderLayout(10, 10));
        getContentPane().setBackground(BG);

        JPanel top = darkPanel(new BorderLayout(8, 8));
        top.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));

        JPanel controls = darkPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridy = 0;
        c.gridx = 0; controls.add(new JLabel("Group:"), c);
        c.gridx = 1; c.weightx = 1; controls.add(groupCombo, c);
        c.weightx = 0; c.gridx = 2; controls.add(new JLabel("Mode:"), c);
        c.gridx = 3; controls.add(modeCombo, c);
        c.gridy = 1; c.gridx = 0; controls.add(new JLabel("Profile:"), c);
        profileCombo.setSelectedItem(AudioModem.Profile.NORMAL);
        c.gridx = 1; controls.add(profileCombo, c);
        c.gridx = 2; controls.add(new JLabel("Repeat:"), c);
        c.gridx = 3; controls.add(repeatCombo, c);
        c.gridy = 2; c.gridx = 0; controls.add(new JLabel("Listen source:"), c);
        c.gridx = 1; controls.add(listenSourceCombo, c);
        c.gridx = 2; controls.add(new JLabel("Output:"), c);
        c.gridx = 3; controls.add(outputDeviceCombo, c);
        c.gridy = 3; c.gridx = 0; controls.add(new JLabel("Input:"), c);
        c.gridx = 1; c.gridwidth = 3; controls.add(inputDeviceCombo, c);
        c.gridy = 4; c.gridx = 0; c.gridwidth = 1; controls.add(new JLabel("Radio:"), c);
        c.gridx = 1; c.gridwidth = 3; controls.add(radioPresetCombo, c);
        c.gridwidth = 1;
        radioPresetCombo.addActionListener(e -> applySelectedRadioPresetDefaults());
        listenSourceCombo.addActionListener(e -> audio.setListenSource((ListenSource) listenSourceCombo.getSelectedItem()));
        refreshAudioDevices();
        audio.setListenSource((ListenSource) listenSourceCombo.getSelectedItem());
        top.add(controls, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        styleText(logArea);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.getViewport().setBackground(CARD);
        logScroll.setBorder(BorderFactory.createTitledBorder("Messages / status"));
        add(logScroll, BorderLayout.CENTER);

        JPanel bottom = darkPanel(new BorderLayout(8, 8));
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        styleText(messageArea);
        JScrollPane msgScroll = new JScrollPane(messageArea);
        msgScroll.getViewport().setBackground(CARD);
        msgScroll.setBorder(BorderFactory.createTitledBorder("Message to transmit"));
        bottom.add(msgScroll, BorderLayout.CENTER);

        JPanel buttons = darkPanel(new FlowLayout(FlowLayout.LEFT));
        JButton create = new JButton("Create");
        JButton export = new JButton("Invite/QR");
        JButton join = new JButton("Join");
        JButton hop = new JButton("Hop assist");
        JButton fieldPacket = new JButton("FieldPacket");
        JButton transmit = new JButton("Transmit");
        JButton test = new JButton("Test ping");
        JButton audioCheck = new JButton("Audio check");
        JButton deviceTest = new JButton("Device test");
        JButton senders = new JButton("Sender safety");
        JButton settings = new JButton("Settings");
        JButton wipe = new JButton("Panic wipe");
        create.addActionListener(e -> createGroup());
        export.addActionListener(e -> exportInvite());
        join.addActionListener(e -> joinInvite());
        hop.addActionListener(e -> manualHopAssistDialog());
        fieldPacket.addActionListener(e -> fieldPacketToolsDialog());
        transmit.addActionListener(e -> transmit(false));
        test.addActionListener(e -> transmit(true));
        listenButton.addActionListener(e -> toggleListen());
        audioCheck.addActionListener(e -> audioCheckDialog());
        deviceTest.addActionListener(e -> deviceTestDialog());
        senders.addActionListener(e -> knownSendersDialog());
        settings.addActionListener(e -> settingsDialog());
        wipe.addActionListener(e -> panicWipe());
        buttons.add(create);
        buttons.add(export);
        buttons.add(join);
        buttons.add(hop);
        buttons.add(fieldPacket);
        buttons.add(transmit);
        buttons.add(test);
        buttons.add(listenButton);
        buttons.add(audioCheck);
        buttons.add(deviceTest);
        buttons.add(senders);
        buttons.add(settings);
        buttons.add(wipe);
        bottom.add(buttons, BorderLayout.NORTH);
        statusLabel.setForeground(MUTED);
        bottom.add(statusLabel, BorderLayout.SOUTH);
        applyDarkTheme(this);
        add(bottom, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);
    }

    private static JPanel darkPanel(LayoutManager layout) {
        JPanel p = new JPanel(layout);
        p.setBackground(PANEL);
        p.setForeground(TEXT);
        return p;
    }

    private static void styleText(JTextComponent c) {
        c.setBackground(CARD);
        c.setForeground(TEXT);
        c.setCaretColor(TEXT);
        c.setSelectionColor(ACCENT);
        c.setSelectedTextColor(Color.WHITE);
    }

    private static DefaultListCellRenderer readableComboRenderer() {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                c.setBackground(isSelected ? ACCENT : CARD);
                c.setForeground(isSelected ? Color.WHITE : TEXT);
                if (c instanceof JLabel) {
                    ((JLabel) c).setOpaque(true);
                }
                return c;
            }
        };
    }

    private static void applyDarkTheme(Component root) {
        if (root instanceof JPanel || root instanceof JScrollPane || root instanceof JViewport) root.setBackground(PANEL);
        if (root instanceof JLabel) root.setForeground(TEXT);
        if (root instanceof AbstractButton) {
            AbstractButton button = (AbstractButton) root;
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
            combo.setRenderer(readableComboRenderer());
            combo.setBorder(BorderFactory.createLineBorder(new Color(61, 82, 96)));
        }
        if (root instanceof JTextComponent) styleText((JTextComponent) root);
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) applyDarkTheme(child);
        }
    }

    private void createGroup() {
        JTextField name = new JTextField("Field Group", 26);
        JPasswordField factor = new JPasswordField(26);
        JPanel panel = form(new String[]{"Group name", "Invite second factor"}, new JComponent[]{name, factor});
        if (JOptionPane.showConfirmDialog(this, panel, "Create DeadDrop group", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            DeadDropCrypto.Group g = DeadDropCrypto.createGroup(name.getText());
            state.groups.add(g);
            state.save();
            refreshGroups();
            appendLog("GROUP", "Created " + g.name + " [" + g.idHex() + "]");
            String sf = new String(factor.getPassword());
            if (sf.length() >= 6) showTextDialog("Invite for " + g.name, DeadDropCrypto.exportInvite(g, sf), true);
            else appendLog("NOTE", "Group created. Export invite later with a second factor of at least 6 characters.");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void exportInvite() {
        DeadDropCrypto.Group g = activeGroup();
        if (g == null) { showInfo("Create or join a group first."); return; }
        JPasswordField factor = new JPasswordField(28);
        if (JOptionPane.showConfirmDialog(this, form(new String[]{"Invite second factor"}, new JComponent[]{factor}), "Export invite", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            showTextDialog("Invite for " + g.name, DeadDropCrypto.exportInvite(g, new String(factor.getPassword())), true);
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void joinInvite() {
        JTextArea invite = new JTextArea(8, 44);
        invite.setLineWrap(true);
        invite.setWrapStyleWord(true);
        JPasswordField factor = new JPasswordField(28);
        JButton importQr = new JButton("Import QR image");
        importQr.addActionListener(e -> {
            try {
                String decoded = chooseAndDecodeQrImage();
                if (decoded != null) {
                    invite.setText(decoded);
                    copy(decoded);
                    setStatus("QR invite decoded and copied. Enter the second factor to join.");
                }
            } catch (Exception ex) {
                showError(ex);
            }
        });
        JPanel helpers = darkPanel(new FlowLayout(FlowLayout.LEFT));
        helpers.add(importQr);
        JPanel panel = darkPanel(new BorderLayout(8, 8));
        panel.add(helpers, BorderLayout.NORTH);
        panel.add(new JScrollPane(invite), BorderLayout.CENTER);
        panel.add(form(new String[]{"Second factor"}, new JComponent[]{factor}), BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, panel, "Join DeadDrop group", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            DeadDropCrypto.Group g = DeadDropCrypto.importInvite(invite.getText(), new String(factor.getPassword()));
            for (DeadDropCrypto.Group existing : state.groups) {
                if (existing.idHex().equals(g.idHex())) { showInfo("Already joined " + g.name); return; }
            }
            state.groups.add(g);
            state.save();
            refreshGroups();
            appendLog("GROUP", "Joined " + g.name + " [" + g.idHex() + "]");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void manualHopAssistDialog() {
        DeadDropCrypto.Group g = activeGroup();
        if (g == null) { showInfo("Create or join a group first."); return; }
        AudioModem.HopSlot slot = AudioModem.manualHopSlot(g.key, g.id, AudioModem.defaultManualHopChannels(), 30, System.currentTimeMillis());
        showInfo("Manual hop assist for " + g.name + "\n\n"
                + "Current: " + slot.current + "\n"
                + "Next: " + slot.next + "\n"
                + "Changes in: " + slot.secondsRemaining + "s\n"
                + "Slot: " + slot.slotIndex + "\n\n"
                + "All members of this group compute the same 30-second schedule from the group key. This is only a manual channel guide; it does not retune radios or SDRs, and message encryption still provides the security.");
    }

    private void fieldPacketToolsDialog() {
        FieldPacketToolsPanel panel = new FieldPacketToolsPanel();
        JOptionPane.showMessageDialog(this, panel, "FieldPacket Desktop Tools", JOptionPane.PLAIN_MESSAGE);
    }

    private void transmit(boolean testPing) {
        String text = testPing ? "DeadDrop test " + timestamp() : messageArea.getText();
        try {
            byte[] packet = createPacket(text);
            AudioModem.Profile profile = (AudioModem.Profile) profileCombo.getSelectedItem();
            AudioModem.RadioPreset radioPreset = selectedRadioPreset();
            if (radioPreset.receiveOnly) throw new IllegalArgumentException("SDR receive is receive-only. Choose another radio preset to transmit.");
            short[] encoded = AudioModem.encode(packet, profile);
            short[] samples = AudioModem.prepareTransmit(encoded, radioPreset);
            int repeats = repeatCount();
            double seconds = samples.length * repeats / (double) AudioModem.SAMPLE_RATE;
            String presetSummary = AudioModem.transmitPresetSummary(radioPreset);
            appendLog("TX", "You / " + modeCombo.getSelectedItem() + " / " + profile.label + " / " + repeats + "x / "
                    + radioPreset.label + " / " + packet.length + " bytes / ~" + String.format(Locale.US, "%.1fs", seconds) + "\n" + text.trim());
            if (!testPing) messageArea.setText("");
            setStatus("Transmitting audio — " + presetSummary + ". Keep receiver listening. Audio is not stored.");
            new Thread(() -> {
                try {
                    audio.setOutputDevice((String) outputDeviceCombo.getSelectedItem());
                    audio.play(samples, repeats);
                    SwingUtilities.invokeLater(() -> setStatus("Transmit complete."));
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> showError(ex));
                }
            }, "deaddrop-desktop-transmit").start();
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private byte[] createPacket(String text) throws Exception {
        int mode = modeCombo.getSelectedIndex();
        if (mode == 0) {
            DeadDropCrypto.Group g = activeGroup();
            if (g == null) throw new IllegalArgumentException("Create or join a group first.");
            return DeadDropCrypto.createEncryptedPacket(g, text, state.ttlHours, state.maxChars);
        }
        if (mode == 1) {
            DeadDropCrypto.Group g = activeGroup();
            if (g == null) throw new IllegalArgumentException("Create or join a group first.");
            return DeadDropCrypto.createSignedEncryptedPacket(g, text, state.ttlHours, state.maxChars, state.handle, state.identity.getPublic(), state.identity.getPrivate());
        }
        return DeadDropCrypto.createSignedPlaintextPacket(text, state.ttlHours, state.maxChars, state.handle, state.identity.getPublic(), state.identity.getPrivate());
    }

    private void toggleListen() {
        if (audio.running) {
            audio.stop();
            listenButton.setText("Start listening");
            setStatus("Listening stopped. Mic is off.");
            return;
        }
        try {
            audio.setListenSource((ListenSource) listenSourceCombo.getSelectedItem());
            audio.setInputDevice((String) inputDeviceCombo.getSelectedItem());
            audio.start(new DesktopAudio.Listener() {
                @Override public void status(String status) {
                    SwingUtilities.invokeLater(() -> setStatus(status));
                }
                @Override public void payload(byte[] payload, AudioModem.Profile profile) {
                    SwingUtilities.invokeLater(() -> handlePayload(payload, profile));
                }
            });
            listenButton.setText("Stop listening");
            setStatus("Listening from " + listenSourceCombo.getSelectedItem() + ". Audio stays in memory and is not stored.");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void handlePayload(byte[] payload, AudioModem.Profile profile) {
        try {
            DeadDropCrypto.ReceivedMessage msg = DeadDropCrypto.openAnyPacket(state.groups, payload);
            String trustNote = rememberSenderTrust(msg);
            if (!state.rememberReplay(msg)) {
                setStatus("Duplicate DeadDrop ignored. Replay cache entries: " + state.replayCache.size() + ".");
                return;
            }
            if (seen.contains(msg.dedupeKey())) {
                setStatus("Duplicate DeadDrop ignored.");
                return;
            }
            seen.add(msg.dedupeKey());
            appendLog("RX", msg.groupLabel() + " / " + msg.displaySender() + " / " + msg.mode + " / " + profile.label + "\n" + msg.text);
            setStatus("Received " + msg.mode + " via " + profile.label + trustNote + ".");
        } catch (Exception ex) {
            appendLog("DROP", "Packet detected but not accepted: " + ex.getMessage());
        }
    }

    private String rememberSenderTrust(DeadDropCrypto.ReceivedMessage msg) {
        if (msg == null || msg.senderFingerprint == null || msg.senderFingerprint.isEmpty()) return "";
        String sender = (msg.senderLabel == null || msg.senderLabel.trim().isEmpty()) ? "Verified sender" : msg.senderLabel.trim();
        String key = msg.groupLabel() + "|" + sender;
        String old = state.knownSenders.put(key, msg.senderFingerprint);
        try { state.save(); } catch (Exception ignored) {}
        if (old != null && !old.equals(msg.senderFingerprint)) {
            return ". KEY CHANGE WARNING for " + sender + ": was #" + old + ", now #" + msg.senderFingerprint;
        }
        return " (#" + msg.senderFingerprint + ")";
    }

    private void audioCheckDialog() {
        refreshAudioDevices();
        showInfo("Audio check\n\n"
                + "Listen source: " + listenSourceCombo.getSelectedItem() + "\n"
                + "System output support: " + DesktopAudio.systemOutputStatus() + "\n"
                + "Input: " + inputDeviceCombo.getSelectedItem() + "\n"
                + "Output: " + outputDeviceCombo.getSelectedItem() + "\n\n"
                + "Profiles:\n"
                + "• Fast/Nearby: clean speaker-to-mic links.\n"
                + "• Normal: default field-test profile.\n"
                + "• Robust+FEC: slower, better for noisy links.\n"
                + "• Voice Bridge/Narrowband: slow speech-band mode for speakerphone/call/radio bridges.\n"
                + "• Ultra/Noisy: very slow emergency/noisy-link mode.\n\n"
                + "When listening, aim for peak 10–80% and avoid clipping. Use Robust/Ultra if packets are heard but decode fails.");
    }

    private void deviceTestDialog() {
        audio.setListenSource((ListenSource) listenSourceCombo.getSelectedItem());
        audio.setInputDevice((String) inputDeviceCombo.getSelectedItem());
        audio.setOutputDevice((String) outputDeviceCombo.getSelectedItem());
        setStatus("Running desktop audio device test...");
        new Thread(() -> {
            try {
                audio.playTestTone();
                AudioDiagnostics diag = audio.recordDiagnostics(3000);
                SwingUtilities.invokeLater(() -> showInfo("Audio device test complete.\n\nOutput: played a short test tone.\nInput: " + diag.shortStatus() + "\n\nAudio was processed in memory only."));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> showError(ex));
            }
        }, "deaddrop-device-test").start();
    }

    private void knownSendersDialog() {
        StringBuilder out = new StringBuilder();
        try {
            out.append("Your sender-safety fingerprint for signed modes:\n#")
                    .append(DeadDropCrypto.fingerprint(state.identity.getPublic().getEncoded()))
                    .append("\n\nKnown signed senders are remembered locally on first verified receive. Compare fingerprints out-of-band before relying on a handle.\n\n");
        } catch (Exception e) {
            out.append("Could not read local signing fingerprint: ").append(e.getMessage()).append("\n\n");
        }
        if (state.knownSenders.isEmpty()) {
            out.append("No signed senders remembered yet. Receive a signed message to add one.\n");
        } else {
            for (Map.Entry<String, String> e : state.knownSenders.entrySet()) {
                out.append("• ").append(e.getKey().replace("|", " / ")).append("\n  #").append(e.getValue()).append('\n');
            }
        }
        int res = JOptionPane.showConfirmDialog(this, out.toString() + "\nForget all known senders?", "Sender safety", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE);
        if (res == JOptionPane.YES_OPTION) {
            state.knownSenders.clear();
            try { state.save(); } catch (Exception ex) { showError(ex); return; }
            setStatus("Known signed senders forgotten. Future signed packets will be learned again.");
        }
    }

    private void refreshAudioDevices() {
        Object inputSelected = inputDeviceCombo.getSelectedItem();
        Object outputSelected = outputDeviceCombo.getSelectedItem();
        inputDeviceCombo.setModel(new DefaultComboBoxModel<>(DesktopAudio.deviceLabels(true)));
        outputDeviceCombo.setModel(new DefaultComboBoxModel<>(DesktopAudio.deviceLabels(false)));
        restoreSelection(inputDeviceCombo, inputSelected);
        restoreSelection(outputDeviceCombo, outputSelected);
    }

    private static void restoreSelection(JComboBox<String> combo, Object selected) {
        if (selected == null) return;
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (String.valueOf(selected).equals(combo.getItemAt(i))) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }

    private void settingsDialog() {
        JTextField handle = new JTextField(state.handle, 24);
        JTextField ttl = new JTextField(String.valueOf(state.ttlHours), 8);
        JTextField max = new JTextField(String.valueOf(state.maxChars), 8);
        JPanel panel = form(new String[]{"Optional sender label for signed modes", "Message TTL hours", "Max chars"}, new JComponent[]{handle, ttl, max});
        if (JOptionPane.showConfirmDialog(this, panel, "DeadDrop Desktop settings", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            state.handle = handle.getText().trim().isEmpty() ? "DeadDrop" : handle.getText().trim();
            state.ttlHours = clamp(Integer.parseInt(ttl.getText().trim()), 1, 168);
            state.maxChars = clamp(Integer.parseInt(max.getText().trim()), 20, 1000);
            state.save();
            setStatus("Settings saved. TTL " + state.ttlHours + "h, max " + state.maxChars + " chars.");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void panicWipe() {
        int res = JOptionPane.showConfirmDialog(this, "Delete local DeadDrop Desktop vault/groups/signing identity from this machine?\nThis cannot be undone.", "Panic wipe", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (res != JOptionPane.YES_OPTION) return;
        try {
            audio.stop();
            state.wipe();
            seen.clear();
            refreshGroups();
            logArea.setText("");
            listenButton.setText("Start listening");
            setStatus("Panic wipe complete. Local desktop vault deleted. Audio was never stored.");
        } catch (Exception ex) {
            showError(ex);
        }
    }

    private DeadDropCrypto.Group activeGroup() {
        int idx = groupCombo.getSelectedIndex();
        if (idx < 0 || idx >= state.groups.size()) return null;
        return state.groups.get(idx);
    }

    private void refreshGroups() {
        String[] labels;
        if (state.groups.isEmpty()) {
            labels = new String[]{"No group yet"};
        } else {
            labels = new String[state.groups.size()];
            for (int i = 0; i < state.groups.size(); i++) labels[i] = state.groups.get(i).name + " [" + state.groups.get(i).idHex() + "]";
        }
        groupCombo.setModel(new DefaultComboBoxModel<>(labels));
    }

    private int repeatCount() {
        String s = String.valueOf(repeatCombo.getSelectedItem());
        if (s.startsWith("5")) return 5;
        if (s.startsWith("3")) return 3;
        return 1;
    }

    private AudioModem.RadioPreset selectedRadioPreset() {
        Object selected = radioPresetCombo.getSelectedItem();
        return selected instanceof AudioModem.RadioPreset ? (AudioModem.RadioPreset) selected : AudioModem.RadioPreset.STANDARD;
    }

    private void applySelectedRadioPresetDefaults() {
        AudioModem.RadioPreset preset = selectedRadioPreset();
        if (preset == AudioModem.RadioPreset.STANDARD || preset.receiveOnly) return;
        profileCombo.setSelectedItem(preset.defaultProfile);
        repeatCombo.setSelectedIndex(preset.defaultRepeats >= 5 ? 2 : preset.defaultRepeats >= 3 ? 1 : 0);
        setStatus("Radio preset: " + preset.label + " (" + AudioModem.transmitPresetSummary(preset) + ").");
    }

    private JPanel form(String[] labels, JComponent[] fields) {
        JPanel panel = darkPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        for (int i = 0; i < labels.length; i++) {
            c.gridy = i;
            c.gridx = 0;
            c.weightx = 0;
            panel.add(new JLabel(labels[i] + ":"), c);
            c.gridx = 1;
            c.weightx = 1;
            panel.add(fields[i], c);
        }
        return panel;
    }

    private void showTextDialog(String title, String text, boolean copy) {
        JTextArea area = new JTextArea(text, 10, 58);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        styleText(area);
        if (copy) copy(text);

        JPanel panel = darkPanel(new BorderLayout(8, 8));
        panel.add(new JScrollPane(area), BorderLayout.CENTER);
        if (text != null && text.startsWith("DDINV1")) {
            try {
                JLabel qr = new JLabel(new ImageIcon(qrImage(text, 280)));
                qr.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
                panel.add(qr, BorderLayout.SOUTH);
            } catch (Exception ex) {
                area.append("\n\nQR generation failed: " + ex.getMessage());
            }
        }
        JOptionPane.showMessageDialog(this, panel, title + (copy ? " (copied)" : ""), JOptionPane.INFORMATION_MESSAGE);
    }

    private static BufferedImage qrImage(String text, int size) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xffffff);
            }
        }
        return image;
    }

    private String chooseAndDecodeQrImage() throws Exception {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import DeadDrop QR image");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return null;
        return decodeQrImage(ImageIO.read(chooser.getSelectedFile()));
    }

    private static String decodeQrImage(BufferedImage image) throws Exception {
        if (image == null) throw new IllegalArgumentException("Could not read QR image.");
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)));
        String text = new MultiFormatReader().decode(bitmap).getText();
        if (text == null || !text.trim().startsWith("DDINV1.")) throw new IllegalArgumentException("QR is not a DeadDrop DDINV1 invite.");
        return text.trim();
    }

    private void appendLog(String kind, String text) {
        logArea.append("[" + timestamp() + "] " + kind + "  " + text + "\n\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void setStatus(String status) {
        statusLabel.setText(status);
    }

    private void showError(Exception ex) {
        ex.printStackTrace();
        JOptionPane.showMessageDialog(this, ex.getMessage(), "DeadDrop error", JOptionPane.ERROR_MESSAGE);
        setStatus("Error: " + ex.getMessage());
    }

    private void showInfo(String text) {
        JOptionPane.showMessageDialog(this, text, "DeadDrop", JOptionPane.INFORMATION_MESSAGE);
    }

    private void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String timestamp() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    static final class DesktopAudio {
        private static final AudioFormat FORMAT = new AudioFormat(AudioModem.SAMPLE_RATE, 16, 1, true, false);
        private final RingSamples ring = new RingSamples(AudioModem.SAMPLE_RATE * 45);
        private volatile boolean running = false;
        private volatile ListenSource listenSource = ListenSource.MICROPHONE;
        private volatile String inputDevice = "System default";
        private volatile String outputDevice = "System default";
        private Thread thread;
        private Process captureProcess;

        interface Listener {
            void status(String status);
            void payload(byte[] payload, AudioModem.Profile profile);
        }

        void setListenSource(ListenSource source) { listenSource = source == null ? ListenSource.MICROPHONE : source; }
        void setInputDevice(String label) { inputDevice = label == null ? "System default" : label; }
        void setOutputDevice(String label) { outputDevice = label == null ? "System default" : label; }

        static String[] deviceLabels(boolean input) {
            List<String> labels = new ArrayList<>();
            labels.add("System default");
            Class<?> lineClass = input ? TargetDataLine.class : SourceDataLine.class;
            DataLine.Info wanted = new DataLine.Info(lineClass, FORMAT);
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                try {
                    Mixer mixer = AudioSystem.getMixer(mi);
                    if (mixer.isLineSupported(wanted)) labels.add(mi.getName() + " — " + mi.getDescription());
                } catch (Exception ignored) {}
            }
            if (labels.size() == 1) labels.add("No compatible Java Sound " + (input ? "input" : "output") + " device found");
            return labels.toArray(new String[0]);
        }

        static String systemOutputStatus() {
            if (isWindows()) {
                Path helper = windowsWasapiHelperPath();
                return helper == null
                        ? "Windows WASAPI loopback helper not found in app folder/package."
                        : "Windows WASAPI loopback via " + helper.getFileName();
            }
            if (isLinux()) {
                String parec = findOnPath("parec");
                if (parec != null) return "Linux Pulse/PipeWire monitor via parec @DEFAULT_MONITOR@";
                String pwRecord = findOnPath("pw-record");
                if (pwRecord != null) return "Linux PipeWire monitor via pw-record @DEFAULT_MONITOR@";
                return "Linux system-output capture needs parec (pulseaudio-utils/pipewire-pulse) or pw-record on PATH.";
            }
            return "System-output capture is implemented for Windows and Linux desktop packages.";
        }

        void play(short[] samples, int repeats) throws Exception {
            SourceDataLine line = openOutputLine(outputDevice);
            try (SourceDataLine ignored = line) {
                byte[] bytes = shortsToBytes(samples);
                line.open(FORMAT, Math.min(bytes.length, AudioModem.SAMPLE_RATE * 4));
                line.start();
                for (int i = 0; i < repeats; i++) {
                    line.write(bytes, 0, bytes.length);
                    line.drain();
                    Thread.sleep(120);
                }
            }
        }

        void playTestTone() throws Exception {
            int samples = AudioModem.SAMPLE_RATE / 2;
            short[] tone = new short[samples];
            for (int i = 0; i < samples; i++) {
                double env = Math.min(1.0, Math.min(i / 1200.0, (samples - i) / 1200.0));
                tone[i] = (short)Math.round(Math.sin(2.0 * Math.PI * 880.0 * i / AudioModem.SAMPLE_RATE) * env * Short.MAX_VALUE * 0.45);
            }
            play(tone, 1);
        }

        AudioDiagnostics recordDiagnostics(int millis) throws Exception {
            if (listenSource == ListenSource.SYSTEM_OUTPUT) return recordSystemOutputDiagnostics(millis);
            TargetDataLine mic = openInputLine(inputDevice);
            try (TargetDataLine ignored = mic) {
                mic.open(FORMAT, AudioModem.SAMPLE_RATE);
                mic.start();
                int targetBytes = Math.max(1, millis) * AudioModem.SAMPLE_RATE * 2 / 1000;
                byte[] all = new byte[targetBytes];
                int off = 0;
                long deadline = System.currentTimeMillis() + millis + 1000L;
                while (off < all.length && System.currentTimeMillis() < deadline) {
                    int n = mic.read(all, off, all.length - off);
                    if (n <= 0) break;
                    off += n;
                }
                return AudioDiagnostics.analyze(bytesToShorts(all, off), off / 2);
            }
        }

        void start(Listener listener) throws Exception {
            if (running) return;
            if (listenSource == ListenSource.SYSTEM_OUTPUT) {
                Process process = startSystemOutputProcess();
                captureProcess = process;
                running = true;
                ring.clear();
                startStderrDrain(process, listener);
                thread = new Thread(() -> systemOutputCaptureLoop(process, listener), "deaddrop-desktop-system-output-listen");
                thread.start();
                return;
            }
            TargetDataLine probe = openInputLine(inputDevice);
            probe.close();
            running = true;
            ring.clear();
            thread = new Thread(() -> captureLoop(listener), "deaddrop-desktop-listen");
            thread.start();
        }

        void stop() {
            running = false;
            Process p = captureProcess;
            captureProcess = null;
            if (p != null) p.destroy();
            if (thread != null) thread.interrupt();
            thread = null;
        }

        private void captureLoop(Listener listener) {
            TargetDataLine mic = null;
            try {
                mic = openInputLine(inputDevice);
                mic.open(FORMAT, AudioModem.SAMPLE_RATE * 2);
                mic.start();
                byte[] bytes = new byte[4096];
                long lastDecode = 0;
                long lastLevel = 0;
                while (running && !Thread.currentThread().isInterrupted()) {
                    int n = mic.read(bytes, 0, bytes.length);
                    if (n <= 0) continue;
                    short[] shorts = bytesToShorts(bytes, n);
                    ring.append(shorts, shorts.length);
                    long now = System.currentTimeMillis();
                    if (now - lastLevel > 2000) {
                        lastLevel = now;
                        AudioDiagnostics diag = AudioDiagnostics.analyze(shorts, shorts.length);
                        listener.status("Listening — " + diag.shortStatus() + ". Audio not stored.");
                    }
                    if (now - lastDecode > 900) {
                        lastDecode = now;
                        decodeSnapshot(listener);
                    }
                }
            } catch (Exception e) {
                listener.status("Listen error: " + e.getMessage());
            } finally {
                if (mic != null) {
                    try { mic.stop(); } catch (Exception ignored) {}
                    mic.close();
                }
            }
        }

        private void systemOutputCaptureLoop(Process process, Listener listener) {
            try (BufferedInputStream in = new BufferedInputStream(process.getInputStream())) {
                listener.status("Listening to system output. Audio stays in memory and is not stored.");
                byte[] bytes = new byte[4096];
                byte[] carry = new byte[1];
                boolean hasCarry = false;
                long lastDecode = 0;
                long lastLevel = 0;
                while (running && !Thread.currentThread().isInterrupted()) {
                    int n = in.read(bytes);
                    if (n < 0) break;
                    if (n == 0) continue;
                    byte[] chunk = bytes;
                    int len = n;
                    if (hasCarry) {
                        byte[] merged = new byte[n + 1];
                        merged[0] = carry[0];
                        System.arraycopy(bytes, 0, merged, 1, n);
                        chunk = merged;
                        len = merged.length;
                        hasCarry = false;
                    }
                    if ((len & 1) == 1) {
                        carry[0] = chunk[len - 1];
                        hasCarry = true;
                        len--;
                    }
                    if (len <= 0) continue;
                    short[] shorts = bytesToShorts(chunk, len);
                    ring.append(shorts, shorts.length);
                    long now = System.currentTimeMillis();
                    if (now - lastLevel > 2000) {
                        lastLevel = now;
                        AudioDiagnostics diag = AudioDiagnostics.analyze(shorts, shorts.length);
                        listener.status("System output — " + diag.shortStatus() + ". Audio not stored.");
                    }
                    if (now - lastDecode > 900) {
                        lastDecode = now;
                        decodeSnapshot(listener);
                    }
                }
            } catch (Exception e) {
                if (running) listener.status("System output listen error: " + e.getMessage());
            } finally {
                running = false;
                if (captureProcess != null) {
                    captureProcess.destroy();
                    captureProcess = null;
                }
            }
        }

        private void decodeSnapshot(Listener listener) {
            short[] snapshot = ring.snapshot();
            AudioModem.DecodeResult result = AudioModem.tryDecodeAny(snapshot);
            if (result != null) {
                listener.payload(result.payload, result.profile);
                ring.clear();
            } else {
                AudioModem.DecodeReport report = AudioModem.analyzeDecode(snapshot);
                if (report.signalPresent || report.syncSeen || report.crcFailed) {
                    listener.status("Decode: " + report.shortStatus() + ". Audio not stored.");
                }
            }
        }

        private AudioDiagnostics recordSystemOutputDiagnostics(int millis) throws Exception {
            Process process = startSystemOutputProcess();
            try (BufferedInputStream in = new BufferedInputStream(process.getInputStream())) {
                int targetBytes = Math.max(1, millis) * AudioModem.SAMPLE_RATE * 2 / 1000;
                byte[] all = new byte[targetBytes];
                int off = 0;
                long deadline = System.currentTimeMillis() + millis + 1500L;
                while (off < all.length && System.currentTimeMillis() < deadline) {
                    int n = in.read(all, off, all.length - off);
                    if (n < 0) break;
                    off += n;
                }
                return AudioDiagnostics.analyze(bytesToShorts(all, off), off / 2);
            } finally {
                process.destroy();
            }
        }

        private static void startStderrDrain(Process process, Listener listener) {
            Thread t = new Thread(() -> {
                try (InputStreamReader reader = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
                    char[] buf = new char[512];
                    StringBuilder line = new StringBuilder();
                    int n;
                    while ((n = reader.read(buf)) >= 0) {
                        for (int i = 0; i < n; i++) {
                            char ch = buf[i];
                            if (ch == '\n' || ch == '\r') {
                                String msg = line.toString().trim();
                                line.setLength(0);
                                if (!msg.isEmpty()) listener.status("System output helper: " + msg);
                            } else if (line.length() < 240) {
                                line.append(ch);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }, "deaddrop-system-output-stderr");
            t.setDaemon(true);
            t.start();
        }

        private static Process startSystemOutputProcess() throws IOException {
            List<String> command = systemOutputCommand();
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            return pb.start();
        }

        private static List<String> systemOutputCommand() throws IOException {
            if (isWindows()) {
                Path helper = windowsWasapiHelperPath();
                if (helper == null) throw new IOException("Windows WASAPI loopback helper not found. Reinstall the Windows desktop package or use the ZIP package helper script.");
                List<String> cmd = new ArrayList<>();
                cmd.add("powershell.exe");
                cmd.add("-NoProfile");
                cmd.add("-ExecutionPolicy");
                cmd.add("Bypass");
                cmd.add("-File");
                cmd.add(helper.toString());
                return cmd;
            }
            if (isLinux()) {
                String parec = findOnPath("parec");
                if (parec != null) {
                    return Arrays.asList(parec, "--raw", "--format=s16le", "--rate=" + AudioModem.SAMPLE_RATE, "--channels=1", "--device=@DEFAULT_MONITOR@");
                }
                String pwRecord = findOnPath("pw-record");
                if (pwRecord != null) {
                    return Arrays.asList(pwRecord, "--target", "@DEFAULT_MONITOR@", "--rate", String.valueOf(AudioModem.SAMPLE_RATE), "--channels", "1", "--format", "s16", "-");
                }
                throw new IOException("Linux system-output capture needs parec (pulseaudio-utils/pipewire-pulse) or pw-record on PATH.");
            }
            throw new IOException("System-output capture is implemented for Windows and Linux only.");
        }

        private static Path windowsWasapiHelperPath() {
            String helper = "deaddrop-wasapi-loopback.ps1";
            List<Path> candidates = new ArrayList<>();
            candidates.add(Paths.get(System.getProperty("user.dir", ".")).resolve(helper));
            try {
                Path code = Paths.get(DeadDropDesktopGui.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                Path parent = Files.isDirectory(code) ? code : code.getParent();
                if (parent != null) {
                    candidates.add(parent.resolve(helper));
                    if (parent.getParent() != null) candidates.add(parent.getParent().resolve(helper));
                }
            } catch (Exception ignored) {}
            candidates.add(Paths.get("packaging", "windows", helper));
            for (Path candidate : candidates) {
                try {
                    if (candidate != null && Files.exists(candidate)) return candidate.toAbsolutePath().normalize();
                } catch (Exception ignored) {}
            }
            return null;
        }

        private static String findOnPath(String name) {
            String path = System.getenv("PATH");
            if (path == null || path.isEmpty()) return null;
            for (String dir : path.split(File.pathSeparator)) {
                if (dir == null || dir.isEmpty()) continue;
                Path candidate = Paths.get(dir, name);
                if (Files.isExecutable(candidate)) return candidate.toString();
            }
            return null;
        }

        private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.US).contains("win"); }
        private static boolean isLinux() { return System.getProperty("os.name", "").toLowerCase(Locale.US).contains("linux"); }

        private static SourceDataLine openOutputLine(String label) throws Exception {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, FORMAT);
            Mixer mixer = mixerForLabel(label, false);
            return mixer == null ? (SourceDataLine) AudioSystem.getLine(info) : (SourceDataLine) mixer.getLine(info);
        }

        private static TargetDataLine openInputLine(String label) throws Exception {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            Mixer mixer = mixerForLabel(label, true);
            return mixer == null ? (TargetDataLine) AudioSystem.getLine(info) : (TargetDataLine) mixer.getLine(info);
        }

        private static Mixer mixerForLabel(String label, boolean input) {
            if (label == null || label.startsWith("System default") || label.startsWith("No compatible")) return null;
            String name = label.contains(" — ") ? label.substring(0, label.indexOf(" — ")) : label;
            DataLine.Info wanted = new DataLine.Info(input ? TargetDataLine.class : SourceDataLine.class, FORMAT);
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                if (mi.getName().equals(name)) {
                    try {
                        Mixer mixer = AudioSystem.getMixer(mi);
                        if (mixer.isLineSupported(wanted)) return mixer;
                    } catch (Exception ignored) {}
                }
            }
            return null;
        }

        private static byte[] shortsToBytes(short[] samples) {
            byte[] bytes = new byte[samples.length * 2];
            for (int i = 0; i < samples.length; i++) {
                bytes[i * 2] = (byte) (samples[i] & 0xff);
                bytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xff);
            }
            return bytes;
        }

        private static short[] bytesToShorts(byte[] bytes, int n) {
            int count = n / 2;
            short[] out = new short[count];
            for (int i = 0; i < count; i++) {
                int lo = bytes[i * 2] & 0xff;
                int hi = bytes[i * 2 + 1];
                out[i] = (short) ((hi << 8) | lo);
            }
            return out;
        }
    }

    static final class RingSamples {
        private final short[] data;
        private int write = 0;
        private boolean full = false;
        RingSamples(int capacity) { data = new short[capacity]; }
        synchronized void append(short[] src, int n) {
            for (int i = 0; i < n; i++) {
                data[write++] = src[i];
                if (write >= data.length) { write = 0; full = true; }
            }
        }
        synchronized short[] snapshot() {
            int size = full ? data.length : write;
            short[] out = new short[size];
            if (!full) {
                System.arraycopy(data, 0, out, 0, size);
            } else {
                int tail = data.length - write;
                System.arraycopy(data, write, out, 0, tail);
                System.arraycopy(data, 0, out, tail, write);
            }
            return out;
        }
        synchronized void clear() { write = 0; full = false; }
    }

    static final class DesktopState {
        private static final SecureRandom RNG = new SecureRandom();
        private static final int PBKDF2_ITERATIONS = 180_000;
        private static final int GCM_TAG_BITS = 128;
        private final Path vaultPath;
        private final char[] passphrase;
        private final boolean persistent;
        final List<DeadDropCrypto.Group> groups = new ArrayList<>();
        final Map<String, String> knownSenders = new HashMap<>();
        final Map<String, Long> replayCache = new HashMap<>();
        KeyPair identity;
        String handle = "DeadDrop";
        int ttlHours = 24;
        int maxChars = 280;

        private DesktopState(Path vaultPath, char[] passphrase, boolean persistent) {
            this.vaultPath = vaultPath;
            this.passphrase = passphrase;
            this.persistent = persistent;
        }

        static DesktopState sessionOnly() throws Exception {
            DesktopState state = new DesktopState(defaultVaultPath(), new char[0], false);
            state.ensureIdentity();
            return state;
        }

        static DesktopState openWithPassphrase(char[] passphrase) throws Exception {
            char[] pass = passphrase == null ? new char[0] : passphrase;
            DesktopState state = new DesktopState(defaultVaultPath(), pass, pass.length > 0);
            if (state.persistent) state.load();
            state.ensureIdentity();
            if (state.persistent) state.save();
            return state;
        }

        static DesktopState openWithPrompt(java.awt.Component parent) throws Exception {
            Path path = defaultVaultPath();
            JPasswordField pf = new JPasswordField(28);
            JLabel label = new JLabel("Choose a local vault passphrase. Leave blank for session-only testing; there is no default passphrase.");
            JLabel pathLabel = new JLabel("Vault file: " + path);
            JPanel p = new JPanel(new BorderLayout(6, 6));
            JPanel textPanel = new JPanel(new BorderLayout(4, 4));
            textPanel.add(label, BorderLayout.NORTH);
            textPanel.add(pathLabel, BorderLayout.SOUTH);
            p.add(textPanel, BorderLayout.NORTH);
            p.add(pf, BorderLayout.CENTER);
            int res = JOptionPane.showConfirmDialog(parent, p, "Open DeadDrop Desktop vault", JOptionPane.OK_CANCEL_OPTION);
            if (res != JOptionPane.OK_OPTION) throw new IllegalStateException("Startup cancelled.");
            char[] pass = pf.getPassword();
            DesktopState state = new DesktopState(path, pass, pass.length > 0);
            if (state.persistent) state.load();
            state.ensureIdentity();
            if (state.persistent) state.save();
            return state;
        }

        static Path defaultVaultPath() {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            Path config = (xdg == null || xdg.trim().isEmpty())
                    ? Paths.get(System.getProperty("user.home"), ".config")
                    : Paths.get(xdg);
            return config.resolve("deaddrop-desktop").resolve("vault.ddv");
        }

        static Path legacyVaultPath() {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            Path config = (xdg == null || xdg.trim().isEmpty())
                    ? Paths.get(System.getProperty("user.home"), ".config")
                    : Paths.get(xdg);
            return config.resolve("deaddrop-linux").resolve("vault.ddv");
        }

        void load() throws Exception {
            Path source = Files.exists(vaultPath) ? vaultPath : legacyVaultPath();
            if (!Files.exists(source)) return;
            String raw = Files.readString(source, StandardCharsets.UTF_8).trim();
            if (!raw.startsWith("DDLV1.")) throw new IllegalArgumentException("Unsupported vault format.");
            byte[] packed = DeadDropCrypto.b64d(raw.substring("DDLV1.".length()));
            if (packed.length < 16 + 12 + 16) throw new IllegalArgumentException("Vault is too short.");
            byte[] salt = Arrays.copyOfRange(packed, 0, 16);
            byte[] nonce = Arrays.copyOfRange(packed, 16, 28);
            byte[] ciphertext = Arrays.copyOfRange(packed, 28, packed.length);
            byte[] plain = aes(false, derive(passphrase, salt), nonce, ciphertext);
            Properties props = new Properties();
            props.load(new StringReader(new String(plain, StandardCharsets.UTF_8)));
            handle = props.getProperty("handle", handle);
            ttlHours = parseInt(props.getProperty("ttlHours"), ttlHours);
            maxChars = parseInt(props.getProperty("maxChars"), maxChars);
            groups.clear();
            groups.addAll(DeadDropCrypto.deserializeGroups(props.getProperty("groups", "")));
            knownSenders.clear();
            knownSenders.putAll(deserializeStringMap(props.getProperty("knownSenders", "")));
            replayCache.clear();
            replayCache.putAll(deserializeLongMap(props.getProperty("replayCache", "")));
            pruneReplay();
            String priv = props.getProperty("privateKey", "");
            String pub = props.getProperty("publicKey", "");
            if (!priv.isEmpty() && !pub.isEmpty()) identity = decodeIdentity(priv, pub);
        }

        void save() throws Exception {
            if (!persistent) return;
            ensureIdentity();
            Properties props = new Properties();
            props.setProperty("handle", handle);
            props.setProperty("ttlHours", String.valueOf(ttlHours));
            props.setProperty("maxChars", String.valueOf(maxChars));
            props.setProperty("groups", DeadDropCrypto.serializeGroups(groups));
            props.setProperty("knownSenders", serializeStringMap(knownSenders));
            props.setProperty("replayCache", serializeLongMap(replayCache));
            props.setProperty("privateKey", DeadDropCrypto.b64(identity.getPrivate().getEncoded()));
            props.setProperty("publicKey", DeadDropCrypto.b64(identity.getPublic().getEncoded()));
            StringWriter sw = new StringWriter();
            props.store(sw, "DeadDrop Desktop encrypted vault");
            byte[] salt = random(16);
            byte[] nonce = random(12);
            byte[] cipher = aes(true, derive(passphrase, salt), nonce, sw.toString().getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(salt);
            out.write(nonce);
            out.write(cipher);
            Files.createDirectories(vaultPath.getParent());
            Files.writeString(vaultPath, "DDLV1." + DeadDropCrypto.b64(out.toByteArray()) + "\n", StandardCharsets.UTF_8);
        }

        void wipe() throws Exception {
            groups.clear();
            knownSenders.clear();
            replayCache.clear();
            identity = generateIdentity();
            handle = "DeadDrop";
            ttlHours = 24;
            maxChars = 280;
            if (persistent) {
                Files.deleteIfExists(vaultPath);
                Files.deleteIfExists(legacyVaultPath());
            }
        }

        boolean rememberReplay(DeadDropCrypto.ReceivedMessage msg) throws Exception {
            pruneReplay();
            String key = msg.dedupeKey();
            if (replayCache.containsKey(key)) return false;
            replayCache.put(key, Math.max(msg.expiresAtMillis, System.currentTimeMillis() + 60_000L));
            while (replayCache.size() > 512) replayCache.remove(replayCache.keySet().iterator().next());
            save();
            return true;
        }

        void pruneReplay() {
            long now = System.currentTimeMillis();
            replayCache.entrySet().removeIf(e -> e.getValue() <= now);
        }

        void ensureIdentity() throws Exception {
            if (identity == null) identity = generateIdentity();
        }

        static KeyPair generateIdentity() throws Exception {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            return kpg.generateKeyPair();
        }

        private static KeyPair decodeIdentity(String privB64, String pubB64) throws Exception {
            KeyFactory kf = KeyFactory.getInstance("EC");
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(DeadDropCrypto.b64d(privB64)));
            PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(DeadDropCrypto.b64d(pubB64)));
            return new KeyPair(pub, priv);
        }

        private static byte[] derive(char[] pass, byte[] salt) throws Exception {
            PBEKeySpec spec = new PBEKeySpec(pass, salt, PBKDF2_ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        }

        private static byte[] aes(boolean encrypt, byte[] key, byte[] nonce, byte[] input) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return cipher.doFinal(input);
        }

        private static byte[] random(int len) {
            byte[] b = new byte[len];
            RNG.nextBytes(b);
            return b;
        }

        private static String serializeStringMap(Map<String, String> map) {
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, String> e : map.entrySet()) {
                out.append(DeadDropCrypto.b64(e.getKey().getBytes(StandardCharsets.UTF_8))).append('|')
                        .append(DeadDropCrypto.b64(e.getValue().getBytes(StandardCharsets.UTF_8))).append('\n');
            }
            return out.toString();
        }

        private static Map<String, String> deserializeStringMap(String raw) {
            Map<String, String> out = new HashMap<>();
            if (raw == null || raw.trim().isEmpty()) return out;
            for (String line : raw.split("\\n")) {
                try {
                    String[] p = line.split("\\|", 2);
                    if (p.length == 2) out.put(new String(DeadDropCrypto.b64d(p[0]), StandardCharsets.UTF_8), new String(DeadDropCrypto.b64d(p[1]), StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            }
            return out;
        }

        private static String serializeLongMap(Map<String, Long> map) {
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, Long> e : map.entrySet()) {
                out.append(DeadDropCrypto.b64(e.getKey().getBytes(StandardCharsets.UTF_8))).append('|').append(e.getValue()).append('\n');
            }
            return out.toString();
        }

        private static Map<String, Long> deserializeLongMap(String raw) {
            Map<String, Long> out = new HashMap<>();
            if (raw == null || raw.trim().isEmpty()) return out;
            for (String line : raw.split("\\n")) {
                try {
                    String[] p = line.split("\\|", 2);
                    if (p.length == 2) out.put(new String(DeadDropCrypto.b64d(p[0]), StandardCharsets.UTF_8), Long.parseLong(p[1]));
                } catch (Exception ignored) {}
            }
            return out;
        }

        private static int parseInt(String s, int def) {
            try { return Integer.parseInt(s); } catch (Exception e) { return def; }
        }
    }
}
