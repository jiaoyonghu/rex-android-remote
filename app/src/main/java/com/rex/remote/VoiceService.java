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
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * REx v1.1: sherpa KWS + Silero VAD + CAM++ speaker matching + wake templates,
 * no wake timeout and guided phrase-by-phrase speaker enrollment. All spoken robot
 * commands are PREVIEW ONLY: absolutely no voice-to-IR and no robot motors.
 */
public final class VoiceService extends Service {
    public static final String ACTION_EVENT="com.rex.remote.VOICE_EVENT";
    public static final String ACTION_STOP="com.rex.remote.STOP_VOICE";
    public static final String ACTION_TRAIN_BEGIN="com.rex.remote.TRAIN_BEGIN";
    public static final String ACTION_TRAIN_SELECT="com.rex.remote.TRAIN_SELECT";
    public static final String ACTION_TRAIN_CAPTURE="com.rex.remote.TRAIN_CAPTURE";
    public static final String ACTION_TRAIN_CLEAR="com.rex.remote.TRAIN_CLEAR";
    public static final String ACTION_TRAIN_END="com.rex.remote.TRAIN_END";
    public static final String EXTRA_TRAIN_INDEX="train_index";
    public static final String EXTRA_TRAIN_COUNT="train_count";
    public static final String EXTRA_TRAIN_EVENT="train_event";
    public static final String EXTRA_TRAIN_INFO="train_info";
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

    private static final String CHANNEL="rex_offline_voice_v7"; // preserve notification channel
    private static final String TAG="RExOfflineVoice";
    private static final int SAMPLE_RATE=16000;
    private static final String SHERPA_MODEL_ZIP="rex-sherpa-kws-zh-en-v1.zip";
    private static final String SHERPA_MODEL_FOLDER="rex-sherpa-kws-zh-en-v1";
    private final ArrayBlockingQueue<AudioFrame> audioFrames=new ArrayBlockingQueue<>(16);
    private volatile boolean droppedAudio;

    private volatile boolean running;
    private volatile boolean awake; // NEVER auto-expires. Only the spoken word 睡觉 resets it.
    private volatile boolean speaking;
    private volatile String status="语音未启动";
    private volatile String ownerStatus="尚未录入声纹";
    private volatile AudioRecord recording;
    private Thread worker;
    private SpeakerProfile profile;
    private CommandTemplateStore templates;
    private volatile boolean trainingMode,trainingArmed;
    private volatile int trainingPhrase;
    private volatile int trainingCount;
    private volatile long trainingDeadline;
    private volatile String trainingInfo="当前口令未训练 · 至少采集 3 次";
    private final ArrayBlockingQueue<CommandClip> commandClips=new ArrayBlockingQueue<>(8);
    private volatile long captureGeneration;
    private long lastRealtimeBroadcastMs;
    private long lastFinalMs;
    private String lastFinalSignature="";
    private volatile long lastOwnerVerifiedMs;
    private final SpeechActivityGate activityGate=new SpeechActivityGate();
    private final PersonalWakePolicy personalWake=new PersonalWakePolicy();
    private final Object enrollLock=new Object();
    private int enrollStep;
    private boolean enrollArmed,enrollReady;
    private long enrollDeadline;
    private static final long CAPTURE_TIMEOUT_MS=19000;

    @Override public void onCreate(){super.onCreate();profile=new SpeakerProfile(this);templates=new CommandTemplateStore(this);
        if(profile.hasProfile())ownerStatus="新版 CAM++ 声纹已录入 · 待验证";
        else if(getSharedPreferences("rex_owner_voice_private_v1",MODE_PRIVATE)
                .contains("owner_embedding"))
            ownerStatus="检测到旧版 Vosk 声纹：模型不兼容，请在原声纹页重新录入5条";
        trainingCount=templates.count(0);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?"":intent.getAction();
        if(ACTION_STOP.equals(action)){stopSelf();return START_NOT_STICKY;}
        int requestedStep=intent==null?0:intent.getIntExtra(EXTRA_ENROLL_STEP,0);
        int requestedPhrase=intent==null?0:intent.getIntExtra(EXTRA_TRAIN_INDEX,0);
        if(ACTION_TRAIN_BEGIN.equals(action)||ACTION_TRAIN_SELECT.equals(action)){
            trainingMode=true;trainingArmed=false;
            commandClips.clear();captureGeneration++;personalWake.clear();
            trainingPhrase=Math.max(0,Math.min(CommandPhrases.count()-1,requestedPhrase));
            trainingCount=templates.count(trainingPhrase);
            trainingInfo="『"+CommandPhrases.at(trainingPhrase)+"』已录 "
                +trainingCount+" / 5 次 · 可重新采集，满5次自动替换最旧样本";
            status="指令个性化训练 · 录音仅保留声学特征，不存原始音频";
            publishTraining("TRAIN_SELECTED");
        }
        if(ACTION_TRAIN_CAPTURE.equals(action)){
            if(!trainingMode || requestedPhrase!=trainingPhrase){
                trainingInfo="提示词已改变，请重新选择当前指令";
                publishTraining("TRAIN_FAIL");
            }else{
                trainingArmed=true;captureGeneration++;
                trainingDeadline=SystemClock.elapsedRealtime()+12000;
                trainingInfo="正在采集『"+CommandPhrases.at(trainingPhrase)+"』，请说一次并停顿";
                publishTraining("TRAIN_LISTEN");
            }
            return START_NOT_STICKY;
        }
        if(ACTION_TRAIN_CLEAR.equals(action)){
            if(trainingMode && requestedPhrase==trainingPhrase){
                templates.clear(trainingPhrase);trainingCount=0;trainingArmed=false;
                trainingInfo="本条已清空，请点击『采集』重新朗读";
                publishTraining("TRAIN_CLEARED");
            }
            return START_NOT_STICKY;
        }
        if(ACTION_TRAIN_END.equals(action)){
            trainingMode=false;trainingArmed=false;
            commandClips.clear();captureGeneration++;personalWake.clear();
            trainingInfo="已退出指令训练 · 已录样本保存在本机";
            publishTraining("TRAIN_EXIT");
            return START_NOT_STICKY;
        }
        if(ACTION_ENROLL_CAPTURE.equals(action)){
            synchronized(enrollLock){
                if(profile.isEnrolling() && requestedStep==enrollStep && !enrollReady){
                    enrollArmed=true;captureGeneration++;
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
                            ownerStatus="新版 CAM++ 声纹已保存 · 请用较长唤醒词验证";
                            status="全部 "+EnrollmentPrompts.count()+" 条提示词采集成功 · 声纹录入结束";
                            publishEnrollment("ENROLL_DONE");
                        }else{
                            profile.abandon();enrollStep=0;enrollReady=false;
                            status="五条声纹内部一致性或保存未通过；请重新开始声纹录入（旧档案保留）";
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
                captureGeneration++;commandClips.clear();
                profile.clear();enrollArmed=false;enrollReady=false;enrollStep=0;
                lastOwnerVerifiedMs=0;
                ownerStatus="声纹已清除";status="已删除本机声纹特征";
                publishEnrollment("ENROLL_CLEARED");
            }
            if(!running)stopSelf();return START_NOT_STICKY;
        }
        if(ACTION_ENROLL_CANCEL.equals(action)){
            synchronized(enrollLock){
                captureGeneration++;commandClips.clear();
                profile.abandon();enrollArmed=false;enrollReady=false;enrollStep=0;
                status="已取消本次声纹采集，原有声纹保持不变";
                publishEnrollment("ENROLL_CANCELLED");
            }
            if(!running)stopSelf();return START_NOT_STICKY;
        }
        if(ACTION_ENROLL_BEGIN.equals(action)){
            synchronized(enrollLock){
                captureGeneration++;commandClips.clear();
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
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"REx 神经语音 + 声纹",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,
            new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("REx · 离线神经语音监听")
            .setContentText("持续监听直到手动停止 · 声纹试验版 · 不控制电机")
            .setContentIntent(open).setOngoing(true).build();
        try {startForeground(1001,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);}
        catch(Exception e){status="麦克风服务启动失败："+e.getClass().getSimpleName();
            publish(null,null,null,false,0f);stopSelf();return START_NOT_STICKY;}
        if(!running){running=true;worker=new Thread(this::recognitionLoop,"rex-sherpa-voice-v11");worker.start();}
        publish(null,null,null,false,0f);
        return START_NOT_STICKY;
    }
    private void updateStatus(String value){status=value;publish(null,null,null,false,0f);}
    private void publishTraining(String event){
        publish(null,null,null,false,0f,null,event);
    }
    private static final class CommandClip {
        final short[] pcm;final int phrase;final long generation;
        CommandClip(short[] audio,int phrase,long generation){
            pcm=audio;this.phrase=phrase;this.generation=generation;
        }
    }
    private void handleTrainingClip(CommandClip clip){
        if(!trainingMode || clip.phrase!=trainingPhrase ||
           clip.generation!=captureGeneration)return;
        // Exact-transcript gating rejected the owner's accent. A
        // supervised recording button supplies the label; use actual audio
        // quality and within-phrase consistency checks instead. It is NOT
        // subsequently allowed to trigger an action without KWS evidence.
        String failure=templates.add(clip.phrase,clip.pcm);
        if(failure!=null){trainingInfo="采集失败："+failure;publishTraining("TRAIN_FAIL");}
        else {
            int n=templates.count(clip.phrase);trainingCount=n;
            trainingInfo="『"+CommandPhrases.at(clip.phrase)+"』已采集 "+n+
                "/5"+(n>=3?" · 已参与关键词交叉验证 · 可继续采集替换旧样本":
                " · 还需 "+(3-n)+" 次");
            publishTraining("TRAIN_OK");
        }
    }
    private File unpackSherpaModel() throws Exception {
        File root=new File(getFilesDir(),"rex_offline_models");
        File folder=new File(root,SHERPA_MODEL_FOLDER);
        File marker=new File(folder,".rex_sherpa_ready_v11");
        String[] files={"encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
            "decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
            "joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx",
            "tokens.txt","keywords.txt","silero_vad.onnx","campplus_zh_en.onnx"};
        boolean valid=marker.isFile();
        for(String f:files)valid &= new File(folder,f).length()>100;
        if(valid)return folder;
        if(!root.exists() && !root.mkdirs())throw new IllegalStateException("无法创建模型目录");
        updateStatus("首次启动 · 解压关键词、VAD 与独立声纹模型…");
        long expanded=0;int count=0;
        String rootPath=root.getCanonicalPath()+File.separator;
        try(InputStream in=getAssets().open(SHERPA_MODEL_ZIP);
            ZipInputStream zip=new ZipInputStream(new BufferedInputStream(in))){
            ZipEntry entry;byte[] buf=new byte[32768];
            while((entry=zip.getNextEntry())!=null){
                if(++count>20)throw new IllegalStateException("Sherpa 模型条目异常");
                String name=entry.getName().replace('\\','/');
                if(!name.startsWith(SHERPA_MODEL_FOLDER+"/") || name.contains("../") ||
                    name.startsWith("/"))throw new IllegalStateException("模型路径非法");
                File target=new File(root,name);
                if(!target.getCanonicalPath().startsWith(rootPath))
                    throw new IllegalStateException("模型路径越界");
                if(entry.isDirectory()){
                    if(!target.exists()&&!target.mkdirs())throw new IllegalStateException("模型目录创建失败");
                }else{
                    File parent=target.getParentFile();
                    if(parent!=null&&!parent.exists()&&!parent.mkdirs())
                        throw new IllegalStateException("模型目录创建失败");
                    try(FileOutputStream out=new FileOutputStream(target)){
                        int n;while((n=zip.read(buf))!=-1){
                            expanded+=n;
                            if(expanded>90L*1024*1024)throw new IllegalStateException("模型超出上限");
                            out.write(buf,0,n);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
        for(String f:files)if(new File(folder,f).length()<100)
            throw new IllegalStateException("缺少 Sherpa 模型："+f);
        if(!marker.exists()&&!marker.createNewFile())throw new IllegalStateException("模型标记失败");
        return folder;
    }
    private static final class AudioFrame {
        final short[] samples;final long generation;
        AudioFrame(short[] samples,long generation){this.samples=samples;this.generation=generation;}
    }
    private void recognizeSherpaClip(SherpaVoiceEngine.Clip clip,long generation,
                                      SherpaVoiceEngine engine){
        if(generation!=captureGeneration)return;
        if(trainingMode){
            if(trainingArmed && SystemClock.elapsedRealtime()<=trainingDeadline){
                trainingArmed=false;
                handleTrainingClip(new CommandClip(clip.pcm,trainingPhrase,generation));
            }
            return;
        }
        if(profile.isEnrolling()){
            if(!enrollArmed)return;
            float[] speaker=engine.speakerEmbedding(clip.pcm);
            if(generation==captureGeneration)
                consumeEnrollment(speaker);
            return;
        }
        // KWS is normally the initiator. When ASR misses an enrolled wake word,
        // a HIGH-confidence owner-specific *wake* template can propose a
        // candidate, but ONLY a newly verified long CAM++ embedding can accept it.
        CommandTemplateStore.Result personal=null;
        String proposed=clip.keyword;
        boolean fallback=false,shortTwoHit=false;
        if(!clip.conflictingKeywords && !proposed.isEmpty()){
            personalWake.clear();
            personal=templates.match(clip.pcm);
        }
        else if(!clip.conflictingKeywords && !awake){
            CommandTemplateStore.Result possible=templates.match(clip.pcm);
            int takes=possible.candidateIndex>=0?
                templates.count(possible.candidateIndex):0;
            PersonalWakePolicy.Result recovery=personalWake.evaluate(
                possible,takes,clip.pcm.length,SystemClock.elapsedRealtime());
            if(recovery.found()){
                personal=possible;
                proposed=recovery.phrase;
                fallback=true;shortTwoHit=recovery.twoShort;
            }else if(recovery.awaitSecond){
                status="检测到个人短唤醒的第1次匹配 · 请在3.5秒内再说一遍";
                publish("个人短唤醒待二次确认",null,null,false,0f);
            }
        }
        SherpaCommandPolicy.Decision k=SherpaCommandPolicy.decide(
            proposed,awake,personal,clip.conflictingKeywords);
        if(k.command==null && !k.wake && !fallback && proposed.isEmpty())return;
        long now=SystemClock.elapsedRealtime();
        boolean registered=profile.hasProfile();
        // Computing a speaker embedding for every noise segment would raise
        // latency. Only KWS candidates, accepted wake rescue and enrollment
        // receive a costly speaker check.
        float[] embedding=registered && (k.command!=null || k.wake || fallback)
            ?engine.speakerEmbedding(clip.pcm):null;
        SpeakerProfile.Match match=embedding==null?null:profile.verify(embedding);
        VoiceIdentityPolicy.Speaker sp=registered?
            (match==null?VoiceIdentityPolicy.Speaker.TOO_SHORT:
                match.verified?VoiceIdentityPolicy.Speaker.MATCH:
                               VoiceIdentityPolicy.Speaker.MISMATCH):
                VoiceIdentityPolicy.Speaker.NO_PROFILE;
        boolean session=lastOwnerVerifiedMs>0 && now-lastOwnerVerifiedMs<VoiceIdentityPolicy.SESSION_MS;
        VoiceIdentityPolicy.Decision d=VoiceIdentityPolicy.decide(k,sp,session,fallback&&!shortTwoHit);
        String signature=String.valueOf(k.command)+"|"+k.wake+"|"+
            RexCommand.norm(proposed)+"|"+fallback+"|"+shortTwoHit;
        if(signature.equals(lastFinalSignature) && now-lastFinalMs<750)return;
        lastFinalSignature=signature;lastFinalMs=now;
        if(d.refreshSession)lastOwnerVerifiedMs=now;
        if(match!=null)ownerStatus=(match.verified?"声纹相符":"声纹不符")+
            " · CAM++ 相似度 "+String.format(java.util.Locale.ROOT,"%.2f",match.similarity)+
            " / 阈值 "+String.format(java.util.Locale.ROOT,"%.2f",match.threshold)+
            "（非防录音重放认证）";
        else if(registered)ownerStatus=session?
            "本句较短，使用近期已核对的声纹会话（非独立认证）":
            "本句未取得可靠声纹：请说『Rex醒了吗』较长唤醒词";
        else ownerStatus="新版声纹未录入 · 仅显示命令候选";
        if(d.wake || (d.command!=null &&
                RexCommand.stripWakePrefix(proposed)!=null &&
                !RexCommand.stripWakePrefix(proposed).isEmpty()))awake=true;
        if(d.command==RexCommand.SLEEP){
            awake=false;lastOwnerVerifiedMs=0;
        }
        if(d.wake)status="REx 已唤醒 · "+d.explanation+
            (shortTwoHit?" · 双次短唤醒仅激活界面":fallback?" · 个人长唤醒模板补救":"");
        else if(d.command==RexCommand.SLEEP)status="已睡眠 · 已清除声纹会话";
        else if(d.command!=null)status="识别『"+d.command.label+"』· "+d.explanation+
            " · 仅预览，不发送运动红外";
        else status="未输出口令 · "+d.explanation;
        publish("关键词: "+clip.keyword+(fallback?" | 个人唤醒: "+proposed:""),
            null,d.command,d.wake,0f);
    }
    private void recognitionLoop(){
        try{
            File kwsDir=unpackSherpaModel();
            if(!running)return;
            updateStatus("正在启动 Zipformer 关键词 + Silero VAD + CAM++ 声纹模型…");
            try(SherpaVoiceEngine engine=new SherpaVoiceEngine(kwsDir)){
                int minimum=AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(minimum<=0)throw new IllegalStateException("不支持16kHz麦克风");
                AudioRecord mic=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum*2,8192));
                recording=mic;
                if(mic.getState()!=AudioRecord.STATE_INITIALIZED)
                    throw new IllegalStateException("AudioRecord 未能初始化");
                NoiseSuppressor ns=null;AutomaticGainControl agc=null;
                try{
                    try{if(NoiseSuppressor.isAvailable()){
                        ns=NoiseSuppressor.create(mic.getAudioSessionId());
                        if(ns!=null)ns.setEnabled(true);
                    }}catch(Exception ex){Log.w(TAG,"NoiseSuppressor unavailable",ex);}
                    try{if(AutomaticGainControl.isAvailable()){
                        agc=AutomaticGainControl.create(mic.getAudioSessionId());
                        if(agc!=null)agc.setEnabled(true);
                    }}catch(Exception ex){Log.w(TAG,"AGC unavailable",ex);}
                    mic.startRecording();
                    updateStatus("流式离线监听 · KWS + VAD + 按需声纹复核 · 仅预览");
                    Thread capture=new Thread(()->capturePcm(mic),"rex-live-mic-v11");
                    capture.start();
                    long previousGeneration=-1;
                    while(running){
                        AudioFrame packet=audioFrames.poll(220,TimeUnit.MILLISECONDS);
                        if(packet==null||!running)continue;
                        long gen=captureGeneration;
                        if(packet.generation!=gen)continue;
                        if(previousGeneration!=gen || droppedAudio){
                            engine.reset();previousGeneration=gen;
                            droppedAudio=false;
                        }
                        for(SherpaVoiceEngine.Clip clip:engine.accept(packet.samples,packet.samples.length)){
                            if(gen!=captureGeneration)break;
                            recognizeSherpaClip(clip,gen,engine);
                        }
                    }
                }finally{
                    try{mic.stop();}catch(Exception ignored){}
                    if(ns!=null)ns.release();if(agc!=null)agc.release();
                    mic.release();recording=null;
                }
            }
        }catch(Exception ex){
            if(running){
                Log.e(TAG,"REx sherpa-onnx + CAM++ audio pipeline failed",ex);
                status="神经识别启动失败："+ex.getClass().getSimpleName()+" "+String.valueOf(ex.getMessage());
                publish(null,null,null,false,0f);
                running=false;stopSelf();
            }
        }
    }
    /** Microphone PCM runs separately; bounded queue drops stale data instead of lagging. */
    private void capturePcm(AudioRecord mic){
        short[] reusable=new short[800];
        try{
            while(running){
                int n=mic.read(reusable,0,reusable.length,AudioRecord.READ_BLOCKING);
                if(!running)break;
                if(n<0)throw new IllegalStateException("mic.read failed "+n);
                if(n==0)continue;
                float energy=activityGate.process(reusable,n);
                speaking=activityGate.isActive();
                long now=SystemClock.elapsedRealtime();
                if(trainingArmed && now>trainingDeadline){
                    trainingArmed=false;trainingInfo="采集超时：未取得有效语音，请重试";
                    publishTraining("TRAIN_FAIL");
                }
                synchronized(enrollLock){
                    if(profile.isEnrolling()&&enrollArmed&&now>=enrollDeadline){
                        enrollArmed=false;status="声纹采集超时，请点击采集重试";
                        publishEnrollment("CAPTURE_FAIL");
                    }
                }
                short[] copy=java.util.Arrays.copyOf(reusable,n);
                AudioFrame frame=new AudioFrame(copy,captureGeneration);
                if(!audioFrames.offer(frame)){
                    audioFrames.clear();droppedAudio=true;
                    audioFrames.offer(frame); // never combine stale and fresh utterances
                    status="识别负载较高，已清空过期录音，避免误认";
                }
                if(now-lastRealtimeBroadcastMs>=65){
                    lastRealtimeBroadcastMs=now;publish(null,null,null,false,energy);
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

    private void consumeEnrollment(float[] embedding){
        synchronized(enrollLock){
            if(!profile.isEnrolling() || !enrollArmed || enrollReady)return;
            enrollArmed=false;
            // The user has selected and confirmed the prompt in the original
            // five-step UI. ASR transcript equality is not a valid prerequisite
            // for speaker embedding enrollment, especially with strong accents.
            if(embedding!=null && profile.add(embedding)){
                enrollReady=true;
                ownerStatus="第 "+(enrollStep+1)+"/"+EnrollmentPrompts.count()+" 条：CAM++ 声纹采集有效";
                status="声纹质量及内部一致性初检通过 · 点击『完成』";
                publish(null,null,null,false,0f,"CAPTURE_OK");
            }else{
                status=embedding==null?
                    "采集时长不足或未取得有效语音，请完整念当前提示句":
                    "声纹与本次录入的其他样本差异过大，请在安静环境重录";
                publish(null,null,null,false,0f,"CAPTURE_FAIL");
            }
        }
    }
    private void publishEnrollment(String event){
        publish(null,null,null,false,0f,event);
    }
    private void publish(String finalText,String partial,RexCommand command,
                         boolean wakeEvent,float realtimeLevel){
        publish(finalText,partial,command,wakeEvent,realtimeLevel,null,null);
    }
    private void publish(String finalText,String partial,RexCommand command,
                         boolean wakeEvent,float realtimeLevel,String enrollEvent){
        publish(finalText,partial,command,wakeEvent,realtimeLevel,enrollEvent,null);
    }
    private void publish(String finalText,String partial,RexCommand command,
                         boolean wakeEvent,float realtimeLevel,String enrollEvent,String trainEvent){
        Intent i=new Intent(ACTION_EVENT).setPackage(getPackageName());
        if(finalText!=null)i.putExtra(EXTRA_TEXT,finalText);
        if(partial!=null)i.putExtra(EXTRA_PARTIAL,partial);
        if(command!=null)i.putExtra(EXTRA_COMMAND,command.name());
        if(enrollEvent!=null)i.putExtra(EXTRA_ENROLL_EVENT,enrollEvent);
        if(trainEvent!=null)i.putExtra(EXTRA_TRAIN_EVENT,trainEvent);
        i.putExtra(EXTRA_TRAIN_INDEX,trainingPhrase);
        i.putExtra(EXTRA_TRAIN_COUNT,trainingCount);
        i.putExtra(EXTRA_TRAIN_INFO,trainingInfo);
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
