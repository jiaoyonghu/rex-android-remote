package com.rex.remote;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * REx v0.3: IN-APP OFFLINE Chinese ASR via bundled Vosk acoustic model.
 * Does NOT call Android SpeechRecognizer and requires NO network permission.
 * A microphone foreground service keeps sampling while allowed by Android 16.
 * Speaker verification and replay protection are NOT implemented, therefore
 * spoken movement or DISABLE may NEVER call NecIr.send().
 */
public final class VoiceService extends Service {
    public static final String ACTION_EVENT = "com.rex.remote.VOICE_EVENT";
    public static final String ACTION_STOP = "com.rex.remote.STOP_VOICE";
    public static final String ACTION_STATUS = "com.rex.remote.REQUEST_STATUS";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_WAKE = "wake";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_LEVEL = "level";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_SPEAKING = "speaking";
    private static final String CHANNEL = "rex_offline_voice_v3";
    private static final String TAG = "RExOfflineVoice";
    private static final int SAMPLE_RATE = 16000;
    private static final long WAKE_WINDOW_MS = 15000L;
    private static final String MODEL_ZIP = "vosk-model-small-cn-0.22.zip";
    private static final String MODEL_FOLDER = "vosk-model-small-cn-0.22";
    private static final long MAX_EXPANDED_BYTES = 340L * 1024 * 1024;

    private volatile boolean running;
    private volatile String status = "语音未启动";
    private volatile boolean speaking;
    private Thread worker;
    private volatile AudioRecord recording;
    private long wakeUntilMs;
    private long lastLevelBroadcastMs;
    private String lastPartial = "";
    private long speechSinceMs;
    private long lastPartialMs;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf(); return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STATUS.equals(intent.getAction())) {
            publish(null, false, null, 0f); return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "未授予麦克风权限";
            publish(null, false, null, 0f);
            stopSelf(); return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "REx 本地离线监听", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("REx · 本地离线监听")
            .setContentText("正在等待 Rex 唤醒 · 语音不会控制电机")
            .setContentIntent(open).setOngoing(true).build();
        try {
            startForeground(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (Exception e) {
            status = "麦克风服务启动失败：" + e.getClass().getSimpleName();
            publish(null, false, null, 0f);
            stopSelf(); return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            worker = new Thread(this::recognitionLoop, "rex-offline-vosk");
            worker.start();
        }
        publish(null, false, null, 0f);
        return START_NOT_STICKY;
    }

    private void updateStatus(String state) {
        status = state;
        publish(null, false, null, 0f);
    }

    /** Copies the model bundled INSIDE the APK to app-private storage on first start. */
    private File unpackBundledModel() throws Exception {
        File root = new File(getFilesDir(), "rex_offline_models");
        File dest = new File(root, MODEL_FOLDER);
        File ready = new File(dest, ".rex_model_ready_v3");
        if (ready.isFile() && new File(dest, "am/final.mdl").isFile()
                && new File(dest, "conf/model.conf").isFile()) return dest;
        updateStatus("首次启动 · 解压 APK 内置中文模型（只需一次）");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("无法创建模型目录");
        long expanded = 0;
        int entries = 0;
        try (InputStream source = getAssets().open(MODEL_ZIP);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry entry;
            byte[] buf = new byte[32768];
            while ((entry = zip.getNextEntry()) != null) {
                entries++;
                if (entries > 8000) throw new IllegalStateException("模型条目超出限制");
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith(MODEL_FOLDER + "/") || name.contains("../")
                        || name.startsWith("/")) throw new IllegalStateException("异常模型路径：" + name);
                File target = new File(root, name);
                // Canonical containment prevents ZIP slip.
                String rootPath = root.getCanonicalPath() + File.separator;
                if (!target.getCanonicalPath().startsWith(rootPath))
                    throw new IllegalStateException("模型路径越界");
                if (entry.isDirectory()) {
                    if (!target.exists() && !target.mkdirs()) throw new IllegalStateException("无法创建目录");
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs())
                        throw new IllegalStateException("无法创建子目录");
                    try (FileOutputStream out = new FileOutputStream(target)) {
                        int n;
                        while ((n = zip.read(buf)) != -1) {
                            expanded += n;
                            if (expanded > MAX_EXPANDED_BYTES)
                                throw new IllegalStateException("模型解压大小超限");
                            out.write(buf, 0, n);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
        if (!new File(dest, "am/final.mdl").isFile()
                || !new File(dest, "conf/model.conf").isFile())
            throw new IllegalStateException("内置模型不完整");
        if (!ready.createNewFile() && !ready.exists()) throw new IllegalStateException("模型完成标记失败");
        return dest;
    }

    private void recognitionLoop() {
        try {
            File modelDir = unpackBundledModel();
            if (!running) return;
            updateStatus("离线中文模型加载中（不联网）…");
            try (Model model = new Model(modelDir.getAbsolutePath());
                 Recognizer recognizer = new Recognizer(model, SAMPLE_RATE)) {
                int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) throw new IllegalStateException("麦克风不支持 16kHz");
                int size = Math.max(8192, min * 2);
                AudioRecord mic = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, size);
                recording = mic;
                if (mic.getState() != AudioRecord.STATE_INITIALIZED)
                    throw new IllegalStateException("AudioRecord 无法初始化");
                NoiseSuppressor suppressor = null;
                try {
                    if (NoiseSuppressor.isAvailable()) {
                        try {
                            suppressor = NoiseSuppressor.create(mic.getAudioSessionId());
                            if (suppressor != null) suppressor.setEnabled(true);
                        } catch (Exception unavailable) {
                            Log.w(TAG, "Platform noise suppressor not usable", unavailable);
                        }
                    }
                    mic.startRecording();
                    updateStatus("REx 本地离线监听中 · 请说 Rex / 瑞克斯");
                    short[] buffer = new short[3200]; // 200ms PCM16, never uploads audio
                    while (running) {
                        int count = mic.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                        if (!running) break;
                        if (count <= 0) {
                            if (count < 0) throw new IllegalStateException("麦克风读取异常 " + count);
                            continue;
                        }
                        float rms = rmsLevel(buffer, count);
                        // A recognized speech partial is needed before UI voice reactivity.
                        boolean endpoint = recognizer.acceptWaveForm(buffer, count);
                        if (endpoint) {
                            String finalText = getText(recognizer.getResult(), "text");
                            speaking = false;
                            lastPartial = "";
                            speechSinceMs = 0;
                            if (!finalText.isEmpty()) consume(finalText);
                            else if (SystemClock.elapsedRealtime() > wakeUntilMs)
                                updateStatus("正在等待 Rex 唤醒（本机离线）");
                        } else {
                            String part = getText(recognizer.getPartialResult(), "partial");
                            long now = SystemClock.elapsedRealtime();
                            // Noise alone (RMS without phonetic recognition) NEVER wakes UI.
                            if (!part.isEmpty() && rms > .045f) {
                                if (speechSinceMs == 0) speechSinceMs = now;
                                if (now - speechSinceMs >= 200) {
                                    speaking = true;
                                    if (!part.equals(lastPartial)) {
                                        lastPartial = part;
                                        lastPartialMs = now;
                                        status = "本机识别中：" + part;
                                    }
                                    if (now - lastLevelBroadcastMs >= 70) {
                                        lastLevelBroadcastMs = now;
                                        publish(null, false, null, Math.min(1f, rms * 4.2f));
                                    }
                                }
                            } else if (now - lastPartialMs > 1000) {
                                speaking = false;
                                speechSinceMs = 0;
                            }
                        }
                    }
                } finally {
                    try { mic.stop(); } catch (Exception ignored) { }
                    if (suppressor != null) suppressor.release();
                    mic.release();
                    recording = null;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Offline recognition failed", e);
            status = "本地识别错误：" + e.getClass().getSimpleName()
                + " " + String.valueOf(e.getMessage());
            publish(null, false, null, 0f);
            running = false;
            stopSelf();
        }
    }

    private static String getText(String json, String key) {
        try { return new JSONObject(json).optString(key, "").trim(); }
        catch (Exception ignored) { return ""; }
    }

    private static float rmsLevel(short[] data, int count) {
        double sum = 0;
        for (int i = 0; i < count; i++) {
            double x = data[i] / 32768.0;
            sum += x * x;
        }
        // 0..1 display energy; amplitude doesn't imply speech by itself.
        return (float)Math.min(1.0, Math.sqrt(sum / count) * 8.0);
    }

    private void consume(String heard) {
        long now = SystemClock.elapsedRealtime();
        String remainder = RexCommand.stripWakePrefix(heard);
        boolean wake = remainder != null;
        if (wake) wakeUntilMs = now + WAKE_WINDOW_MS;
        RexCommand command = null;
        if (wake) command = RexCommand.parse(remainder);
        else if (now < wakeUntilMs) command = RexCommand.parse(heard);
        if (command == RexCommand.WAKE) command = null;
        status = wake ? "REx 已唤醒 · 本地语音监听"
            : command != null ? "指令已识别 · 未通过本人声纹认证，未发射红外"
            : "离线识别到文字 · 非唤醒/非白名单指令";
        // Only wake/UI is affected. NO automatic infrared or ROS command.
        publish(heard, wake, command, 0f);
    }

    private void publish(String text, boolean wake, RexCommand command, float level) {
        Intent event = new Intent(ACTION_EVENT).setPackage(getPackageName());
        if (text != null) event.putExtra(EXTRA_TEXT, text);
        if (command != null) event.putExtra(EXTRA_COMMAND, command.name());
        event.putExtra(EXTRA_WAKE, wake);
        event.putExtra(EXTRA_LEVEL, level);
        event.putExtra(EXTRA_SPEAKING, speaking);
        event.putExtra(EXTRA_STATUS, status);
        sendBroadcast(event);
    }

    @Override public void onDestroy() {
        running = false;
        AudioRecord mic = recording;
        if (mic != null) { try { mic.stop(); } catch (Exception ignored) { } }
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
