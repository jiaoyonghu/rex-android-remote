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
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/**
 * Voice UI and command PREVIEW, not motor authorization. A recognizer transcribes words;
 * it does NOT distinguish the owner from another person, a loudspeaker or a recording.
 * In this release no SpeechRecognizer result is ever passed to NecIr.send().
 *
 * A microphone foreground service may keep running after the Activity is backgrounded.
 * SpeechRecognizer itself is not guaranteed to provide a reliable always-on hotword
 * implementation on ColorOS; errors are displayed rather than claimed away.
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
    public static final String PREF_ALLOW_SYSTEM = "allow_system_recognizer";
    public static final String PREF_FORCE_SYSTEM = "force_system_recognizer";
    private static final String CHANNEL = "rex_voice_v2";
    private static final long WAKE_WINDOW_MS = 12000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean running;
    private boolean listening;
    private boolean inSpeech;
    private int failures;
    private long wakeUntilMs;
    private long lastLevelMs;
    private int currentGeneration;
    private String status = "语音未启动";
    private boolean onDevice;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf(); return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STATUS.equals(intent.getAction())) {
            publish(null, false, null, 0f); return START_NOT_STICKY;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status="缺少麦克风权限"; publish(null,false,null,0f);
            stopSelf(); return START_NOT_STICKY;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "REx 语音监听", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notice = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("REx · 语音监听")
            .setContentText("听到唤醒词会切换界面 · 未通过本人声纹验证，不发送语音运动指令")
            .setContentIntent(open).setOngoing(true).build();
        try {
            startForeground(1001, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } catch (Exception e) {
            status="前台麦克风服务启动失败："+e.getClass().getSimpleName();
            publish(null,false,null,0f); stopSelf(); return START_NOT_STICKY;
        }
        if (!running) {
            running=true;
            handler.post(this::setupRecognizer);
        }
        publish(null,false,null,0f);
        return START_NOT_STICKY; // Continues while running; do not promise an OS restart
    }

    private void setStatus(String text) {
        status=text;
        publish(null,false,null,0f);
    }

    private void setupRecognizer() {
        if (!running) return;
        releaseRecognizer();
        // Default to true on-device. If not available, use the system recognition
        // service ONLY when the user has explicitly agreed to that possible network use.
        boolean local = Build.VERSION.SDK_INT >= 31
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
        boolean allowSystem = getSharedPreferences("rex_settings",MODE_PRIVATE)
                .getBoolean(PREF_ALLOW_SYSTEM,false);
        if (!local && !allowSystem) {
            setStatus("未检测到专用离线语音引擎；请开启系统识别授权");
            return;
        }
        if ((!local || getSharedPreferences("rex_settings",MODE_PRIVATE)
             .getBoolean(PREF_FORCE_SYSTEM,false)) &&
             !SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("手机没有可用的系统语音识别服务；请安装中文语音服务");
            return;
        }
        boolean forceSystem=getSharedPreferences("rex_settings",MODE_PRIVATE)
            .getBoolean(PREF_FORCE_SYSTEM,false);
        if(forceSystem&&!allowSystem){setStatus("尚未授权系统语音识别");return;}
        onDevice=local&&!forceSystem;
        try {
            recognizer = onDevice ? SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                                  : SpeechRecognizer.createSpeechRecognizer(this);
            final int generation=++currentGeneration;
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle p) {
                    if (generation != currentGeneration) return;
                    inSpeech=false;
                    setStatus("正在听 · 等待你说 Rex / 瑞克斯");
                }
                @Override public void onBeginningOfSpeech() {
                    if (generation != currentGeneration) return;
                    inSpeech=true;
                    setStatus("检测到人声 · 正在聆听");
                }
                @Override public void onRmsChanged(float rms) {
                    if (generation != currentGeneration || !inSpeech) return;
                    long now=SystemClock.elapsedRealtime();
                    if (now-lastLevelMs<55) return;
                    lastLevelMs=now;
                    float level=Math.max(0f,Math.min(1f,(rms+1f)/10f));
                    publish(null,false,null,level);
                }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() {
                    if (generation != currentGeneration) return;
                    inSpeech=false;
                    setStatus("句尾已检测 · 等待最终识别结果");
                }
                @Override public void onError(int error) {
                    if (generation != currentGeneration) return;
                    listening=false; inSpeech=false;
                    // Speech timeout/no-match are normal in a quiet room, not failures.
                    boolean silence = error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                        error==SpeechRecognizer.ERROR_NO_MATCH;
                    if(silence) failures=0; else failures++;
                    String detail=errorName(error);
                    setStatus(silence?"正在监听 · "+detail:
                        "语音服务错误："+detail+"（重试 "+failures+"）");
                    if(silence) { scheduleListening(320); return; }
                    // Do not hammer a recognizer permanently reporting permissions/errors.
                    if (error==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        setStatus("麦克风权限不可用，已停止自动重试"); return;
                    }
                    if(onDevice && (error==SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error==SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                        setStatus("本机中文离线语音模型不可用；请安装模型或授权系统识别");
                        return;
                    }
                    if (error==SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                        error==SpeechRecognizer.ERROR_CLIENT ||
                        error==SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                        long delay=Math.min(12000,1000L*(1L<<Math.min(failures,3)));
                        handler.postDelayed(VoiceService.this::setupRecognizer,delay);
                    } else {
                        scheduleListening(Math.min(9000,450L*(1L<<Math.min(failures,4))));
                    }
                }
                @Override public void onResults(Bundle results) {
                    if (generation != currentGeneration) return;
                    listening=false;inSpeech=false;
                    failures=0;
                    consume(results);
                    scheduleListening(350);
                }
                @Override public void onPartialResults(Bundle partial) {
                    if (generation != currentGeneration) return;
                    // Not authoritative; don't execute commands or trigger wake here.
                    if (!inSpeech) return;
                    ArrayList<String> partialTexts=partial==null?null:
                        partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (partialTexts!=null&&!partialTexts.isEmpty()) {
                        status="识别中："+partialTexts.get(0);
                        publish(null,false,null,0f);
                    }
                }
                @Override public void onEvent(int e, Bundle p) { }
            });
            setStatus((onDevice?"手机离线识别":"系统语音识别（可能联网）")+" · 初始化完成");
            scheduleListening(200);
        } catch (Exception e) {
            setStatus("创建识别器失败："+e.getClass().getSimpleName());
        }
    }

    private void consume(Bundle bundle) {
        ArrayList<String> texts=bundle==null?null:
            bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (texts==null || texts.isEmpty()) {
            setStatus("没有识别到可用文字");return;
        }
        // Use final alternatives to handle Rex being transcribed as 瑞克斯/雷克斯.
        // Partial hypotheses are NEVER accepted as final commands.
        String text=texts.get(0).trim();
        long now=SystemClock.elapsedRealtime();
        for(String alternative:texts) {
            if(RexCommand.stripWakePrefix(alternative)!=null) {
                text=alternative.trim(); break;
            }
            if(now<wakeUntilMs && RexCommand.parse(alternative)!=null) {
                text=alternative.trim(); break;
            }
        }
        String rest=RexCommand.stripWakePrefix(text);
        boolean woke=rest!=null;
        if (woke) wakeUntilMs=now+WAKE_WINDOW_MS;
        RexCommand command=null;
        if (woke) {
            command=RexCommand.parse(rest);
        } else if (now<wakeUntilMs) {
            command=RexCommand.parse(text);
            wakeUntilMs=now+WAKE_WINDOW_MS;
        }
        if (command==RexCommand.WAKE) command=null;
        // No infrared/motor access: text cannot prove a human speaker's identity.
        status=woke?"REx 已唤醒 · 等待指令":
            command!=null?"识别完成 · 本人声纹尚未验证，未发射红外":
            "识别完成 · 未匹配命令，或未唤醒";
        publish(text,woke,command,0f);
    }

    private void scheduleListening(long delay) {
        handler.postDelayed(() -> {
            if (!running || recognizer==null || listening) return;
            Intent request=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN");
            request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"zh-CN");
            request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
            request.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3);
            request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
            // Best effort only: speech service may ignore silence timings.
            request.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,850L);
            request.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,650L);
            request.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,350L);
            try {
                listening=true;
                recognizer.startListening(request);
                setStatus("正在启动麦克风识别 …");
            } catch (Exception ex) {
                listening=false;
                setStatus("开始识别失败："+ex.getClass().getSimpleName());
                handler.postDelayed(this::setupRecognizer,1500);
            }
        },delay);
    }

    private static String errorName(int code) {
        switch(code) {
            case SpeechRecognizer.ERROR_AUDIO: return "麦克风音频错误(3)";
            case SpeechRecognizer.ERROR_CLIENT: return "客户端异常(5)";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "权限不足(9)";
            case SpeechRecognizer.ERROR_NETWORK: return "网络错误(2)";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "网络超时(1)";
            case SpeechRecognizer.ERROR_NO_MATCH: return "没有识别到语音(7)";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "识别器忙(8)";
            case SpeechRecognizer.ERROR_SERVER: return "语音引擎错误(4)";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "没有检测到说话(6)";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED: return "服务断开(11)";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "中文不支持(12)";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "中文模型未安装(13)";
            default: return "代码 "+code;
        }
    }

    private void publish(String text,boolean wake,RexCommand command,float level) {
        Intent event=new Intent(ACTION_EVENT).setPackage(getPackageName());
        if (text!=null) event.putExtra(EXTRA_TEXT,text);
        if (command!=null) event.putExtra(EXTRA_COMMAND,command.name());
        event.putExtra(EXTRA_WAKE,wake);
        event.putExtra(EXTRA_LEVEL,level);
        event.putExtra(EXTRA_SPEAKING,inSpeech);
        event.putExtra(EXTRA_STATUS,status);
        sendBroadcast(event);
    }

    private void releaseRecognizer() {
        ++currentGeneration;
        listening=false;
        inSpeech=false;
        if (recognizer!=null) {
            try { recognizer.cancel(); recognizer.destroy(); } catch(Exception ignored) { }
            recognizer=null;
        }
    }
    @Override public void onDestroy() {
        running=false;
        handler.removeCallbacksAndMessages(null);
        releaseRecognizer();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
