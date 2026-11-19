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
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.SpeakerModel;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * REx v0.6: in-app bilingual Vosk ASR, speech-likeness-gated live visuals,
 * no wake timeout and guided phrase-by-phrase speaker enrollment. All spoken robot
 * commands are PREVIEW ONLY: absolutely no voice-to-IR and no robot motors.
 */
public final class VoiceService extends Service {
    public static final String ACTION_EVENT="com.rex.remote.VOICE_EVENT";
    public static final String ACTION_STOP="com.rex.remote.STOP_VOICE";
    public static final String ACTION_ENROLL_BEGIN="com.rex.remote.ENROLL_BEGIN";
    public static final String ACTION_ENROLL_CAPTURE="com.rex.remote.ENROLL_CAPTURE";
    public static final String ACTION_ENROLL_NEXT="com.rex.remote.ENROLL_NEXT";
    public static final String ACTION_ENROLL_FINISH="com.rex.remote.ENROLL_FINISH";
    public static final String ACTION_ENROLL_CLEAR="com.rex.remote.ENROLL_CLEAR";
    public static final String ACTION_ENROLL_CANCEL="com.rex.remote.ENROLL_CANCEL";
    public static final String EXTRA_TEXT="text";
    public static final String EXTRA_PARTIAL="partial";
    public static final String EXTRA_WAKE="wake";
    public static final String EXTRA_AWAKE="awake";
    public static final String EXTRA_COMMAND="command";
    public static final String EXTRA_LEVEL="level";
    public static final String EXTRA_STATUS="status";
    public static final String EXTRA_SPEAKING="speaking";
    public static final String EXTRA_OWNER="owner";
    public static final String EXTRA_ENROLL_COUNT="enroll_count";
    public static final String EXTRA_ENROLL_STEP="enroll_step";
    public static final String EXTRA_ENROLL_READY="enroll_ready";
    public static final String EXTRA_ENROLL_CAPTURING="enroll_capturing";
    public static final String EXTRA_ENROLL_EVENT="enroll_event";

    private static final String CHANNEL="rex_offline_voice_v6";
    private static final String TAG="RExOfflineVoice";
    private static final int SAMPLE_RATE=16000;
    private static final String ZH_MODEL_ZIP="vosk-model-small-cn-0.22.zip";
    private static final String ZH_MODEL_FOLDER="vosk-model-small-cn-0.22";
    private static final String EN_MODEL_ZIP="vosk-model-small-en-us-0.15.zip";
    private static final String EN_MODEL_FOLDER="vosk-model-small-en-us-0.15";
    private static final String SPEAKER_ZIP="vosk-model-spk-0.4.zip";
    private static final String SPEAKER_FOLDER="vosk-model-spk-0.4";
    private static final long MAX_EXPANDED_BYTES=360L*1024*1024;

    private volatile boolean running;
    private volatile boolean awake; // NEVER auto-expires. Only the spoken word 睡觉 resets it.
    private volatile boolean speaking;
    private volatile String status="语音未启动";
    private volatile String ownerStatus="尚未录入声纹";
    private volatile AudioRecord recording;
    private Thread worker;
    private SpeakerProfile profile;
    private long lastRealtimeBroadcastMs;
    private volatile long lastVoiceOnsetMs;
    private long lastFinalMs;
    private String lastFinalSignature="";
    private volatile long lastOwnerVerifiedMs;
    private final SpeechActivityGate activityGate=new SpeechActivityGate();
    private final Object enrollLock=new Object();
    private int enrollStep;
    private boolean enrollArmed,enrollReady;
    private long enrollDeadline;
    private static final long CAPTURE_TIMEOUT_MS=19000;
    private String lastPartialSent="";

    @Override public void onCreate(){super.onCreate();profile=new SpeakerProfile(this);
        if(profile.hasProfile())ownerStatus="已录入声纹 · 待验证";
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?"":intent.getAction();
        if(ACTION_STOP.equals(action)){stopSelf();return START_NOT_STICKY;}
        int requestedStep=intent==null?0:intent.getIntExtra(EXTRA_ENROLL_STEP,0);
        if(ACTION_ENROLL_CAPTURE.equals(action)){
            synchronized(enrollLock){
                if(profile.isEnrolling() && requestedStep==enrollStep && !enrollReady){
                    enrollArmed=true;
                    enrollDeadline=SystemClock.elapsedRealtime()+CAPTURE_TIMEOUT_MS;
                    status="正在采集第 "+(enrollStep+1)+" 条，请朗读提示词并自然停顿";
                    publishEnrollment("CAPTURE_STARTED");
                }else{
                    status="采集未开始：请确认正在当前声纹提示词页面";
                    publishEnrollment("CAPTURE_FAIL");
                }
            }
            return START_NOT_STICKY;
        }
        if(ACTION_ENROLL_NEXT.equals(action)){
            synchronized(enrollLock){
                if(profile.isEnrolling() && requestedStep==enrollStep && enrollReady){
                    enrollReady=false;enrollArmed=false;
                    enrollStep++;
                    if(enrollStep>=EnrollmentPrompts.count()){
                        if(profile.finish()){
                            ownerStatus="已保存主人声纹特征（实验性）";
                            status="全部 "+EnrollmentPrompts.count()+" 条提示词采集成功 · 声纹录入结束";
                            publishEnrollment("ENROLL_DONE");
                        }else{
                            enrollStep=EnrollmentPrompts.count()-1;
                            enrollReady=true; // allow retry of persist without duplicate capture
                            status="声纹保存失败，请再次点击『完成』重试保存";
                            publishEnrollment("CAPTURE_FAIL");
                        }
                    }else{
                        status="第 "+enrollStep+" 条完成 · 请点击『采集』朗读下一条";
                        publishEnrollment("STEP_ADVANCED");
                    }
                }else{
                    status="本条提示词尚未成功采集，不能完成";
                    publishEnrollment("CAPTURE_FAIL");
                }
            }
            return START_NOT_STICKY;
        }
        if(ACTION_ENROLL_CLEAR.equals(action)){
            synchronized(enrollLock){
                profile.clear();enrollArmed=false;enrollReady=false;enrollStep=0;
                lastOwnerVerifiedMs=0;
                ownerStatus="声纹已清除";status="已删除本机声纹特征";
                publishEnrollment("ENROLL_CLEARED");
            }
            if(!running)stopSelf();return START_NOT_STICKY;
        }
        if(ACTION_ENROLL_CANCEL.equals(action)){
            synchronized(enrollLock){
                profile.abandon();enrollArmed=false;enrollReady=false;enrollStep=0;
                status="已取消本次声纹采集，原有声纹保持不变";
                publishEnrollment("ENROLL_CANCELLED");
            }
            if(!running)stopSelf();return START_NOT_STICKY;
        }
        if(ACTION_ENROLL_BEGIN.equals(action)){
            synchronized(enrollLock){
                profile.begin();enrollArmed=false;enrollReady=false;enrollStep=0;
                lastOwnerVerifiedMs=0;
                ownerStatus="声纹录入进行中：0/"+EnrollmentPrompts.count();
                status="声纹第 1 条：点击『采集』后朗读屏幕提示词";
                publishEnrollment("ENROLL_BEGIN");
            }
        }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            status="未授权麦克风";publish(null,null,null,false,0f);
            stopSelf();return START_NOT_STICKY;
        }
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"REx 本地双语 + 声纹",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,
            new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("REx · 离线实时监听")
            .setContentText("持续监听直到手动停止 · 声纹试验版 · 不控制电机")
            .setContentIntent(open).setOngoing(true).build();
        try {startForeground(1001,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);}
        catch(Exception e){status="麦克风服务启动失败："+e.getClass().getSimpleName();
            publish(null,null,null,false,0f);stopSelf();return START_NOT_STICKY;}
        if(!running){running=true;worker=new Thread(this::recognitionLoop,"rex-bilingual-voice-v6");worker.start();}
        publish(null,null,null,false,0f);
        return START_NOT_STICKY;
    }
    private void updateStatus(String value){status=value;publish(null,null,null,false,0f);}

    /** Copies one prepackaged Vosk ZIP into app-private storage on first use. */
    private File unpackBundledModel(String zipName, String folder, String language) throws Exception {
        File root = new File(getFilesDir(), "rex_offline_models");
        File dest = new File(root, folder);
        File ready = new File(dest, ".rex_model_ready_v5");
        boolean spk = folder.equals(SPEAKER_FOLDER);
        boolean valid = spk
            ? new File(dest,"mfcc.conf").isFile() && new File(dest,"final.ext.raw").isFile()
                && new File(dest,"mean.vec").isFile() && new File(dest,"transform.mat").isFile()
            : new File(dest,"am/final.mdl").isFile() && new File(dest,"conf/model.conf").isFile();
        if (valid && (ready.isFile() || new File(dest, ".rex_model_ready_v4").isFile())) {
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
        if (!(spk ? (new File(dest,"mfcc.conf").isFile() && new File(dest,"final.ext.raw").isFile()
                && new File(dest,"mean.vec").isFile() && new File(dest,"transform.mat").isFile())
                : (new File(dest,"am/final.mdl").isFile()
                && new File(dest,"conf/model.conf").isFile())))
            throw new IllegalStateException(language + "内置模型不完整");
        if (!ready.createNewFile() && !ready.exists())
            throw new IllegalStateException("模型完成标记失败");
        return dest;
    }

    private void recognitionLoop(){
        try {
            File zhDir=unpackBundledModel(ZH_MODEL_ZIP,ZH_MODEL_FOLDER,"中文");
            if(!running)return;
            File enDir=unpackBundledModel(EN_MODEL_ZIP,EN_MODEL_FOLDER,"英文");
            if(!running)return;
            File speakerDir=unpackBundledModel(SPEAKER_ZIP,SPEAKER_FOLDER,"声纹");
            if(!running)return;
            updateStatus("正在加载中文 + 英文 + 声纹 3 套离线模型…");
            try(Model zhModel=new Model(zhDir.getAbsolutePath());
                Model enModel=new Model(enDir.getAbsolutePath());
                SpeakerModel speakerModel=new SpeakerModel(speakerDir.getAbsolutePath());
                Recognizer zh=new Recognizer(zhModel,SAMPLE_RATE);
                Recognizer en=new Recognizer(enModel,SAMPLE_RATE)) {
                zh.setSpeakerModel(speakerModel);
                en.setSpeakerModel(speakerModel);
                int minimum=AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(minimum<=0)throw new IllegalStateException("不支持 16kHz 麦克风");
                AudioRecord mic=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,Math.max(minimum*2,8192));
                recording=mic;
                if(mic.getState()!=AudioRecord.STATE_INITIALIZED)
                    throw new IllegalStateException("AudioRecord 初始化失败");
                NoiseSuppressor ns=null;
                AutomaticGainControl agc=null;
                try {
                    try{if(NoiseSuppressor.isAvailable()){
                        ns=NoiseSuppressor.create(mic.getAudioSessionId());
                        if(ns!=null)ns.setEnabled(true);
                    }}catch(Exception e){Log.w(TAG,"NoiseSuppressor unavailable",e);}
                    try{if(AutomaticGainControl.isAvailable()){
                        agc=AutomaticGainControl.create(mic.getAudioSessionId());
                        if(agc!=null)agc.setEnabled(true);
                    }}catch(Exception e){Log.w(TAG,"AGC unavailable",e);}
                    mic.startRecording();
                    updateStatus("离线实时监听 · 叫 Rex 或 灭建国 唤醒");
                    // Audio capture is a SEPARATE thread from the potentially
                    // expensive CN/EN decoders. PCM animation runs even if ASR lags.
                    ArrayBlockingQueue<short[]> audioQueue=new ArrayBlockingQueue<>(32);
                    Thread capture=new Thread(()->capturePcm(mic,audioQueue),"rex-live-mic");
                    capture.start();
                    String finalZh="",finalEn="";
                    SpeakerEvidence zhSpk=null,enSpk=null;
                    boolean endedZh=false,endedEn=false;
                    long pendingSince=0;
                    String latestZhPartial="",latestEnPartial="";
                    while(running){
                        short[] samples=audioQueue.poll(350,TimeUnit.MILLISECONDS);
                        if(!running)break;
                        if(samples==null)continue;
                        int count=samples.length;
                        long now=SystemClock.elapsedRealtime();
                        // Decode audio from the queue; visualization is independent.
                        boolean zhDone=zh.acceptWaveForm(samples,count);
                        boolean enDone=en.acceptWaveForm(samples,count);
                        if(zhDone){
                            String raw=zh.getResult();finalZh=getText(raw,"text");
                            zhSpk=evidenceFrom(raw);endedZh=true;latestZhPartial="";
                        }else if(!endedZh)latestZhPartial=getText(zh.getPartialResult(),"partial");
                        if(enDone){
                            String raw=en.getResult();finalEn=getText(raw,"text");
                            enSpk=evidenceFrom(raw);endedEn=true;latestEnPartial="";
                        }else if(!endedEn)latestEnPartial=getText(en.getPartialResult(),"partial");
                        if((zhDone||enDone) && pendingSince==0)pendingSince=now;
                        String partial=!latestZhPartial.isEmpty()?"中: "+latestZhPartial:
                            (!latestEnPartial.isEmpty()?"EN: "+latestEnPartial:"");
                        if(!partial.isEmpty() && !partial.equals(lastPartialSent)){
                            lastPartialSent=partial;
                            publish(null,partial,null,false,0f);
                        }
                        if(pendingSince>0 && ((endedZh && endedEn)||now-pendingSince>=750)){
                            String z=endedZh?finalZh:latestZhPartial;
                            String e=endedEn?finalEn:latestEnPartial;
                            if(!z.isEmpty() || !e.isEmpty())consumePair(z,e,zhSpk,enSpk);
                            finalZh="";finalEn="";zhSpk=null;enSpk=null;
                            endedZh=false;endedEn=false;pendingSince=0;
                            latestZhPartial="";latestEnPartial="";lastPartialSent="";
                        }
                    }
                }finally{
                    try{mic.stop();}catch(Exception ignored){}
                    if(ns!=null)ns.release();
                    if(agc!=null)agc.release();
                    mic.release();recording=null;
                }
            }
        }catch(Exception e){
            if(!running)return;
            Log.e(TAG,"REx Vosk recording failure",e);
            status="离线语音失败："+e.getClass().getSimpleName()+" "+String.valueOf(e.getMessage());
            publish(null,null,null,false,0f);
            running=false;stopSelf();
        }
    }

    /** Real-time PCM thread: samples + publishes level BEFORE any ASR decoding. */
    private void capturePcm(AudioRecord mic,ArrayBlockingQueue<short[]> queue){
        short[] reusable=new short[800]; // 50ms, 16kHz mono
        try{
            while(running){
                int count=mic.read(reusable,0,reusable.length,AudioRecord.READ_BLOCKING);
                if(!running)break;
                if(count<0)throw new IllegalStateException("mic.read failed " + count);
                if(count==0)continue;
                float energy=activityGate.process(reusable,count);
                boolean wasSpeaking=speaking;
                speaking=activityGate.isActive();
                long now=SystemClock.elapsedRealtime();
                if(!wasSpeaking && speaking)lastVoiceOnsetMs=now;
                synchronized(enrollLock){
                    if(profile.isEnrolling() && enrollArmed && now>=enrollDeadline){
                        enrollArmed=false;
                        status="采集超时：未得到完整提示词，请点击『采集』重试";
                        publishEnrollment("CAPTURE_FAIL");
                    }
                }
                if(now-lastRealtimeBroadcastMs>=65){
                    lastRealtimeBroadcastMs=now;
                    publish(null,null,null,false,energy);
                }
                short[] samples=new short[count];
                System.arraycopy(reusable,0,samples,0,count);
                if(!queue.offer(samples)){
                    // Avoid indefinite stale audio: drop one OLD frame under load.
                    queue.poll();queue.offer(samples);
                }
            }
        }catch(Exception e){
            if(running){
                Log.e(TAG,"Live microphone capture stopped",e);
                status="麦克风录音中断："+String.valueOf(e.getMessage());
                publish(null,null,null,false,0f);
                running=false;stopSelf();
            }
        }
    }

    private static final class SpeakerEvidence {
        final float[] vector; final int frames;
        SpeakerEvidence(float[] vec,int n){vector=vec;frames=n;}
    }
    private static SpeakerEvidence evidenceFrom(String raw) {
        try {
            JSONObject obj=new JSONObject(raw);
            JSONArray a=obj.optJSONArray("spk");
            int frames=obj.optInt("spk_frames",0);
            // Speaker vectors from very short utterances are not reliable.
            if(a==null || a.length()<64 || a.length()>512 || frames<90)return null;
            float[] values=new float[a.length()];
            for(int i=0;i<values.length;i++)values[i]=(float)a.getDouble(i);
            float[] vec=SpeakerMath.unit(values);
            return vec==null?null:new SpeakerEvidence(vec,frames);
        }catch(Exception e){return null;}
    }
    private static String getText(String json,String key){
        try{return new JSONObject(json).optString(key,"").trim();}
        catch(Exception e){return "";}
    }
    private void consumePair(String zhText,String enText,SpeakerEvidence zhSpk,SpeakerEvidence enSpk){
        long now=SystemClock.elapsedRealtime();
        String fingerprint=RexCommand.norm(zhText)+"|"+RexCommand.norm(enText);
        if(fingerprint.equals("|") ||
            (!profile.isEnrolling() && fingerprint.equals(lastFinalSignature)
                && now-lastFinalMs<1250))return;
        lastFinalSignature=fingerprint;lastFinalMs=now;
        SpeakerEvidence ev=zhSpk;
        if(ev==null || (enSpk!=null && enSpk.frames>ev.frames))ev=enSpk;
        if(profile.isEnrolling()){
            synchronized(enrollLock){
                if(!enrollArmed || enrollReady){
                    // Ignore ambient ASR while the user has not tapped Collect.
                    return;
                }
                enrollArmed=false;
                boolean phraseCorrect=EnrollmentPrompts.matches(enrollStep,zhText,enText);
                boolean usableSpeaker=ev!=null;
                String heard="中: "+zhText+" | EN: "+enText;
                if(phraseCorrect && usableSpeaker && profile.add(ev.vector)){
                    enrollReady=true;
                    ownerStatus="第 "+(enrollStep+1)+"/"+EnrollmentPrompts.count()+" 条：声纹采集成功";
                    status="采集成功：文字核对通过 + 已提取声纹；点击『完成』";
                    publish(heard,null,null,false,0f,"CAPTURE_OK");
                }else{
                    if(!phraseCorrect)
                        status="提示词不匹配：请完整朗读『"+EnrollmentPrompts.at(enrollStep)+"』";
                    else if(!usableSpeaker)
                        status="语音太短或声纹特征不足：请慢一点完整朗读";
                    else status="声纹特征无效，请重试";
                    publish(heard,null,null,false,0f,"CAPTURE_FAIL");
                }
            }
            return;
        }
        boolean enrolled=profile.hasProfile();
        if(enrolled){
            if(ev!=null){
                float score=profile.score(ev.vector);
                if(profile.matches(ev.vector)){
                    lastOwnerVerifiedMs=now;
                    ownerStatus="声纹匹配（实验性）相似度 "+Math.round(score*100)+"%";
                }else ownerStatus="声纹不匹配（实验性）相似度 "+Math.round(score*100)+"%";
            }else if(now-lastOwnerVerifiedMs<180000 && lastOwnerVerifiedMs>0)
                ownerStatus="本句较短 · 沿用 3 分钟内声纹验证";
            else ownerStatus="本句太短无法验证声纹 · 请先说一段较长的本人语音";
        }else ownerStatus="尚未录入声纹 · 仅演示识别";

        BilingualCommandRouter.Match match=BilingualCommandRouter.resolve(zhText,enText,awake);
        boolean wakeEvent=false;
        if(match.woke){
            // Even when already awake, saying wake in MANUAL mode returns to orb.
            awake=true; wakeEvent=true;
        }
        RexCommand cmd=match.command;
        if(cmd==RexCommand.SLEEP){awake=false;lastOwnerVerifiedMs=0;
            status="已听到『睡觉』 · 智能体进入待机（麦克风继续监听）";
        }else if(match.woke){status="REx 已唤醒 · 永不自动休眠 · 说『睡觉』休眠";}
        else if(cmd!=null)status="白名单指令 "+cmd.label+" · 仅显示，尚未开放语音红外";
        else status=awake?"保持唤醒 · 本句不是白名单指令":"待机中 · 等待 Rex / Rex醒了吗 / 灭建国";
        // Even a matched owner embedding is insufficient for motor authorization.
        publish(match.display(),null,cmd,wakeEvent,0f);
    }
    private void publishEnrollment(String event){
        publish(null,null,null,false,0f,event);
    }
    private void publish(String finalText,String partial,RexCommand command,
                         boolean wakeEvent,float realtimeLevel){
        publish(finalText,partial,command,wakeEvent,realtimeLevel,null);
    }
    private void publish(String finalText,String partial,RexCommand command,
                         boolean wakeEvent,float realtimeLevel,String enrollEvent){
        Intent i=new Intent(ACTION_EVENT).setPackage(getPackageName());
        if(finalText!=null)i.putExtra(EXTRA_TEXT,finalText);
        if(partial!=null)i.putExtra(EXTRA_PARTIAL,partial);
        if(command!=null)i.putExtra(EXTRA_COMMAND,command.name());
        if(enrollEvent!=null)i.putExtra(EXTRA_ENROLL_EVENT,enrollEvent);
        i.putExtra(EXTRA_WAKE,wakeEvent);
        i.putExtra(EXTRA_AWAKE,awake);
        i.putExtra(EXTRA_LEVEL,realtimeLevel);
        i.putExtra(EXTRA_SPEAKING,speaking);
        i.putExtra(EXTRA_STATUS,status);
        i.putExtra(EXTRA_OWNER,ownerStatus);
        i.putExtra(EXTRA_ENROLL_COUNT,profile.sampleCount());
        synchronized(enrollLock){
            i.putExtra(EXTRA_ENROLL_STEP,enrollStep);
            i.putExtra(EXTRA_ENROLL_READY,enrollReady);
            i.putExtra(EXTRA_ENROLL_CAPTURING,enrollArmed);
        }
        sendBroadcast(i);
    }
    @Override public void onDestroy(){
        running=false;
        AudioRecord mic=recording;
        if(mic!=null)try{mic.stop();}catch(Exception ignored){}
        if(worker!=null)worker.interrupt();
        if(profile!=null)profile.abandon();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
