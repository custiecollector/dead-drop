package org.deaddrop.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

public class DeadDropListenService extends Service {
    private static final int NOTIFICATION_ID = 77;
    private static final String CHANNEL_ID = "deaddrop-listen";
    public static final String EXTRA_DURATION_MS = "org.deaddrop.app.EXTRA_DURATION_MS";
    public static final String EXTRA_SOURCE = "org.deaddrop.app.EXTRA_SOURCE";
    public static final String EXTRA_PROJECTION_RESULT_CODE = "org.deaddrop.app.EXTRA_PROJECTION_RESULT_CODE";
    public static final String EXTRA_PROJECTION_DATA = "org.deaddrop.app.EXTRA_PROJECTION_DATA";
    public static final int SOURCE_MIC = 0;
    public static final int SOURCE_PLAYBACK = 1;

    private volatile boolean running = false;
    private volatile long stopAtMillis = 0L;
    private volatile AudioRecord activeRecord;
    private Thread worker;
    private SharedPreferences prefs;
    private MediaProjection mediaProjection;
    private MediaProjection.Callback mediaProjectionCallback;
    private final RingSamples ring = new RingSamples(AudioModem.SAMPLE_RATE * 45);

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        long durationMs = intent == null ? 0L : intent.getLongExtra(EXTRA_DURATION_MS, 0L);
        int source = intent == null ? SOURCE_MIC : intent.getIntExtra(EXTRA_SOURCE, SOURCE_MIC);
        stopAtMillis = durationMs > 0L ? System.currentTimeMillis() + durationMs : 0L;
        String sourceLabel = source == SOURCE_PLAYBACK ? "device playback" : "microphone";
        String text = stopAtMillis > 0L
                ? "Local " + sourceLabel + " listener stops automatically in " + Math.max(1L, durationMs / 60000L) + " min. No network permission; no stored audio."
                : "Local " + sourceLabel + " listener active until stopped. No network permission; no stored audio.";
        startForegroundForSource(source, "DeadDrop listening locally", text);
        if (!running) {
            running = true;
            int resultCode = intent == null ? 0 : intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0);
            Intent projectionData = intent == null ? null : intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
            worker = new Thread(() -> listenLoop(source, resultCode, projectionData), "deaddrop-bg-listen");
            worker.start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        AudioRecord rec = activeRecord;
        activeRecord = null;
        if (rec != null) { try { rec.stop(); } catch (Exception ignored) {} }
        if (worker != null) worker.interrupt();
        MediaProjection projection = mediaProjection;
        MediaProjection.Callback callback = mediaProjectionCallback;
        mediaProjection = null;
        mediaProjectionCallback = null;
        if (projection != null) {
            try { if (callback != null) projection.unregisterCallback(callback); } catch (Exception ignored) {}
            try { projection.stop(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void listenLoop(int source, int projectionResultCode, Intent projectionData) {
        AudioRecord rec = null;
        try {
            rec = source == SOURCE_PLAYBACK ? createPlaybackAudioRecord(projectionResultCode, projectionData) : createMicAudioRecord();
            activeRecord = rec;
            rec.startRecording();
            short[] buf = new short[Math.max(1024, AudioModem.SAMPLE_RATE / 20)];
            long lastDecode = 0;
            while (running && !Thread.currentThread().isInterrupted()) {
                int n = rec.read(buf, 0, buf.length);
                if (n > 0) ring.append(buf, n);
                long now = System.currentTimeMillis();
                if (stopAtMillis > 0L && now >= stopAtMillis) {
                    running = false;
                    stopSelf();
                    break;
                }
                if (now - lastDecode > 900) {
                    lastDecode = now;
                    AudioModem.DecodeResult result = AudioModem.tryDecodeAny(ring.snapshot());
                    if (result != null) {
                        handleDecodedPayload(result);
                        ring.clear();
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (activeRecord == rec) activeRecord = null;
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) {}
                rec.release();
            }
        }
    }

    private AudioRecord createMicAudioRecord() {
        int min = AudioRecord.getMinBufferSize(AudioModem.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("AudioRecord buffer failed");
        return new AudioRecord(MediaRecorder.AudioSource.MIC, AudioModem.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4);
    }

    private AudioRecord createPlaybackAudioRecord(int resultCode, Intent projectionData) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw new IllegalStateException("Android 10+ required for playback capture");
        if (projectionData == null || resultCode == 0) throw new IllegalStateException("Playback capture approval missing");
        MediaProjectionManager mgr = (MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr == null) throw new IllegalStateException("MediaProjection service unavailable");
        mediaProjection = mgr.getMediaProjection(resultCode, projectionData);
        if (mediaProjection == null) throw new IllegalStateException("Android did not grant playback capture");
        mediaProjectionCallback = new MediaProjection.Callback() {
            @Override public void onStop() {
                running = false;
                AudioRecord rec = activeRecord;
                if (rec != null) { try { rec.stop(); } catch (Exception ignored) {} }
                stopSelf();
            }
        };
        mediaProjection.registerCallback(mediaProjectionCallback, new Handler(Looper.getMainLooper()));

        int min = AudioRecord.getMinBufferSize(AudioModem.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("AudioRecord buffer failed");
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
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

    private void handleDecodedPayload(AudioModem.DecodeResult result) {
        try {
            List<DeadDropCrypto.Group> groups = new ArrayList<>(DeadDropCrypto.deserializeGroups(SecurePrefs.getString(prefs, "groups_v1", "")));
            DeadDropCrypto.ReceivedMessage msg = DeadDropCrypto.openAnyPacket(groups, result.payload);
            rememberSenderTrust(msg);
            DeadDropReplayCache.Result replay = DeadDropReplayCache.remember(prefs, msg);
            if (replay.duplicate) return;
            boolean added = DeadDropLog.append(prefs, msg);
            if (added) notifyReceived(msg, result.profile);
        } catch (Exception ignored) {}
    }

    private void notifyReceived(DeadDropCrypto.ReceivedMessage msg, AudioModem.Profile profile) {
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID + 1, notification("DeadDrop received", msg.mode + " via " + profile.label + ". Open app to view."));
    }

    private void rememberSenderTrust(DeadDropCrypto.ReceivedMessage msg) {
        if (msg == null || msg.senderFingerprint == null || msg.senderFingerprint.isEmpty()) return;
        String sender = (msg.senderLabel == null || msg.senderLabel.trim().isEmpty()) ? "Verified sender" : msg.senderLabel.trim();
        String suffix = msg.groupLabel() + "|" + sender;
        String key = "sender_fp_v1|" + suffix;
        String old = prefs.getString(key, "");
        prefs.edit().putString(key, msg.senderFingerprint).apply();
        if (!old.isEmpty() && !old.equals(msg.senderFingerprint)) {
            prefs.edit().putString("sender_warn_v1|" + suffix, "was #" + old + ", now #" + msg.senderFingerprint).apply();
        }
    }

    private void startForegroundForSource(int source, String title, String text) {
        Notification n = notification(title, text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = source == SOURCE_PLAYBACK ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIFICATION_ID, n, type);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private Notification notification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(title.contains("listening"))
                .setShowWhen(true);
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "DeadDrop listening", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Foreground local listener for offline DeadDrop audio packets.");
            NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }

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
