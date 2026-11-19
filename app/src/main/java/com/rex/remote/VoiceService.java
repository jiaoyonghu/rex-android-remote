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
 * REx v0.4: IN-APP OFFLINE Mandarin + English ASR (two Vosk decoders).
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
    private static final String CHANNEL = "rex_offline_voice_v4";
    private static final String TAG = "RExOfflineVoice";
    private static final int SAMPLE_RATE = 16000;
    private static final long WAKE_WINDOW_MS = 15000L;
    private static final String ZH_MODEL_ZIP = "vosk-model-small-cn-0.22.zip";
    private static final String ZH_MODEL_FOLDER = "vosk-model-small-cn-0.22";
    private static final String EN_MODEL_ZIP = "vosk-model-small-en-us-0.15.zip";
    private static final String EN_MODEL_FOLDER = "vosk-model-small-en-us-0.15";
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
    private String lastFinalSignature = "";
    private long lastFinalMs;

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
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "REx 中英双语离线监听", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("REx · 中英双语离线监听")
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

    /** Copies one prepackaged Vosk ZIP into app-private storage on first use. */
    private File unpackBundledModel(String zipName, String folder, String language) throws Exception {
        File root = new File(getFilesDir(), "rex_offline_models");
        File dest = new File(root, folder);
        File ready = new File(dest, ".rex_model_ready_v4");
        boolean valid = new File(dest, "am/final.mdl").isFile()
            && new File(dest, "conf/model.conf").isFile();
        if (valid && (ready.isFile() || new File(dest, ".rex_model_ready_v3").isFile())) {
            if (!ready.isFile()) ready.createNewFile();
            return dest;
        }
        updateStatus("首次运行 · 解压内置" + language + "语音模型");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("无法创建模型目录");
        long expanded = 0;
        int entries = 0;
        try (InputStream source = getAssets().open(zipName);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
            ZipEntry entry;
            byte[] buf = new byte[32768];
            String rootPath = root.getCanonicalPath() + File.separator;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 8000) throw new IllegalStateException("模型条目超出限制");
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith(folder + "/") || name.contains("../") || name.startsWith("/"))
                    throw new IllegalStateException("异常模型路径：" + name);
                File target = new File(root, name);
                if (!target.getCanonicalPath().startsWith(rootPath))
                    throw new IllegalStateException("模型路径越界");
                if (entry.isDirectory()) {
                    if (!target.exists() && !target.mkdirs())
                        throw new IllegalStateException("模型目录创建失败");
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs())
                        throw new IllegalStateException("模型子目录创建失败");
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
            throw new IllegalStateException(language + "内置模型不完整");
        if (!ready.createNewFile() && !ready.exists())
            throw new IllegalStateException("模型完成标记失败");
        return dest;
    }

    private void recognitionLoop() {
        try {
            File zhDir = unpackBundledModel(ZH_MODEL_ZIP, ZH_MODEL_FOLDER, "中文");
            if (!running) return;
            File enDir = unpackBundledModel(EN_MODEL_ZIP, EN_MODEL_FOLDER, "英文");
            if (!running) return;
            updateStatus("正在加载中文 + English 双模型（全离线）…");
            try (Model zhModel = new Model(zhDir.getAbsolutePath());
                 Model enModel = new Model(enDir.getAbsolutePath());
                 Recognizer zh = new Recognizer(zhModel, SAMPLE_RATE);
                 Recognizer en = new Recognizer(enModel, SAMPLE_RATE)) {
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
                    updateStatus("中英双语离线监听中 · Rex / 瑞克斯");
                    short[] buffer = new short[3200]; // single PCM stream to BOTH recognizers
                    String finalZh = "", finalEn = "";
                    boolean endedZh = false, endedEn = false;
                    long pendingSince = 0;
                    while (running) {
                        int count = mic.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                        if (!running) break;
                        if (count <= 0) {
                            if (count < 0) throw new IllegalStateException("麦克风读取异常 " + count);
                            continue;
                        }
                        float rms = rmsLevel(buffer, count);
                        long now = SystemClock.elapsedRealtime();
                        boolean zhEnd = zh.acceptWaveForm(buffer, count);
                        boolean enEnd = en.acceptWaveForm(buffer, count);
                        String zhPart = "", enPart = "";
                        if (zhEnd) {
                            finalZh = getText(zh.getResult(), "text");
                            endedZh = true;
                        } else if (!endedZh) {
                            zhPart = getText(zh.getPartialResult(), "partial");
                        }
                        if (enEnd) {
                            finalEn = getText(en.getResult(), "text");
                            endedEn = true;
                        } else if (!endedEn) {
                            enPart = getText(en.getPartialResult(), "partial");
                        }
                        // One ASR endpoint often arrives before the other. Allow the
                        // second model 800 ms to finish; retain its latest partial.
                        if ((zhEnd || enEnd) && pendingSince == 0) pendingSince = now;
                        if (pendingSince > 0 && ((endedZh && endedEn)
                                || now - pendingSince >= 800)) {
                            String z = endedZh ? finalZh : zhPart;
                            String e = endedEn ? finalEn : enPart;
                            if (!z.isEmpty() || !e.isEmpty()) consumePair(z, e);
                            endedZh = false; endedEn = false;
                            finalZh = ""; finalEn = ""; pendingSince = 0;
                            speaking = false; lastPartial = ""; speechSinceMs = 0;
                            if (z.isEmpty() && e.isEmpty() && now > wakeUntilMs)
                                updateStatus("正在等待 Rex / 瑞克斯（中英双语离线）");
                        }
                        String part = !zhPart.isEmpty() ? zhPart : enPart;
                        // Ambient volume alone cannot make the UI recognize a wake word.
                        if (!part.isEmpty() && rms > .045f) {
                            if (speechSinceMs == 0) speechSinceMs = now;
                            if (now - speechSinceMs >= 200) {
                                speaking = true;
                                if (!part.equals(lastPartial)) {
                                    lastPartial = part;
                                    lastPartialMs = now;
                                    status = "中英双语识别中：" + part;
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
                } finally {
                    try { mic.stop(); } catch (Exception ignored) { }
                    if (suppressor != null) suppressor.release();
                    mic.release();
                    recording = null;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Bilingual offline recognition failed", e);
            status = "中英离线识别错误：" + e.getClass().getSimpleName()
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

    private void consumePair(String chinese, String english) {
        long now = SystemClock.elapsedRealtime();
        String fingerprint = RexCommand.norm(chinese) + "|" + RexCommand.norm(english);
        if (fingerprint.equals(lastFinalSignature) && now - lastFinalMs < 1800) return;
        lastFinalSignature = fingerprint;
        lastFinalMs = now;
        BilingualCommandRouter.Match match = BilingualCommandRouter.resolve(
            chinese, english, now < wakeUntilMs);
        if (match.woke) wakeUntilMs = now + WAKE_WINDOW_MS;
        status = match.woke ? "REx 已唤醒 · 中文 / English 均可"
            : match.command != null ? "已识别指令 · 未通过本人声纹认证，不发射红外"
            : "双语文字已识别 · 非唤醒/非白名单命令";
        // Visualization + transcript only; no NecIr.send(), no ROS motor command.
        publish(match.display(), match.woke, match.command, 0f);
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
