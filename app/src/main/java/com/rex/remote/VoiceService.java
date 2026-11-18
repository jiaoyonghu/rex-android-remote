package com.rex.remote;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Voice preview ONLY: no IR transmission. On-device recognizer is not a continuous
 * wake-word engine and does not verify speaker identity. Voice actuation intentionally
 * disabled until a real owner-enrollment/anti-spoofing pipeline is integrated.
 */
public final class VoiceService extends Service {
    public static final String ACTION_EVENT = "com.rex.remote.VOICE_EVENT";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_WAKE = "wake";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_LEVEL = "level";
    private static final String CHANNEL = "rex_voice";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean running;
    private boolean listening;
    private boolean inSpeech;
    private long wakeUntilMs;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "REx 语音监听", NotificationManager.IMPORTANCE_LOW));
        Notification notice = new Notification.Builder(this, CHANNEL)
            .setContentTitle("REx · 正在监听（语音预览）")
            .setContentText("主人声纹未验证：语音不会控制机器人")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build();
        try {
            startForeground(1001, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (Exception ex) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            handler.post(this::startEngine);
        }
        return START_NOT_STICKY;
    }

    private void startEngine() {
        if (!running) return;
        if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            dispatch("手机未提供可用的离线语音识别服务", false, null, 0f);
            return; // Never silently send audio to an online speech service.
        }
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { inSpeech=false; }
            @Override public void onBeginningOfSpeech() { inSpeech=true; }
            @Override public void onRmsChanged(float rms) {
                // Only animate after recognition service reports speech onset.
                if (inSpeech) dispatch(null, false, null, Math.max(0f, Math.min(1f, (rms + 2f) / 12f)));
            }
            @Override public void onBufferReceived(byte[] p) { }
            @Override public void onEndOfSpeech() { inSpeech=false; }
            @Override public void onError(int error) { listening=false; retry(900); }
            @Override public void onResults(Bundle results) { listening=false; consume(results); retry(400); }
            @Override public void onPartialResults(Bundle partial) { /* do not execute partial speech */ }
            @Override public void onEvent(int e, Bundle p) { }
        });
        retry(200);
    }

    private void consume(Bundle results) {
        ArrayList<String> texts = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (texts == null || texts.isEmpty()) return;
        String text = texts.get(0).trim();
        String norm = text.toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？,.!?]", "");
        boolean woke = norm.contains("rex");
        if (woke) wakeUntilMs = android.os.SystemClock.elapsedRealtime() + 7000L;
        String withoutWake = norm.replace("rex醒了吗", "").replace("rex", "");
        RexCommand cmd = RexCommand.parse(withoutWake.isEmpty() ? norm : withoutWake);
        if (cmd == RexCommand.WAKE) cmd = null;
        if (android.os.SystemClock.elapsedRealtime() > wakeUntilMs) cmd = null;
        dispatch(text, woke, cmd, 0.6f);
        // SECURITY: No send() here, because recognizer cannot authenticate the owner.
    }

    private void dispatch(String text, boolean wake, RexCommand cmd, float level) {
        Intent i = new Intent(ACTION_EVENT).setPackage(getPackageName());
        if (text != null) i.putExtra(EXTRA_TEXT, text);
        if (cmd != null) i.putExtra(EXTRA_COMMAND, cmd.name());
        i.putExtra(EXTRA_WAKE, wake);
        i.putExtra(EXTRA_LEVEL, level);
        sendBroadcast(i);
    }

    private void retry(long delayMs) {
        handler.postDelayed(() -> {
            if (!running || recognizer == null || listening) return;
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            try { listening = true; recognizer.startListening(i); }
            catch (Exception ex) { listening=false; dispatch("语音监听暂不可用", false, null, 0f); }
        }, delayMs);
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer=null; }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
