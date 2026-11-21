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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REx v1.4: KWS + VAD + frozen openWakeWord features + trained personal MLP head,
 * no wake timeout and guided phrase-by-phrase speaker enrollment. All spoken robot
 * commands are PREVIEW ONLY: absolutely no voice-to-IR and no robot motors.
 */
public final class VoiceService extends Service {
    public static final String ACTION_EVENT="com.rex.remote.VOICE_EVENT";
    public static final String ACTION_STOP="com.rex.remote.STOP_VOICE";
    public static final String ACTION_DIAG_RESET="com.rex.remote.DIAG_RESET";
    public static final String EXTRA_DIAG_ENGINE="diag_engine";
    public static final String EXTRA_DIAG_DBFS="diag_dbfs";
    public static final String EXTRA_DIAG_ACTIVITY="diag_activity";
    public static final String EXTRA_DIAG_FRAMES="diag_frames";
    public static final String EXTRA_DIAG_DROPS="diag_drops";
    public static final String EXTRA_DIAG_KWS="diag_kws";
    public static final String EXTRA_DIAG_VAD="diag_vad";
    public static final String EXTRA_DIAG_PERSONAL="diag_personal";
    public static final String EXTRA_DIAG_VERDICT="diag_verdict";
    public static final String EXTRA_DIAG_CAUSE="diag_cause";
    public static final String EXTRA_DIAG_TIMING="diag_timing";
    public static final String EXTRA_DIAG_KWS_AT="diag_kws_at";
    public static final String EXTRA_DIAG_VAD_AT="diag_vad_at";
    public static final String EXTRA_DIAG_VERDICT_AT="diag_verdict_at";
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
    // Set false to use only the previously working sherpa KWS/VAD pipeline.
    // Diagnostic switch for native ONNX crashes; it does not erase trained samples.
    private static final boolean ENABLE_PERSONAL_NEURAL=true;
    private static final String SHERPA_MODEL_ZIP="rex-sherpa-kws-zh-en-v1.zip";
    private static final String SHERPA_MODEL_FOLDER="rex-sherpa-kws-zh-en-v1";
    // Observation-only state: diagnostics cannot authorize speech commands.
    private final VoiceDiagnostics diagnostics=new VoiceDiagnostics();
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
    private CommandTemplateStore templates; // old MFCC data retained
    private PersonalNeuralStore neural;
    private PersonalWakeHead trainedHead;
    private final ExecutorService trainingWorker=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"rex-v14-train-head");t.setPriority(Thread.MIN_PRIORITY);return t;
    });
    private final AtomicLong trainingRevision=new AtomicLong();
    private volatile boolean trainingMode,trainingArmed;
    private volatile int trainingPhrase;
    private volatile int trainingCount;
    private volatile long trainingDeadline;
    private volatile String trainingInfo="当前口令未训练 · 新版至少6次，建议12次";
    private final ArrayBlockingQueue<CommandClip> commandClips=new ArrayBlockingQueue<>(8);
    private volatile long captureGeneration;
    private long lastRealtimeBroadcastMs;
    private long lastFinalMs;
    private String lastFinalSignature="";
    private volatile long lastOwnerVerifiedMs;
    private final SpeechActivityGate activityGate=new SpeechActivityGate();
    private final NeuralWakePolicy neuralWake=new NeuralWakePolicy();
    private final Object enrollLock=new Object();
    private int enrollStep;
    private boolean enrollArmed,enrollReady;
    private long enrollDeadline;
    private static final long CAPTURE_TIMEOUT_MS=19000;

    @Override public void onCreate(){super.onCreate();profile=new SpeakerProfile(this);templates=new CommandTemplateStore(this);
        neural=new PersonalNeuralStore(this);
        trainedHead=new PersonalWakeHead(this,neural);
        if(profile.hasProfile())ownerStatus="CAM++ 声纹已录入 · 仅供声纹页参考，不强制限制指令";
        else if(getSharedPreferences("rex_owner_voice_private_v1",MODE_PRIVATE)
                .contains("owner_embedding"))
            ownerStatus="检测到旧版 Vosk 声纹：模型不兼容，请在原声纹页重新录入5条";
        trainingCount=neural.count(0);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?"":intent.getAction();
        if(ACTION_STOP.equals(action)){stopSelf();return START_NOT_STICKY;}
        if(ACTION_DIAG_RESET.equals(action)){
            diagnostics.reset();publish(null,null,null,false,0f);
            if(!running)stopSelf();return START_NOT_STICKY;
        }
        int requestedStep=intent==null?0:intent.getIntExtra(EXTRA_ENROLL_STEP,0);
        int requestedPhrase=intent==null?0:intent.getIntExtra(EXTRA_TRAIN_INDEX,0);
        if(ACTION_TRAIN_BEGIN.equals(action)||ACTION_TRAIN_SELECT.equals(action)){
            trainingMode=true;trainingArmed=false;
            commandClips.clear();captureGeneration++;neuralWake.clear();
            trainingPhrase=Math.max(0,Math.min(PersonalNeuralStore.NON_COMMAND,requestedPhrase));
            trainingCount=neural.count(trainingPhrase);
            trainingInfo="『"+trainingLabel(trainingPhrase)+"』已录 "
                +trainingCount+" / 30 次 · 分类器需本词6条与其他/非口令6条";
            status="openWakeWord 个人神经分类器训练 · 原始录音不落盘";
            publishTraining("TRAIN_SELECTED");
        }
        if(ACTION_TRAIN_CAPTURE.equals(action)){
            if(!trainingMode || requestedPhrase!=trainingPhrase){
                trainingInfo="提示词已改变，请重新选择当前指令";
                publishTraining("TRAIN_FAIL");
            }else{
                trainingArmed=true;captureGeneration++;
                trainingDeadline=SystemClock.elapsedRealtime()+12000;
                trainingInfo="正在采集『"+trainingLabel(trainingPhrase)+"』，请说一次并停顿";
                publishTraining("TRAIN_LISTEN");
            }
            return START_NOT_STICKY;
        }
        if(ACTION_TRAIN_CLEAR.equals(action)){
            if(trainingMode && requestedPhrase==trainingPhrase){
                neural.clear(trainingPhrase);trainingCount=0;trainingArmed=false;
                trainingRevision.incrementAndGet();
                // Every one-vs-rest head could have used this phrase as negative.
                // On deletion invalidate all learned heads, then rebuild from
                // the remaining samples rather than retaining removed evidence.
                for(int p=0;p<CommandPhrases.count();p++)trainedHead.forget(p);
                scheduleHeadTraining(PersonalNeuralStore.NON_COMMAND);
                trainingInfo="本条已清空，请点击『采集』重新朗读";
                publishTraining("TRAIN_CLEARED");
            }
            return START_NOT_STICKY;
        }
        if(ACTION_TRAIN_END.equals(action)){
            trainingMode=false;trainingArmed=false;
            commandClips.clear();captureGeneration++;neuralWake.clear();
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
        if(!running){running=true;worker=new Thread(this::recognitionLoop,"rex-sherpa-voice-v12");worker.start();}
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
    private static String trainingLabel(int index){
        return index==PersonalNeuralStore.NON_COMMAND?"非口令/相似词（负样本）":CommandPhrases.at(index);
    }
    /** Re-learn weights off the realtime audio thread; newest training revision wins. */
    private void scheduleHeadTraining(int changed){
        final long revision=trainingRevision.incrementAndGet();
        trainingWorker.execute(()->{
            if(trainingRevision.get()!=revision)return;
            String outcome="";
            if(changed==PersonalNeuralStore.NON_COMMAND){
                int ready=0;
                for(int p=0;p<CommandPhrases.count();p++){
                    if(trainingRevision.get()!=revision)return;
                    if(neural.count(p)>=PersonalWakeHead.MIN_POS){
                        String msg=trainedHead.train(p,()->trainingRevision.get()==revision);
                        if(msg.startsWith("训练成功"))ready++;
                    }
                }
                outcome="负样本更新；已训练 "+ready+" 个分类头";
            }else outcome=trainedHead.train(changed,()->trainingRevision.get()==revision);
            if(trainingRevision.get()!=revision)return;
            diagnostics.onPersonal("v1.4 真分类头 · "+outcome);
            if(trainingMode && changed==trainingPhrase){
                trainingInfo=outcome+" · 已存 "+neural.count(changed)+"/30";
                publishTraining("TRAIN_MODEL");
            }
        });
    }
    private void handleTrainingClip(CommandClip clip,OpenWakeWordFeatures features){
        if(!trainingMode || clip.phrase!=trainingPhrase ||
           clip.generation!=captureGeneration)return;
        // Exact-transcript gating rejected the owner's accent. A
        // The chosen phrase supplies the positive/negative supervised label.
        // The learned head may later complement missing KWS evidence, but all
        // outcomes still require the VAD segment and fusion policy.
        long measureStarted=SystemClock.elapsedRealtime();
        float[] vector=features==null?null:features.embed(clip.pcm);
        String failure=features==null?
            "个人神经模型不可用；基础关键词监听继续运行":neural.add(clip.phrase,vector);
        if(failure!=null){
            diagnostics.onPersonal("训练失败："+failure);
            diagnostics.onVerdict("训练未保存",failure,
                SystemClock.elapsedRealtime(),SystemClock.elapsedRealtime()-measureStarted);
            trainingInfo="采集失败："+failure;publishTraining("TRAIN_FAIL");
        }
        else {
            int n=neural.count(clip.phrase);trainingCount=n;
            diagnostics.onPersonal("训练样本保存："+trainingLabel(clip.phrase)+" · "+n+" 条样本");
            diagnostics.onVerdict("训练已保存","个人特征写入本机",
                SystemClock.elapsedRealtime(),SystemClock.elapsedRealtime()-measureStarted);
            trainingInfo="『"+trainingLabel(clip.phrase)+"』已采集 "+n+
                "/30"+(n>=PersonalNeuralStore.MIN_TAKES?
                " · 已保存，后台更新神经分类器（至少6条负样本）":
                " · 还需 "+(PersonalNeuralStore.MIN_TAKES-n)+" 次");
            publishTraining("TRAIN_OK");
            scheduleHeadTraining(clip.phrase);
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
    /** VAD already delivered a real speech segment. Zipformer has been running
     * concurrently for low latency; neither a background KWS hit nor a loud
     * noise by itself can bypass this final speech-segment gate. */
    private void recognizeSherpaClip(SherpaVoiceEngine.Clip clip,long generation,
                                      SherpaVoiceEngine engine,OpenWakeWordFeatures features){
        if(generation!=captureGeneration)return;
        if(trainingMode){
            if(trainingArmed && SystemClock.elapsedRealtime()<=trainingDeadline){
                trainingArmed=false;
                handleTrainingClip(new CommandClip(clip.pcm,trainingPhrase,generation),features);
            }
            return;
        }
        if(profile.isEnrolling()){
            if(!enrollArmed)return;
            float[] speaker=engine.speakerEmbedding(clip.pcm);
            if(generation==captureGeneration)consumeEnrollment(speaker);
            return;
        }
        long now=SystemClock.elapsedRealtime();
        long decisionStarted=now;
        boolean trained=false;
        for(int i=0;i<CommandPhrases.count();i++){
            if(neural.count(i)>=PersonalNeuralStore.MIN_TAKES){trained=true;break;}
        }
        long personalStarted=SystemClock.elapsedRealtime();
        float[] utteranceFeature=trained && features!=null?features.embed(clip.pcm):null;
        PersonalNeuralStore.Result oldMatch=utteranceFeature==null?null:neural.evaluate(utteranceFeature);
        PersonalWakeHead.Result headMatch=utteranceFeature==null?null:trainedHead.evaluate(utteranceFeature);
        PersonalNeuralStore.Result match=oldMatch;
        // Trained head takes precedence only when it has genuinely learned
        // positive-vs-negative discrimination. The old classifier is fallback.
        if(headMatch!=null && headMatch.accepted &&
           (oldMatch==null || !oldMatch.accepted ||
            headMatch.phrase==oldMatch.phrase ||
            (headMatch.score>=.95f && headMatch.second<=.35f))){
            match=new PersonalNeuralStore.Result(headMatch.phrase,headMatch.runnerUp,
                headMatch.score,headMatch.second,.64f,headMatch.trainedCount,true,
                "v1.4 监督训练 MLP 分类头（非声纹认证）");
        }
        if(headMatch!=null){
            diagnostics.onPersonal(String.format(java.util.Locale.ROOT,
                "v1.4分类头 p=%d 得分 %.3f 备选 %.3f · %s · %s",
                headMatch.phrase,headMatch.score,headMatch.second,
                headMatch.reason,match==oldMatch?"沿用原匹配":"采用训练分类头"));
        }
        CommandFusion.Config cfg=CommandFusion.load(this);
        if(match==null){
            diagnostics.onPersonal(!trained?"未启用：没有达到6条的个人训练口令":
                features==null?"不可用：个人TFLite模型未加载":"个人语音没有可用的模型得分");
        }else{
            diagnostics.onPersonal(String.format(java.util.Locale.ROOT,
                "个人得分 %.3f / 可调门槛 %.3f / 内部门槛 %.3f · %d条 · %dms (%s)",
                match.score,cfg.neuralThreshold,match.threshold,match.samples,
                SystemClock.elapsedRealtime()-personalStarted,match.reason));
        }
        // The stock sherpa JNI does not expose a continuous KWS probability.
        // Its evidence is exactly 1 for a hit and 0 for no hit, NOT a confidence.
        CommandFusion.Decision decision=CommandFusion.evaluate(
            clip.keyword,awake,clip.conflictingKeywords,match,cfg);
        diagnostics.onPersonal(decision.report(cfg));
        if(!decision.pass){
            if(!clip.keyword.isEmpty())neuralWake.clear();
            diagnostics.onVerdict("融合拒绝",decision.report(cfg),
                SystemClock.elapsedRealtime(),SystemClock.elapsedRealtime()-decisionStarted);
            status="本句未通过综合评分 · "+decision.reason;
            publish(null,null,null,false,0f);
            return;
        }
        if(decision.wake)neuralWake.clear();
        RexCommand command=decision.command;
        boolean wake=decision.wake;
        String signature=String.valueOf(command)+"|"+wake+"|"+clip.keyword;
        if(signature.equals(lastFinalSignature)&&now-lastFinalMs<750){
            diagnostics.onVerdict("重复结果已抑制","750ms内相同候选被去重",
                SystemClock.elapsedRealtime(),SystemClock.elapsedRealtime()-decisionStarted);
            return;
        }
        lastFinalSignature=signature;lastFinalMs=now;
        if(wake)awake=true;
        if(command==RexCommand.SLEEP){awake=false;lastOwnerVerifiedMs=0;}
        ownerStatus="个人神经模型辅助口令（非本人认证）· CAM++ 仅在声纹页";
        if(wake)status="REx 已唤醒 · 综合得分 " +
            String.format(java.util.Locale.ROOT,"%.3f",decision.combined);
        else if(command==RexCommand.SLEEP)status="已睡眠";
        else status="识别『"+command.label+"』· 综合得分 "+
            String.format(java.util.Locale.ROOT,"%.3f",decision.combined)+
            " · 仅预览，不发送红外";
        diagnostics.onVerdict(wake?"唤醒成功":command==RexCommand.SLEEP?
                "进入待机":"识别成功："+command.label,
            decision.report(cfg),SystemClock.elapsedRealtime(),
            SystemClock.elapsedRealtime()-decisionStarted);
        publish(decision.report(cfg),null,command,wake,0f);
    }
    private void recognitionLoop(){
        try{
            diagnostics.onEngine("正在准备模型文件");
            File kwsDir=unpackSherpaModel();
            if(!running)return;
            updateStatus("启动 Zipformer KWS + Silero VAD + openWakeWord 个人神经模型…");
            // The personal ONNX frontend is optional and loaded only on first use.
            // A missing/broken personal model must not terminate the primary KWS.
            OpenWakeWordFeatures personalFeatures=null;
            boolean personalUnavailable=false;
            try(SherpaVoiceEngine engine=new SherpaVoiceEngine(kwsDir,
                new SherpaVoiceEngine.DiagnosticsListener(){
                    @Override public void onKwsHit(String hit,boolean conflict){
                        diagnostics.onKeyword(hit,conflict,SystemClock.elapsedRealtime());
                    }
                    @Override public void onVadSegment(int samples,boolean accepted,String keyword){
                        diagnostics.onVad(samples,accepted,keyword,SystemClock.elapsedRealtime());
                    }
                })){
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
                    diagnostics.onEngine("麦克风已启动，KWS/VAD运行中");
                    updateStatus("流式监听 · KWS + VAD + 个人神经口令 · 仅预览");
                    Thread capture=new Thread(()->capturePcm(mic),"rex-live-mic-v12");
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
                            boolean needPersonal=ENABLE_PERSONAL_NEURAL && trainingMode;
                            if(ENABLE_PERSONAL_NEURAL && !needPersonal && !profile.isEnrolling()){
                                for(int i=0;i<CommandPhrases.count();i++){
                                    if(neural.count(i)>=PersonalNeuralStore.MIN_TAKES){
                                        needPersonal=true;break;
                                    }
                                }
                            }
                            if(needPersonal && personalFeatures==null && !personalUnavailable){
                                try{
                                    personalFeatures=new OpenWakeWordFeatures(this);
                                    Log.i(TAG,"Personal neural frontend loaded on demand");
                                }catch(Exception | LinkageError ex){
                                    personalUnavailable=true;
                                    diagnostics.onPersonal("个人神经模型加载失败："+ex.getClass().getSimpleName());
                                    diagnostics.onError("TFLite初始化",ex.getClass().getSimpleName());
                                    Log.e(TAG,"Optional personal ONNX frontend unavailable; KWS continues",ex);
                                    status="个人模型暂不可用；基础关键词识别继续运行";
                                    publish(null,null,null,false,0f);
                                }
                            }
                            try{
                                recognizeSherpaClip(clip,gen,engine,personalFeatures);
                            }catch(RuntimeException ex){
                                diagnostics.onError("单段裁决",ex.getClass().getSimpleName());
                                Log.e(TAG,"One voice clip failed; continuous KWS kept alive",ex);
                                status="单条语音处理出错，已跳过并继续监听";
                                publish(null,null,null,false,0f);
                            }
                        }
                    }
                }finally{
                    try{mic.stop();}catch(Exception ignored){}
                    if(ns!=null)ns.release();if(agc!=null)agc.release();
                    mic.release();recording=null;
                }
            }finally{
                if(personalFeatures!=null)try{personalFeatures.close();}
                catch(Exception ex){Log.w(TAG,"Optional neural feature cleanup failed",ex);}
            }
        }catch(Exception ex){
            if(running){
                diagnostics.onError("音频主引擎",ex.getClass().getSimpleName());
                diagnostics.onEngine("已停止："+ex.getClass().getSimpleName());
                Log.e(TAG,"REx KWS/openWakeWord audio pipeline failed",ex);
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
                diagnostics.onAudio(reusable,n,speaking);
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
                    diagnostics.onDroppedAudio();
                    audioFrames.offer(frame); // never combine stale and fresh utterances
                    status="识别负载较高，已清空过期录音，避免误认";
                }
                if(now-lastRealtimeBroadcastMs>=65){
                    lastRealtimeBroadcastMs=now;publish(null,null,null,false,energy);
                }
            }
        }catch(Exception e){
            if(running){
                diagnostics.onError("麦克风采集",e.getClass().getSimpleName());
                diagnostics.onEngine("已停止：麦克风异常");
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
        VoiceDiagnostics.Snapshot d=diagnostics.snapshot();
        i.putExtra(EXTRA_DIAG_ENGINE,d.engine);
        i.putExtra(EXTRA_DIAG_DBFS,d.dbfs);
        i.putExtra(EXTRA_DIAG_ACTIVITY,d.activity);
        i.putExtra(EXTRA_DIAG_FRAMES,d.frames);
        i.putExtra(EXTRA_DIAG_DROPS,d.dropped);
        i.putExtra(EXTRA_DIAG_KWS,d.keyword);
        i.putExtra(EXTRA_DIAG_VAD,d.vad);
        i.putExtra(EXTRA_DIAG_PERSONAL,d.personal);
        i.putExtra(EXTRA_DIAG_VERDICT,d.verdict);
        i.putExtra(EXTRA_DIAG_CAUSE,d.cause);
        i.putExtra(EXTRA_DIAG_TIMING,d.timing);
        i.putExtra(EXTRA_DIAG_KWS_AT,d.kwsAt);
        i.putExtra(EXTRA_DIAG_VAD_AT,d.vadAt);
        i.putExtra(EXTRA_DIAG_VERDICT_AT,d.verdictAt);
        sendBroadcast(i);
    }
    @Override public void onDestroy(){
        diagnostics.onEngine("语音服务已关闭");
        running=false;
        AudioRecord mic=recording;
        if(mic!=null)try{mic.stop();}catch(Exception ignored){}
        if(worker!=null)worker.interrupt();
        trainingRevision.incrementAndGet();
        trainingWorker.shutdownNow();
        if(profile!=null)profile.abandon();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
