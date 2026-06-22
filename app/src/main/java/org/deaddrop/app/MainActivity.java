package org.deaddrop.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioPlaybackCaptureConfiguration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.security.KeyPair;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    static final String PREFS = "deaddrop_v1";
    private static final int REQ_RECORD_AUDIO = 1001;
    private static final int REQ_QR_IMAGE = 2001;
    private static final int REQ_QR_PHOTO = 2002;
    private static final int REQ_MEDIA_PROJECTION = 3001;
    private static final int LISTEN_SOURCE_MIC = 0;
    private static final int LISTEN_SOURCE_PLAYBACK = 1;
    private static final String ACTION_USB_SDR_PERMISSION = "org.deaddrop.app.USB_SDR_PERMISSION";

    private static final int DARK_BG = Color.rgb(8, 13, 18);
    private static final int DARK_PANEL = Color.rgb(18, 27, 35);
    private static final int DARK_CARD = Color.rgb(24, 36, 46);
    private static final int DARK_TEXT = Color.rgb(232, 240, 244);
    private static final int DARK_MUTED = Color.rgb(159, 178, 190);
    private static final int DARK_ACCENT = Color.rgb(42, 157, 143);
    private static final int DARK_ACCENT_2 = Color.rgb(101, 199, 177);
    private static final int DARK_WARN = Color.rgb(229, 184, 102);
    private static final int LIGHT_BG = Color.rgb(246, 248, 250);
    private static final int LIGHT_PANEL = Color.rgb(238, 242, 244);
    private static final int LIGHT_CARD = Color.rgb(255, 255, 255);
    private static final int LIGHT_TEXT = Color.rgb(30, 37, 43);
    private static final int LIGHT_MUTED = Color.rgb(86, 99, 108);
    private static final int LIGHT_ACCENT = Color.rgb(31, 132, 118);
    private static final int LIGHT_ACCENT_2 = Color.rgb(20, 99, 89);
    private static final int LIGHT_WARN = Color.rgb(171, 112, 32);

    private SharedPreferences prefs;
    private final List<DeadDropCrypto.Group> groups = new ArrayList<>();
    private int activeGroupIndex = 0;
    private final Set<String> seen = new HashSet<>();

    private LinearLayout logLayout;
    private TextView statusView;
    private TextView listeningCard;
    private TextView audioCard;
    private TextView packetCard;
    private TextView profileCard;
    private TextView groupView;
    private EditText messageInput;
    private EditText pendingInviteInput;
    private Spinner groupSpinner;
    private Spinner profileSpinner;
    private Spinner repeatSpinner;
    private Spinner modeSpinner;
    private Spinner radioPresetSpinner;
    private Spinner listenSourceSpinner;
    private TextView usbSdrStatus;
    private Button listenButton;

    private volatile boolean listening = false;
    private int pendingMicAction = 0; // 1 = app mic, 2 = background mic, 3 = app playback, 4 = background playback
    private int pendingProjectionAction = 0; // 1 = app playback, 2 = background playback
    private long pendingBackgroundListenDurationMs = 0L;
    private String pendingBackgroundListenLabel = "";
    private Thread listenThread;
    private volatile AudioRecord activeListenRecord;
    private MediaProjection activeMediaProjection;
    private MediaProjection.Callback activeProjectionCallback;
    private final RingSamples ring = new RingSamples(AudioModem.SAMPLE_RATE * 45);
    private final BroadcastReceiver usbSdrPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_SDR_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (device == null) {
                setUsbSdrStatus("USB SDR: no device in permission reply.");
            } else if (granted) {
                setUsbSdrStatus("USB SDR: authorized " + usbDeviceLabel(device) + ". Direct demodulation is staged; use SDR receive preset for receive workflow.");
                setPacketStatus("USB SDR authorized — direct Android demod staged");
            } else {
                setUsbSdrStatus("USB SDR: permission denied for " + usbDeviceLabel(device) + ".");
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        registerUsbSdrReceiver();
        loadGroups();
        loadSeenFromLog();
        buildUi();
        pruneExpiredLog();
        refreshGroupsUi();
        refreshLogUi();
        setListeningStatus("Listen: off");
        setAudioStatus("Source: off");
        setPacketStatus("Packet: idle");
        setStatus("Ready. Audio capture is off until you start listening.");
        showFirstRunGuideIfNeeded();
    }

    @Override protected void onResume() {
        super.onResume();
        if (logLayout != null) refreshLogUi();
    }

    @Override protected void onDestroy() {
        stopListening();
        try { unregisterReceiver(usbSdrPermissionReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            int action = pendingMicAction;
            pendingMicAction = 0;
            if (action == 4) requestPlaybackProjection(2);
            else if (action == 3) requestPlaybackProjection(1);
            else if (action == 2) startBackgroundListen();
            else if (action == 1) startListeningIfPossible();
            else setStatus("Audio permission granted. Tap Start listening when ready.");
        } else {
            pendingMicAction = 0;
            setStatus("Audio permission denied. Transmit still works; receive/listen does not.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MEDIA_PROJECTION) {
            int action = pendingProjectionAction;
            pendingProjectionAction = 0;
            if (resultCode != RESULT_OK || data == null) {
                setAudioStatus("Playback: permission cancelled");
                setStatus("Device playback capture was cancelled. Microphone listening still works.");
                return;
            }
            if (action == 2) startBackgroundListenForSource(pendingBackgroundListenDurationMs, pendingBackgroundListenLabel, LISTEN_SOURCE_PLAYBACK, resultCode, data);
            else startPlaybackListening(resultCode, data);
        } else if (requestCode == REQ_QR_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri uri = data.getData();
                String invite = QrInvite.decodeFromUri(this, uri);
                acceptDecodedInvite(invite, "QR image decoded.");
            } catch (Exception e) {
                toast("QR decode failed: " + e.getMessage());
            }
        } else if (requestCode == REQ_QR_PHOTO && resultCode == RESULT_OK && data != null) {
            try {
                Bundle extras = data.getExtras();
                Bitmap photo = extras == null ? null : (Bitmap) extras.get("data");
                if (photo == null) throw new IllegalArgumentException("Camera did not return an image.");
                String invite = QrInvite.decodeFromBitmap(photo);
                acceptDecodedInvite(invite, "QR photo decoded.");
            } catch (Exception e) {
                toast("QR photo decode failed: " + e.getMessage());
            }
        }
    }

    private void acceptDecodedInvite(String invite, String status) {
        if (pendingInviteInput != null) pendingInviteInput.setText(invite);
        copy(invite);
        setStatus(status + " Invite copied/pasted.");
    }

    private boolean darkMode() { return prefs == null || prefs.getBoolean("dark_mode", true); }
    private int colorBg() { return darkMode() ? DARK_BG : LIGHT_BG; }
    private int colorPanel() { return darkMode() ? DARK_PANEL : LIGHT_PANEL; }
    private int colorCard() { return darkMode() ? DARK_CARD : LIGHT_CARD; }
    private int colorText() { return darkMode() ? DARK_TEXT : LIGHT_TEXT; }
    private int colorMuted() { return darkMode() ? DARK_MUTED : LIGHT_MUTED; }
    private int colorAccent() { return darkMode() ? DARK_ACCENT : LIGHT_ACCENT; }
    private int colorAccent2() { return darkMode() ? DARK_ACCENT_2 : LIGHT_ACCENT_2; }
    private int colorWarn() { return darkMode() ? DARK_WARN : LIGHT_WARN; }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), 0);
        root.setBackgroundColor(colorBg());
        setContentView(root);
        applyTopInsetPadding(root, dp(8));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(colorBg());
            getWindow().setNavigationBarColor(colorBg());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(darkMode() ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        ScrollView mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(false);
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(0, dp(6), 0, dp(6));
        main.setBackgroundColor(colorBg());
        mainScroll.addView(main, new ScrollView.LayoutParams(-1, -2));
        root.addView(mainScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout controlsCard = containerCard();
        LinearLayout controlsContent = new LinearLayout(this);
        controlsContent.setOrientation(LinearLayout.VERTICAL);
        TextView controlsHeader = collapsibleHeader("Status & setup", controlsContent);
        controlsCard.addView(controlsHeader, new LinearLayout.LayoutParams(-1, -2));
        controlsCard.addView(controlsContent, new LinearLayout.LayoutParams(-1, -2));
        main.addView(controlsCard, fullWidthCardParams());

        buildStatusCards(controlsContent);

        groupView = new TextView(this);
        groupView.setTextSize(14);
        groupView.setTextColor(colorMuted());
        groupView.setPadding(0, 0, 0, dp(4));
        controlsContent.addView(groupView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row1 = row();
        Button create = button("Create");
        create.setOnClickListener(v -> showCreateGroupDialog());
        Button invite = button("Invite/QR");
        invite.setOnClickListener(v -> showInviteDialog());
        Button join = button("Join");
        join.setOnClickListener(v -> showJoinDialog());
        Button hop = button("Hop assist");
        hop.setOnClickListener(v -> showManualHopAssistDialog());
        row1.addView(create, weight()); row1.addView(invite, weight()); row1.addView(join, weight()); row1.addView(hop, weight());
        controlsContent.addView(row1);

        LinearLayout rowGroup = row();
        rowGroup.addView(label("Group:"));
        groupSpinner = new Spinner(this);
        groupSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                activeGroupIndex = Math.max(0, position);
                prefs.edit().putInt("active_group", activeGroupIndex).apply();
                updateGroupLabel();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        rowGroup.addView(groupSpinner, weight());
        controlsContent.addView(rowGroup);

        LinearLayout rowProfile = row();
        rowProfile.addView(label("Profile:"));
        profileSpinner = new Spinner(this);
        profileSpinner.setAdapter(spinnerAdapter(AudioModem.Profile.values()));
        profileSpinner.setSelection(AudioModem.Profile.NORMAL.ordinal());
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { updateProfileCard(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        rowProfile.addView(profileSpinner, weight());
        rowProfile.addView(label("Repeat:"));
        repeatSpinner = new Spinner(this);
        repeatSpinner.setAdapter(spinnerAdapter(new String[]{"1x", "3x", "5x"}));
        rowProfile.addView(repeatSpinner, weight());
        controlsContent.addView(rowProfile);

        LinearLayout rowMode = row();
        rowMode.addView(label("Mode:"));
        modeSpinner = new Spinner(this);
        modeSpinner.setAdapter(spinnerAdapter(new String[]{"Anonymous encrypted", "Signed handle encrypted", "Signed plaintext"}));
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { updateProfileCard(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        rowMode.addView(modeSpinner, new LinearLayout.LayoutParams(0, -2, 2));
        controlsContent.addView(rowMode);

        LinearLayout rowRadio = row();
        rowRadio.addView(label("Radio:"));
        radioPresetSpinner = new Spinner(this);
        radioPresetSpinner.setAdapter(spinnerAdapter(AudioModem.RadioPreset.values()));
        radioPresetSpinner.setSelection(clamp(prefs.getInt("radio_preset", AudioModem.RadioPreset.STANDARD.ordinal()),
                0, AudioModem.RadioPreset.values().length - 1));
        radioPresetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putInt("radio_preset", position).apply();
                applyRadioPresetDefaults(selectedRadioPreset());
                updateProfileCard();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        rowRadio.addView(radioPresetSpinner, new LinearLayout.LayoutParams(0, -2, 2));
        controlsContent.addView(rowRadio);

        LinearLayout rowListenSource = row();
        rowListenSource.addView(label("Listen source:"));
        listenSourceSpinner = new Spinner(this);
        listenSourceSpinner.setAdapter(spinnerAdapter(new String[]{"Microphone / acoustic", "Device playback / app audio"}));
        listenSourceSpinner.setSelection(clamp(prefs.getInt("listen_source", LISTEN_SOURCE_MIC), 0, 1));
        listenSourceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putInt("listen_source", position).apply();
                if (!listening) setAudioStatus(position == LISTEN_SOURCE_PLAYBACK ? "Playback: ready" : "Mic: ready");
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        rowListenSource.addView(listenSourceSpinner, new LinearLayout.LayoutParams(0, -2, 2));
        controlsContent.addView(rowListenSource);

        LinearLayout rowUsbSdr = row();
        rowUsbSdr.addView(label("USB SDR:"));
        Button usbButton = button("Scan / authorize");
        usbButton.setOnClickListener(v -> scanUsbSdrReceivers(true));
        rowUsbSdr.addView(usbButton, weight());
        controlsContent.addView(rowUsbSdr);
        usbSdrStatus = mutedText("USB SDR: not scanned");
        controlsContent.addView(usbSdrStatus, new LinearLayout.LayoutParams(-1, -2));
        scanUsbSdrReceivers(false);

        logLayout = new LinearLayout(this);
        logLayout.setOrientation(LinearLayout.VERTICAL);
        logLayout.setPadding(0, dp(8), 0, dp(8));
        main.addView(logLayout, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setBackgroundColor(colorBg());
        composer.setPadding(0, dp(6), 0, dp(24));

        messageInput = new EditText(this);
        messageInput.setHint("Type a short DeadDrop message");
        messageInput.setMinLines(1);
        messageInput.setMaxLines(3);
        messageInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) view.postDelayed(() -> {
                updateKeyboardAvoidance(root, mainScroll, composer, dp(6), dp(24));
                composer.requestRectangleOnScreen(new Rect(0, 0, composer.getWidth(), composer.getHeight()), false);
            }, 250);
        });
        styleInput(messageInput);
        composer.addView(messageInput, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row2 = row();
        row2.setPadding(0, dp(6), 0, 0);
        Button transmit = button("Transmit");
        transmit.setOnClickListener(v -> transmitMessage());
        listenButton = button("Start listening");
        listenButton.setOnClickListener(v -> { if (listening) stopListening(); else startListeningIfPossible(); });
        Button settings = button("Settings");
        settings.setOnClickListener(v -> showSettingsDialog());
        row2.addView(transmit, weight()); row2.addView(listenButton, weight()); row2.addView(settings, weight());
        composer.addView(row2, new LinearLayout.LayoutParams(-1, -2));
        root.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        installKeyboardAvoidance(root, mainScroll, composer, dp(6), dp(24));

        statusView = null; // Status is now consolidated into the top cards; avoid a redundant clipped bottom line.
        setListeningStatus("Listen: off");
        setAudioStatus("Source: off");
        setPacketStatus("Packet: idle");
        updateProfileCard();
    }

    private void buildStatusCards(LinearLayout root) {
        listeningCard = statusCard("Listen: off");
        audioCard = statusCard("Mic: waiting");
        packetCard = statusCard("Packet: none");
        profileCard = statusCard("Normal / Anonymous");

        LinearLayout rowA = row();
        rowA.addView(listeningCard, weight());
        rowA.addView(audioCard, weight());
        root.addView(rowA);

        LinearLayout rowB = row();
        rowB.addView(packetCard, weight());
        rowB.addView(profileCard, weight());
        root.addView(rowB);
    }

    private TextView statusCard(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(11);
        v.setTextColor(colorText());
        v.setPadding(dp(7), dp(4), dp(7), dp(4));
        v.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(9));
        bg.setColor(colorCard());
        bg.setStroke(dp(1), colorAccent());
        v.setBackground(bg);
        return v;
    }

    private LinearLayout containerCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(9), dp(8), dp(9), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(colorPanel());
        bg.setStroke(dp(1), darkMode() ? Color.rgb(45, 64, 74) : Color.rgb(205, 214, 220));
        card.setBackground(bg);
        return card;
    }

    private TextView collapsibleHeader(String title, LinearLayout content) {
        TextView header = new TextView(this);
        header.setText(title + "  ▾");
        header.setTextColor(colorText());
        header.setTextSize(14);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), 0, dp(2), dp(6));
        header.setOnClickListener(v -> {
            boolean expand = content.getVisibility() != View.VISIBLE;
            content.setVisibility(expand ? View.VISIBLE : View.GONE);
            header.setText(title + (expand ? "  ▾" : "  ▸"));
        });
        return header;
    }

    private LinearLayout.LayoutParams fullWidthCardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(3));
        return lp;
    }

    private void showFirstRunGuideIfNeeded() {
        if (prefs.getBoolean("first_run_guide_seen", false)) return;
        prefs.edit().putBoolean("first_run_guide_seen", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("DeadDrop field setup")
                .setMessage("Quick start:\n\n1. Create or join a group with a DDINV1 invite + second factor.\n2. Keep both devices open/listening.\n3. Use Normal first, then Robust+FEC or Ultra/Noisy if decode fails.\n4. Use Audio check if mic/output level is quiet or clipping.\n5. Listen source can be microphone/acoustic or device playback/app audio.\n\nDeadDrop has no Internet permission and does not store audio.")
                .setPositiveButton("Create group", (d, which) -> showCreateGroupDialog())
                .setNeutralButton("Join group", (d, which) -> showJoinDialog())
                .setNegativeButton("Later", null)
                .show();
    }

    private void showCreateGroupDialog() {
        LinearLayout box = dialogBox();
        EditText name = input("Group name", false);
        EditText factor = input("Second factor for invites, min 6 chars", true);
        box.addView(name); box.addView(factor);
        new AlertDialog.Builder(this)
                .setTitle("Create DeadDrop group")
                .setView(box)
                .setPositiveButton("Create", (d, which) -> {
                    try {
                        DeadDropCrypto.Group g = DeadDropCrypto.createGroup(name.getText().toString());
                        groups.add(g); activeGroupIndex = groups.size() - 1; saveGroups(); refreshGroupsUi();
                        String invite = DeadDropCrypto.exportInvite(g, factor.getText().toString());
                        showInviteResult("Group created", invite);
                    } catch (Exception e) { toast(e.getMessage()); }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void showInviteDialog() {
        DeadDropCrypto.Group g = activeGroup();
        if (g == null) { toast("Create or join a group first."); return; }
        EditText factor = input("Second factor, min 6 chars", true);
        new AlertDialog.Builder(this)
                .setTitle("Export invite for " + g.name)
                .setView(factor)
                .setPositiveButton("Create invite", (d, which) -> {
                    try {
                        String invite = DeadDropCrypto.exportInvite(g, factor.getText().toString());
                        showInviteResult("Invite copied", invite);
                    } catch (Exception e) { toast(e.getMessage()); }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void showJoinDialog() {
        LinearLayout box = dialogBox();
        EditText invite = input("DDINV1 invite", false);
        pendingInviteInput = invite;
        invite.setMinLines(4);
        EditText factor = input("Second factor", true);
        LinearLayout helpers = row();
        Button paste = button("Paste");
        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (text != null) invite.setText(text.toString());
            }
        });
        Button importQr = button("Import QR image");
        importQr.setOnClickListener(v -> importQrImage(invite));
        Button photoQr = button("Take QR photo");
        photoQr.setOnClickListener(v -> takeQrPhoto(invite));
        helpers.addView(paste, weight()); helpers.addView(importQr, weight()); helpers.addView(photoQr, weight());
        box.addView(invite); box.addView(helpers); box.addView(factor);
        new AlertDialog.Builder(this)
                .setTitle("Join DeadDrop group")
                .setView(box)
                .setPositiveButton("Join", (d, which) -> {
                    try {
                        DeadDropCrypto.Group g = DeadDropCrypto.importInvite(invite.getText().toString(), factor.getText().toString());
                        groups.add(g); activeGroupIndex = groups.size() - 1; saveGroups(); refreshGroupsUi();
                        toast("Joined " + g.name);
                    } catch (Exception e) { toast(e.getMessage()); }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void importQrImage(EditText invite) {
        pendingInviteInput = invite;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, REQ_QR_IMAGE);
    }

    private void takeQrPhoto(EditText invite) {
        pendingInviteInput = invite;
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        try {
            startActivityForResult(intent, REQ_QR_PHOTO);
        } catch (Exception e) {
            toast("No camera app available.");
        }
    }

    private void showSettingsDialog() {
        LinearLayout box = dialogBox();
        EditText handle = input("Optional sender label for signed modes", false);
        handle.setText(handle());
        EditText ttl = input("Auto-expire hours", false);
        ttl.setInputType(InputType.TYPE_CLASS_NUMBER);
        ttl.setText(String.valueOf(ttlHours()));
        EditText max = input("Max message characters", false);
        max.setInputType(InputType.TYPE_CLASS_NUMBER);
        max.setText(String.valueOf(maxChars()));
        CheckBox dark = new CheckBox(this);
        dark.setText("Dark mode");
        dark.setTextColor(colorText());
        dark.setChecked(darkMode());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) dark.setButtonTintList(ColorStateList.valueOf(colorAccent()));
        Button clearLog = button("Clear local text log");
        clearLog.setOnClickListener(v -> {
            DeadDropLog.clear(prefs);
            seen.clear();
            refreshLogUi();
            setStatus("Local text log cleared. Replay cache kept so duplicate packets stay suppressed.");
        });
        Button known = button("Sender safety");
        known.setOnClickListener(v -> showKnownSendersDialog());
        Button clearReplay = button("Clear replay cache");
        clearReplay.setOnClickListener(v -> {
            DeadDropReplayCache.clear(prefs);
            seen.clear();
            setPacketStatus("Replay cache cleared");
            setStatus("Replay cache cleared. Duplicate audio packets may show again until heard once.");
        });
        Button wipe = button("PANIC WIPE groups + logs");
        wipe.setOnClickListener(v -> confirmPanicWipe());
        box.addView(handle); box.addView(ttl); box.addView(max); box.addView(dark); box.addView(known); box.addView(clearLog); box.addView(clearReplay); box.addView(wipe);
        new AlertDialog.Builder(this)
                .setTitle("DeadDrop settings")
                .setView(box)
                .setPositiveButton("Save", (d, which) -> {
                    int t = clamp(parseInt(ttl.getText().toString(), 24), 1, 168);
                    int m = clamp(parseInt(max.getText().toString(), 280), 20, 1000);
                    String h = handle.getText().toString().trim();
                    if (h.isEmpty()) h = "DeadDrop";
                    boolean oldDark = darkMode();
                    prefs.edit().putInt("ttl_hours", t).putInt("max_chars", m).putString("handle", h).putBoolean("dark_mode", dark.isChecked()).apply();
                    setStatus("Settings saved. TTL " + t + "h, max " + m + " chars.");
                    if (oldDark != dark.isChecked()) {
                        buildUi();
                        refreshGroupsUi();
                        refreshLogUi();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void transmitTestPing() {
        messageInput.setText("DeadDrop test " + nowTime());
        transmitMessage();
    }

    private void showKnownSendersDialog() {
        StringBuilder out = new StringBuilder();
        try {
            KeyPair kp = SigningIdentity.getOrCreate();
            out.append("Your signed-handle safety fingerprint:\n#")
                    .append(DeadDropCrypto.fingerprint(kp.getPublic().getEncoded()))
                    .append("\n\nKnown signed senders are remembered locally on first verified receive. Compare fingerprints out-of-band before relying on a handle.\n\n");
        } catch (Exception e) {
            out.append("Could not read local signing fingerprint: ").append(e.getMessage()).append("\n\n");
        }
        boolean any = false;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("sender_fp_v1|")) continue;
            any = true;
            String label = key.substring("sender_fp_v1|".length()).replace("|", " / ");
            out.append("• ").append(label).append("\n  #").append(String.valueOf(entry.getValue())).append('\n');
            String warning = prefs.getString("sender_warn_v1|" + key.substring("sender_fp_v1|".length()), "");
            if (!warning.isEmpty()) out.append("  WARNING: ").append(warning).append('\n');
        }
        if (!any) out.append("No signed senders remembered yet. Receive a signed message to add one.\n");
        new AlertDialog.Builder(this)
                .setTitle("Known senders / safety")
                .setMessage(out.toString())
                .setNeutralButton("Forget all", (d, which) -> forgetKnownSenders())
                .setPositiveButton("OK", null)
                .show();
    }

    private void showManualHopAssistDialog() {
        DeadDropCrypto.Group g = activeGroup();
        if (g == null) { toast("Create or join a group first."); return; }
        AudioModem.HopSlot slot = AudioModem.manualHopSlot(g.key, g.id, AudioModem.defaultManualHopChannels(), 30, System.currentTimeMillis());
        String msg = "Manual hop assist for " + g.name + "\n\n"
                + "Current: " + slot.current + "\n"
                + "Next: " + slot.next + "\n"
                + "Changes in: " + slot.secondsRemaining + "s\n"
                + "Slot: " + slot.slotIndex + "\n\n"
                + "All members of this group compute the same 30-second schedule from the group key. This is only a manual channel guide; it does not retune radios or SDRs, and message encryption still provides the security.";
        new AlertDialog.Builder(this)
                .setTitle("Group hop assist")
                .setMessage(msg)
                .setPositiveButton("Refresh", (d, which) -> showManualHopAssistDialog())
                .setNegativeButton("OK", null)
                .show();
    }

    private void forgetKnownSenders() {
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("sender_fp_v1|") || key.startsWith("sender_warn_v1|")) edit.remove(key);
        }
        edit.apply();
        setStatus("Known signed senders forgotten. Future signed packets will be learned again.");
    }

    private void showAudioCheckDialog() {
        String profileText = "Profile guidance:\n"
                + "• Fast/Nearby: shortest messages, clean device-to-device audio.\n"
                + "• Normal: default field-test mode.\n"
                + "• Robust+FEC: noisy rooms, speakerphone, light radio filtering.\n"
                + "• Voice Bridge/Narrowband: slow speech-band mode for speakerphone/call/radio bridges.\n"
                + "• Ultra/Noisy: very slow emergency/noisy-link mode.\n\n"
                + "Live receive status now reports peak/RMS level and clipping hints every ~2 seconds. "
                + "Aim for peak 10–80%, no clipping. If packets are seen but not accepted, verify group/invite, mode, and second factor.";
        new AlertDialog.Builder(this)
                .setTitle("DeadDrop audio check")
                .setMessage(profileText)
                .setPositiveButton("Send test ping", (d, which) -> transmitTestPing())
                .setNegativeButton("OK", null)
                .show();
    }

    private void transmitMessage() {
        try {
            String text = messageInput.getText().toString();
            byte[] packet = createPacketForCurrentMode(text);
            AudioModem.Profile profile = (AudioModem.Profile) profileSpinner.getSelectedItem();
            AudioModem.RadioPreset radioPreset = selectedRadioPreset();
            if (radioPreset.receiveOnly) throw new IllegalArgumentException("SDR receive is receive-only. Choose another radio preset to transmit.");
            short[] encoded = AudioModem.encode(packet, profile);
            short[] one = AudioModem.prepareTransmit(encoded, radioPreset);
            int repeats = repeatCount();
            double seconds = one.length * repeats / (double)AudioModem.SAMPLE_RATE;
            String modeLabel = modeForCurrentSelection();
            DeadDropLog.appendSent(prefs, groupLabelForCurrentMode(), text.trim(), modeLabel, ttlHours());
            pruneExpiredLog();
            refreshLogUi();
            setPacketStatus("Transmitting " + packet.length + " bytes — " + profile.label);
            updateProfileCard();
            String presetSummary = AudioModem.transmitPresetSummary(radioPreset);
            setStatus("Transmitting " + packet.length + " byte " + modeSpinner.getSelectedItem().toString()
                    + " packet via " + profile.label + " / " + presetSummary
                    + " (~" + String.format(Locale.US, "%.1f", seconds) + "s). Audio not stored.");
            messageInput.setText("");
            new Thread(() -> playSamples(one, repeats), "deaddrop-transmit").start();
        } catch (Exception e) {
            toast(e.getMessage());
        }
    }

    private byte[] createPacketForCurrentMode(String text) throws Exception {
        int mode = modeSpinner.getSelectedItemPosition();
        if (mode == 0) {
            DeadDropCrypto.Group g = activeGroup();
            if (g == null) throw new IllegalArgumentException("Create or join a group first.");
            return DeadDropCrypto.createEncryptedPacket(g, text, ttlHours(), maxChars());
        }
        KeyPair kp = SigningIdentity.getOrCreate();
        if (mode == 1) {
            DeadDropCrypto.Group g = activeGroup();
            if (g == null) throw new IllegalArgumentException("Create or join a group first.");
            return DeadDropCrypto.createSignedEncryptedPacket(g, text, ttlHours(), maxChars(), handle(), kp.getPublic(), kp.getPrivate());
        }
        return DeadDropCrypto.createSignedPlaintextPacket(text, ttlHours(), maxChars(), handle(), kp.getPublic(), kp.getPrivate());
    }

    private String groupLabelForCurrentMode() {
        if (modeSpinner.getSelectedItemPosition() == 2) return "Signed plaintext";
        DeadDropCrypto.Group g = activeGroup();
        return g == null ? "No group" : g.name;
    }

    private String modeForCurrentSelection() {
        int mode = modeSpinner.getSelectedItemPosition();
        if (mode == 0) return "encrypted-anonymous";
        if (mode == 1) return "encrypted-signed-handle";
        return "signed-plaintext";
    }

    private void playSamples(short[] samples, int repeats) {
        int min = AudioTrack.getMinBufferSize(AudioModem.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        for (int i = 0; i < repeats; i++) {
            AudioTrack track = new AudioTrack(AudioManager.STREAM_MUSIC, AudioModem.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, samples.length * 2), AudioTrack.MODE_STATIC);
            track.write(samples, 0, samples.length);
            track.play();
            while (track.getPlaybackHeadPosition() < samples.length) {
                try { Thread.sleep(30); } catch (InterruptedException ignored) { break; }
            }
            track.release();
            try { Thread.sleep(120); } catch (InterruptedException ignored) { break; }
        }
        runOnUiThread(() -> { setPacketStatus("Transmit complete"); setStatus("Transmit complete. No audio was stored."); });
    }

    private void startListeningIfPossible() {
        if (selectedListenSource() == LISTEN_SOURCE_PLAYBACK) startPlaybackListeningIfPossible();
        else startMicListeningIfPossible();
    }

    private int selectedListenSource() {
        if (listenSourceSpinner != null) return listenSourceSpinner.getSelectedItemPosition() == LISTEN_SOURCE_PLAYBACK ? LISTEN_SOURCE_PLAYBACK : LISTEN_SOURCE_MIC;
        return prefs.getInt("listen_source", LISTEN_SOURCE_MIC) == LISTEN_SOURCE_PLAYBACK ? LISTEN_SOURCE_PLAYBACK : LISTEN_SOURCE_MIC;
    }

    private void startMicListeningIfPossible() {
        if (listening) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicAction = 1;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            setStatus("Microphone permission is needed only when listening.");
            return;
        }
        listening = true;
        ring.clear();
        listenButtonText();
        listenThread = new Thread(this::listenLoop, "deaddrop-listen");
        listenThread.start();
        setListeningStatus("Listen: on / mic");
        setStatus("Listening from microphone. Audio stays in memory and is not stored.");
    }

    private void startPlaybackListeningIfPossible() {
        if (listening) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setAudioStatus("Playback: Android 10+ required");
            setStatus("Device playback capture requires Android 10 or newer. Use microphone/acoustic listening on this device.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicAction = 3;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            setStatus("Audio permission is needed before Android can capture device playback.");
            return;
        }
        requestPlaybackProjection(1);
    }

    private void requestPlaybackProjection(int action) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setStatus("Device playback capture requires Android 10 or newer.");
            return;
        }
        MediaProjectionManager mgr = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr == null) {
            setStatus("Device playback capture unavailable: MediaProjection service missing.");
            return;
        }
        pendingProjectionAction = action;
        setAudioStatus("Playback: waiting for Android capture approval");
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    private void startPlaybackListening(int resultCode, Intent data) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        MediaProjectionManager mgr = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr == null) {
            setStatus("Device playback capture unavailable: MediaProjection service missing.");
            return;
        }
        MediaProjection projection = mgr.getMediaProjection(resultCode, data);
        if (projection == null) {
            setStatus("Device playback capture unavailable: Android did not grant a capture session.");
            return;
        }
        activeProjectionCallback = new MediaProjection.Callback() {
            @Override public void onStop() { runOnUiThread(() -> handleProjectionStoppedBySystem()); }
        };
        projection.registerCallback(activeProjectionCallback, new Handler(Looper.getMainLooper()));
        activeMediaProjection = projection;
        listening = true;
        ring.clear();
        listenButtonText();
        listenThread = new Thread(() -> listenPlaybackLoop(projection), "deaddrop-playback-listen");
        listenThread.start();
        setListeningStatus("Listen: on / playback");
        setStatus("Listening to device playback/app audio. Android may show active capture; audio is not stored.");
    }

    private void handleProjectionStoppedBySystem() {
        activeMediaProjection = null;
        if (!listening) return;
        listening = false;
        AudioRecord rec = activeListenRecord;
        if (rec != null) { try { rec.stop(); } catch (Exception ignored) {} }
        if (listenThread != null) listenThread.interrupt();
        listenThread = null;
        listenButtonText();
        setListeningStatus("Listen: off");
        setAudioStatus("Playback: stopped by Android");
        setPacketStatus("Packet: idle");
        setStatus("Android stopped device playback capture.");
    }

    private void stopListening() {
        listening = false;
        AudioRecord rec = activeListenRecord;
        activeListenRecord = null;
        if (rec != null) { try { rec.stop(); } catch (Exception ignored) {} }
        MediaProjection projection = activeMediaProjection;
        MediaProjection.Callback callback = activeProjectionCallback;
        activeMediaProjection = null;
        activeProjectionCallback = null;
        if (projection != null) {
            try { if (callback != null) projection.unregisterCallback(callback); } catch (Exception ignored) {}
            try { projection.stop(); } catch (Exception ignored) {}
        }
        if (listenThread != null) listenThread.interrupt();
        listenThread = null;
        listenButtonText();
        setListeningStatus("Listen: off");
        setAudioStatus("Source: off");
        setPacketStatus("Packet: idle");
        setStatus("Listening stopped. Audio capture is off.");
    }

    private AudioRecord createMicAudioRecord() {
        int min = AudioRecord.getMinBufferSize(AudioModem.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("AudioRecord buffer failed");
        return new AudioRecord(MediaRecorder.AudioSource.MIC, AudioModem.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4);
    }

    private AudioRecord createPlaybackAudioRecord(MediaProjection projection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw new IllegalStateException("Android 10+ required for playback capture");
        int min = AudioRecord.getMinBufferSize(AudioModem.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("AudioRecord buffer failed");
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioModem.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        return new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(min * 4)
                .setAudioPlaybackCaptureConfig(config)
                .build();
    }

    private void listenLoop() {
        try {
            processAudioRecord(createMicAudioRecord(), "Mic");
        } catch (Exception e) {
            runOnUiThread(() -> { setAudioStatus("Mic unavailable: " + e.getMessage()); setStatus("Microphone unavailable: " + e.getMessage()); });
        }
    }

    private void listenPlaybackLoop(MediaProjection projection) {
        try {
            processAudioRecord(createPlaybackAudioRecord(projection), "Playback");
        } catch (Exception e) {
            runOnUiThread(() -> { setAudioStatus("Playback unavailable: " + e.getMessage()); setStatus("Device playback capture unavailable: " + e.getMessage()); });
        }
    }

    private void processAudioRecord(AudioRecord rec, String label) {
        int min = AudioRecord.getMinBufferSize(AudioModem.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        short[] buf = new short[Math.max(1024, min > 0 ? min / 2 : 2048)];
        long lastDecode = 0;
        long lastLevel = 0;
        activeListenRecord = rec;
        try {
            rec.startRecording();
            runOnUiThread(() -> setAudioStatus(label + ": AudioRecord started @ " + AudioModem.SAMPLE_RATE + " Hz"));
            while (listening && !Thread.currentThread().isInterrupted()) {
                int n = rec.read(buf, 0, buf.length);
                if (n > 0) ring.append(buf, n);
                long now = System.currentTimeMillis();
                if (now - lastLevel > 2000 && n > 0) {
                    lastLevel = now;
                    AudioDiagnostics diag = AudioDiagnostics.analyze(buf, n);
                    runOnUiThread(() -> { setAudioStatus(label + ": " + diag.shortStatus()); setStatus(label + " listening — " + diag.shortStatus() + ". Audio not stored."); });
                }
                if (now - lastDecode > 900) {
                    lastDecode = now;
                    short[] snapshot = ring.snapshot();
                    AudioModem.DecodeResult result = AudioModem.tryDecodeAny(snapshot);
                    if (result != null) {
                        handleDecodedPayload(result);
                        ring.clear();
                    } else {
                        AudioModem.DecodeReport report = AudioModem.analyzeDecode(snapshot);
                        if (report.signalPresent || report.syncSeen || report.crcFailed) {
                            runOnUiThread(() -> setPacketStatus("Decode: " + report.shortStatus()));
                        }
                    }
                }
            }
        } catch (Exception e) {
            runOnUiThread(() -> setStatus(label + " listen error: " + e.getMessage()));
        } finally {
            if (activeListenRecord == rec) activeListenRecord = null;
            try { rec.stop(); } catch (Exception ignored) {}
            rec.release();
        }
    }

    private void handleDecodedPayload(AudioModem.DecodeResult result) {
        try {
            DeadDropCrypto.ReceivedMessage msg = DeadDropCrypto.openAnyPacket(groups, result.payload);
            String trustNote = rememberSenderTrust(msg);
            DeadDropReplayCache.Result replay = DeadDropReplayCache.remember(prefs, msg);
            if (replay.duplicate) {
                runOnUiThread(() -> { setPacketStatus("Duplicate ignored — " + result.profile.label); setStatus("Duplicate DeadDrop ignored (" + result.profile.label + "). Replay cache entries: " + replay.keptEntries + "."); });
                return;
            }
            boolean added = DeadDropLog.append(prefs, msg);
            if (!added) {
                runOnUiThread(() -> setStatus("Duplicate DeadDrop ignored (" + result.profile.label + ")."));
                return;
            }
            seen.add(msg.dedupeKey());
            runOnUiThread(() -> { pruneExpiredLog(); refreshLogUi(); setPacketStatus("Decoded " + trustLabel(msg.mode) + " — " + result.profile.label + " (" + result.payload.length + " bytes)"); setStatus("Received " + trustLabel(msg.mode) + " via " + result.profile.label + trustNote + "."); });
        } catch (Exception e) {
            runOnUiThread(() -> { setPacketStatus("Packet decoded, rejected — " + e.getMessage()); setStatus("Packet decoded but not accepted: " + e.getMessage()); });
        }
    }

    private void startBackgroundListen() {
        if (selectedListenSource() == LISTEN_SOURCE_PLAYBACK) startBackgroundPlaybackListen();
        else startBackgroundMicListen();
    }

    private void startBackgroundMicListen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicAction = 2;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            setStatus("Microphone permission is needed only when background listening is started.");
            return;
        }
        chooseBackgroundDuration("Background microphone listening", "Starts a user-visible foreground microphone listener. DeadDrop has no Internet permission and does not store audio.", LISTEN_SOURCE_MIC);
    }

    private void startBackgroundPlaybackListen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setStatus("Background device playback capture requires Android 10 or newer.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicAction = 4;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            setStatus("Audio permission is needed before Android can capture device playback.");
            return;
        }
        chooseBackgroundDuration("Background device playback listening", "Starts a user-visible foreground device-playback capture session. Android will ask for capture approval and may show active capture. DeadDrop has no Internet permission and does not store audio.", LISTEN_SOURCE_PLAYBACK);
    }

    private void chooseBackgroundDuration(String title, String message, int source) {
        String[] labels = new String[]{"15 minutes", "1 hour", "Until stopped"};
        long[] durations = new long[]{15L * 60L * 1000L, 60L * 60L * 1000L, 0L};
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setItems(labels, (d, which) -> {
                    if (source == LISTEN_SOURCE_PLAYBACK) {
                        pendingBackgroundListenDurationMs = durations[which];
                        pendingBackgroundListenLabel = labels[which];
                        requestPlaybackProjection(2);
                    } else {
                        startBackgroundListenForSource(durations[which], labels[which], LISTEN_SOURCE_MIC, 0, null);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startBackgroundListenForSource(long durationMs, String label, int source, int projectionResultCode, Intent projectionData) {
        Intent i = new Intent(this, DeadDropListenService.class);
        i.putExtra(DeadDropListenService.EXTRA_DURATION_MS, durationMs);
        i.putExtra(DeadDropListenService.EXTRA_SOURCE, source == LISTEN_SOURCE_PLAYBACK ? DeadDropListenService.SOURCE_PLAYBACK : DeadDropListenService.SOURCE_MIC);
        if (source == LISTEN_SOURCE_PLAYBACK) {
            i.putExtra(DeadDropListenService.EXTRA_PROJECTION_RESULT_CODE, projectionResultCode);
            i.putExtra(DeadDropListenService.EXTRA_PROJECTION_DATA, projectionData);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
        else startService(i);
        setStatus("Background foreground-service listener starting (" + label + ", " + (source == LISTEN_SOURCE_PLAYBACK ? "device playback" : "microphone") + "). Android will show a persistent notification.");
    }

    private void stopBackgroundListen() {
        stopService(new Intent(this, DeadDropListenService.class));
        setStatus("Background listener stop requested.");
    }

    private void loadGroups() {
        try { groups.clear(); groups.addAll(DeadDropCrypto.deserializeGroups(secureGet("groups_v1", ""))); }
        catch (Exception e) { groups.clear(); }
        activeGroupIndex = clamp(prefs.getInt("active_group", 0), 0, Math.max(0, groups.size() - 1));
    }

    private void saveGroups() {
        try { securePut("groups_v1", DeadDropCrypto.serializeGroups(groups)); } catch (Exception e) { toast("Could not securely save groups: " + e.getMessage()); }
        prefs.edit().putInt("active_group", activeGroupIndex).apply();
    }

    private void refreshGroupsUi() {
        List<String> names = new ArrayList<>();
        for (DeadDropCrypto.Group g : groups) names.add(g.name + "  [" + g.idHex() + "]");
        if (names.isEmpty()) names.add("No group yet");
        ArrayAdapter<String> adapter = spinnerAdapter(names.toArray(new String[0]));
        groupSpinner.setAdapter(adapter);
        groupSpinner.setSelection(Math.min(activeGroupIndex, names.size() - 1));
        updateGroupLabel();
    }

    private void updateGroupLabel() {
        DeadDropCrypto.Group g = activeGroup();
        groupView.setTextColor(colorMuted());
        if (g == null) groupView.setText("No group yet. Create or join with a DDINV1 invite + second factor.");
        else groupView.setText("Active group: " + g.name + " / " + g.idHex());
    }

    private DeadDropCrypto.Group activeGroup() {
        if (groups.isEmpty()) return null;
        if (activeGroupIndex < 0 || activeGroupIndex >= groups.size()) activeGroupIndex = 0;
        return groups.get(activeGroupIndex);
    }

    private void pruneExpiredLog() {
        try {
            seen.clear();
            for (DeadDropLog.Entry e : DeadDropLog.readAndPrune(prefs)) seen.add(e.dedupeKey);
        } catch (Exception e) { setStatus("Could not securely update log: " + e.getMessage()); }
    }

    private void loadSeenFromLog() { pruneExpiredLog(); }

    private void refreshLogUi() {
        logLayout.removeAllViews();
        List<DeadDropLog.Entry> entries;
        try { entries = DeadDropLog.readAndPrune(prefs); }
        catch (Exception e) {
            TextView err = new TextView(this);
            err.setText("Could not read encrypted local log: " + e.getMessage());
            err.setTextColor(colorWarn());
            logLayout.addView(err, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        if (entries.isEmpty()) return;
        for (int i = entries.size() - 1; i >= 0; i--) {
            DeadDropLog.Entry e = entries.get(i);
            addMessageBubble(e);
        }
    }

    private void addMessageBubble(DeadDropLog.Entry e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(e.isSent() ? Gravity.RIGHT : Gravity.LEFT);
        row.setPadding(0, dp(4), 0, dp(4));

        TextView item = new TextView(this);
        String who = e.isSent() ? "You" : e.senderLabel;
        String prefix = e.isSent() ? "Sent" : "Received";
        item.setText(prefix + " · " + timeLabel(e.receivedAt) + " · " + e.groupLabel + "\n"
                + who + " · " + trustLabel(e.mode) + "\n"
                + e.text);
        item.setTextSize(16);
        item.setTextColor(colorText());
        item.setPadding(dp(12), dp(9), dp(12), dp(9));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(e.isSent() ? Color.rgb(20, 65, 58) : Color.rgb(37, 45, 58));
        bg.setStroke(dp(1), e.isSent() ? colorAccent2() : Color.rgb(89, 110, 130));
        item.setBackground(bg);

        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(0, -2, 0.88f);
        if (e.isSent()) bubbleParams.setMargins(dp(48), 0, 0, 0);
        else bubbleParams.setMargins(0, 0, dp(48), 0);
        row.addView(item, bubbleParams);
        logLayout.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private String secureGet(String key, String defValue) {
        return SecurePrefs.getString(prefs, key, defValue);
    }

    private void securePut(String key, String value) throws Exception {
        SecurePrefs.putString(prefs, key, value);
    }

    private void showInviteResult(String title, String invite) {
        copy(invite);
        LinearLayout box = dialogBox();
        TextView msg = new TextView(this);
        msg.setText("Invite copied to clipboard. Send the second factor separately.\n\n" + invite);
        ImageView qr = new ImageView(this);
        try {
            Bitmap bmp = QrInvite.encode(invite, dp(280));
            qr.setImageBitmap(bmp);
            qr.setAdjustViewBounds(true);
            qr.setPadding(0, dp(8), 0, dp(8));
        } catch (Exception e) {
            msg.append("\n\nQR generation failed: " + e.getMessage());
        }
        box.addView(msg); box.addView(qr);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setNeutralButton("Share invite", (d, which) -> shareText(invite))
                .setPositiveButton("OK", null)
                .show();
    }

    private void shareText(String text) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, "Share DeadDrop invite"));
    }

    private void confirmPanicWipe() {
        new AlertDialog.Builder(this)
                .setTitle("Panic wipe DeadDrop?")
                .setMessage("This deletes local group keys, signing identity, message logs, replay cache, and local Android Keystore entries. It cannot be undone.")
                .setPositiveButton("WIPE", (d, which) -> panicWipe())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void panicWipe() {
        stopListening();
        stopBackgroundListen();
        SecurePrefs.deleteMasterKey();
        SigningIdentity.delete();
        prefs.edit().clear().apply();
        groups.clear();
        seen.clear();
        activeGroupIndex = 0;
        refreshGroupsUi();
        refreshLogUi();
        setStatus("Panic wipe complete. Local DeadDrop keys/groups/text logs deleted. Audio was never stored.");
    }

    private <T> ArrayAdapter<T> spinnerAdapter(T[] values) {
        return new ArrayAdapter<T>(this, android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                styleSpinnerView(view, false);
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                styleSpinnerView(view, true);
                return view;
            }
        };
    }

    private void styleSpinnerView(View view, boolean dropdown) {
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            tv.setTextColor(colorText());
            tv.setHintTextColor(colorMuted());
            tv.setTextSize(14);
            tv.setPadding(dp(8), dropdown ? dp(10) : dp(4), dp(8), dropdown ? dp(10) : dp(4));
        }
        view.setBackgroundColor(dropdown ? colorCard() : colorBg());
    }

    private void applyTopInsetPadding(View v, int baseTopPadding) {
        v.setOnApplyWindowInsetsListener((view, insets) -> {
            int statusTop;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets status = insets.getInsets(android.view.WindowInsets.Type.statusBars());
                statusTop = status.top;
            } else {
                statusTop = insets.getSystemWindowInsetTop();
            }
            view.setPadding(view.getPaddingLeft(), baseTopPadding + statusTop, view.getPaddingRight(), view.getPaddingBottom());
            return insets;
        });
        v.requestApplyInsets();
    }

    private void installKeyboardAvoidance(View root, ScrollView mainScroll, View composer,
                                          int scrollBottomPadding, int composerClosedBottomPadding) {
        mainScroll.setClipToPadding(false);
        root.getViewTreeObserver().addOnGlobalLayoutListener(() ->
                updateKeyboardAvoidance(root, mainScroll, composer, scrollBottomPadding, composerClosedBottomPadding));
        composer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                v.post(() -> updateKeyboardAvoidance(root, mainScroll, composer, scrollBottomPadding, composerClosedBottomPadding)));
        composer.post(() -> updateKeyboardAvoidance(root, mainScroll, composer, scrollBottomPadding, composerClosedBottomPadding));
    }

    private void updateKeyboardAvoidance(View root, ScrollView mainScroll, View composer,
                                         int scrollBottomPadding, int composerClosedBottomPadding) {
        View decor = getWindow().getDecorView();
        Rect visible = new Rect();
        decor.getWindowVisibleDisplayFrame(visible);
        int[] rootLocation = new int[2];
        decor.getRootView().getLocationOnScreen(rootLocation);
        int fullBottom = rootLocation[1] + Math.max(decor.getRootView().getHeight(), root.getRootView().getHeight());
        if (fullBottom <= 0 || visible.bottom <= 0 || composer.getHeight() <= 0) return;

        int hiddenBottom = Math.max(0, fullBottom - visible.bottom);
        boolean keyboardLikely = hiddenBottom > dp(120);
        int targetComposerBottomPadding = keyboardLikely ? dp(6) : composerClosedBottomPadding + hiddenBottom;
        if (composer.getPaddingBottom() != targetComposerBottomPadding) {
            composer.setPadding(composer.getPaddingLeft(), composer.getPaddingTop(),
                    composer.getPaddingRight(), targetComposerBottomPadding);
            composer.post(() -> updateKeyboardAvoidance(root, mainScroll, composer, scrollBottomPadding, composerClosedBottomPadding));
            return;
        }

        int[] composerLocation = new int[2];
        composer.getLocationOnScreen(composerLocation);
        int untranslatedComposerBottom = Math.round(composerLocation[1] - composer.getTranslationY() + composer.getHeight());
        int overlap = Math.max(0, untranslatedComposerBottom - visible.bottom);
        int lift = keyboardLikely ? overlap + (overlap > 0 ? dp(4) : 0) : 0;

        float targetTranslation = -lift;
        if (Math.abs(composer.getTranslationY() - targetTranslation) > 0.5f) {
            composer.setTranslationY(targetTranslation);
        }

        int targetScrollPaddingBottom = scrollBottomPadding + lift;
        if (mainScroll.getPaddingBottom() != targetScrollPaddingBottom) {
            mainScroll.setPadding(mainScroll.getPaddingLeft(), mainScroll.getPaddingTop(),
                    mainScroll.getPaddingRight(), targetScrollPaddingBottom);
        }
    }

    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); r.setPadding(0, dp(2), 0, dp(2)); return r; }
    private void registerUsbSdrReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_USB_SDR_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbSdrPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbSdrPermissionReceiver, filter);
    }
    private void scanUsbSdrReceivers(boolean requestPermission) {
        UsbManager manager = (UsbManager)getSystemService(Context.USB_SERVICE);
        if (manager == null) { setUsbSdrStatus("USB SDR: USB service unavailable on this device."); return; }
        Map<String, UsbDevice> devices = manager.getDeviceList();
        if (devices == null || devices.isEmpty()) { setUsbSdrStatus("USB SDR: no USB receivers found."); return; }
        UsbDevice best = null;
        for (UsbDevice d : devices.values()) { if (looksLikeUsbSdr(d)) { best = d; break; } }
        if (best == null) {
            UsbDevice first = devices.values().iterator().next();
            setUsbSdrStatus("USB SDR: " + devices.size() + " USB device(s) attached; no generic receiver shape found. First: " + usbDeviceLabel(first));
            return;
        }
        if (manager.hasPermission(best)) {
            setUsbSdrStatus("USB SDR: authorized " + usbDeviceLabel(best) + ". Direct demodulation is staged; Android receive can use acoustic/audio input today.");
            return;
        }
        setUsbSdrStatus("USB SDR: found " + usbDeviceLabel(best) + "; permission needed.");
        if (requestPermission) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_SDR_PERMISSION).setPackage(getPackageName()), flags);
            manager.requestPermission(best, pi);
        }
    }
    private boolean looksLikeUsbSdr(UsbDevice d) {
        int vendor = d.getVendorId();
        if (vendor == 0x0bda || vendor == 0x1d50 || vendor == 0x1fc9 || vendor == 0x04b4) return true;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface intf = d.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC) return true;
        }
        return false;
    }
    private String usbDeviceLabel(UsbDevice d) {
        String name = null;
        if (Build.VERSION.SDK_INT >= 21) try { name = d.getProductName(); } catch (Exception ignored) {}
        if (name == null || name.trim().isEmpty()) name = d.getDeviceName();
        return String.format(Locale.US, "%s (%04x:%04x)", name, d.getVendorId(), d.getProductId());
    }
    private void setUsbSdrStatus(String s) { if (usbSdrStatus != null) usbSdrStatus.setText(s); }
    private LinearLayout dialogBox() { LinearLayout b = new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(dp(8), 0, dp(8), 0); b.setBackgroundColor(colorPanel()); return b; }
    private TextView label(String s) { TextView v = new TextView(this); v.setText(s); v.setTextColor(colorMuted()); v.setPadding(0, 0, dp(6), 0); return v; }
    private TextView mutedText(String s) { TextView v = new TextView(this); v.setText(s); v.setTextColor(colorMuted()); v.setTextSize(12); v.setPadding(0, dp(2), 0, dp(6)); return v; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setTextColor(colorText()); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) b.setBackgroundTintList(ColorStateList.valueOf(colorCard())); return b; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private EditText input(String hint, boolean password) { EditText e = new EditText(this); e.setHint(hint); if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); styleInput(e); return e; }
    private void styleInput(EditText e) {
        e.setTextColor(colorText());
        e.setHintTextColor(colorMuted());
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorPanel());
        bg.setStroke(dp(1), darkMode() ? colorCard() : Color.rgb(205, 214, 220));
        bg.setCornerRadius(dp(2));
        e.setBackground(bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            e.setBackgroundTintList(null);
        }
    }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private int ttlHours() { return prefs.getInt("ttl_hours", 24); }
    private int maxChars() { return prefs.getInt("max_chars", 280); }
    private String handle() { return prefs.getString("handle", "DeadDrop"); }
    private int repeatCount() { String s = repeatSpinner.getSelectedItem().toString(); return s.startsWith("5") ? 5 : s.startsWith("3") ? 3 : 1; }
    private AudioModem.RadioPreset selectedRadioPreset() {
        if (radioPresetSpinner == null || radioPresetSpinner.getSelectedItem() == null) return AudioModem.RadioPreset.STANDARD;
        return (AudioModem.RadioPreset) radioPresetSpinner.getSelectedItem();
    }
    private void applyRadioPresetDefaults(AudioModem.RadioPreset preset) {
        if (preset == null || preset == AudioModem.RadioPreset.STANDARD || preset.receiveOnly) return;
        if (profileSpinner != null) profileSpinner.setSelection(preset.defaultProfile.ordinal());
        if (repeatSpinner != null) repeatSpinner.setSelection(preset.defaultRepeats >= 5 ? 2 : preset.defaultRepeats >= 3 ? 1 : 0);
    }
    private int parseInt(String s, int d) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; } }
    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private String nowTime() { return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()); }
    private String timeLabel(long t) { return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(t > 0 ? t : System.currentTimeMillis())); }
    private String trustLabel(String mode) {
        if ("encrypted-signed-handle".equals(mode)) return "verified signed encrypted";
        if ("signed-plaintext".equals(mode)) return "verified signed plaintext";
        if ("encrypted-anonymous".equals(mode)) return "anonymous encrypted";
        return mode == null ? "unknown" : mode;
    }
    private String rememberSenderTrust(DeadDropCrypto.ReceivedMessage msg) {
        if (msg == null || msg.senderFingerprint == null || msg.senderFingerprint.isEmpty()) return "";
        String sender = (msg.senderLabel == null || msg.senderLabel.trim().isEmpty()) ? "Verified sender" : msg.senderLabel.trim();
        String key = "sender_fp_v1|" + msg.groupLabel() + "|" + sender;
        String old = prefs.getString(key, "");
        prefs.edit().putString(key, msg.senderFingerprint).apply();
        if (!old.isEmpty() && !old.equals(msg.senderFingerprint)) {
            String warning = "was #" + old + ", now #" + msg.senderFingerprint + " at " + nowTime();
            prefs.edit().putString("sender_warn_v1|" + msg.groupLabel() + "|" + sender, warning).apply();
            return ". KEY CHANGE WARNING for " + sender + ": " + warning;
        }
        return " (#" + msg.senderFingerprint + ")";
    }
    private void toast(String s) { Toast.makeText(this, s == null ? "Error" : s, Toast.LENGTH_LONG).show(); }
    private void setStatus(String s) { if (statusView != null) statusView.setText(s); }
    private void setListeningStatus(String s) { if (listeningCard != null) listeningCard.setText(s); }
    private void setAudioStatus(String s) { if (audioCard != null) audioCard.setText(s); }
    private void setPacketStatus(String s) { if (packetCard != null) packetCard.setText(s); if (statusView != null) statusView.setText(s); }
    private void updateProfileCard() {
        if (profileCard == null || profileSpinner == null || modeSpinner == null) return;
        Object profile = profileSpinner.getSelectedItem();
        Object mode = modeSpinner.getSelectedItem();
        AudioModem.RadioPreset preset = selectedRadioPreset();
        String modeText = mode == null ? "?" : mode.toString().replace(" encrypted", " enc.").replace("Signed handle", "Signed");
        String profileText = (profile == null ? "?" : profile.toString()) + " / " + modeText;
        if (preset != AudioModem.RadioPreset.STANDARD) profileText += " / " + preset.label;
        profileCard.setText(profileText);
    }
    private void listenButtonText() { if (listenButton != null) runOnUiThread(() -> listenButton.setText(listening ? "Stop listening" : "Start listening")); }
    private void copy(String s) { ((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("DeadDrop invite", s)); }
    private int peakPercent(short[] buf, int n) { int peak = 0; for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs((int)buf[i])); return Math.min(100, (int)Math.round(peak * 100.0 / 32767.0)); }
    private String levelHint(int pct) { if (pct < 3) return "too quiet?"; if (pct > 85) return "possible clipping"; return "ok"; }

    private static final class RingSamples {
        private final short[] data;
        private int write = 0;
        private boolean full = false;
        RingSamples(int capacity) { data = new short[capacity]; }
        synchronized void append(short[] src, int n) {
            for (int i = 0; i < n; i++) { data[write++] = src[i]; if (write >= data.length) { write = 0; full = true; } }
        }
        synchronized short[] snapshot() {
            int size = full ? data.length : write;
            short[] out = new short[size];
            if (!full) { System.arraycopy(data, 0, out, 0, size); return out; }
            int tail = data.length - write;
            System.arraycopy(data, write, out, 0, tail);
            System.arraycopy(data, 0, out, tail, write);
            return out;
        }
        synchronized void clear() { write = 0; full = false; }
    }
}
