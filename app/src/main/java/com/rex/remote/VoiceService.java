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
 * REx v0.9: fail-closed bilingual ASR + grammar + personal templates,
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

    private static final String CHANNEL="rex_offline_voice_v7"; // keep notification channel
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
    private final Object enrollLock=new Object();
    private int enrollStep;
    private boolean enrollArmed,enrollReady;
    private long enrollDeadline;
    private static final long CAPTURE_TIMEOUT_MS=19000;

    @Override public void onCreate(){super.onCreate();profile=new SpeakerProfile(this);templates=new CommandTemplateStore(this);
        if(profile.hasProfile())ownerStatus="已录入声纹 · 待验证";
        trainingCount=templates.count(0);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?"":intent.getAction();
        if(ACTION_STOP.equals(action)){stopSelf();return START_NOT_STICKY;}
        int requestedStep=intent==null?0:intent.getIntExtra(EXTRA_ENROLL_STEP,0);
        int requestedPhrase=intent==null?0:intent.getIntExtra(EXTRA_TRAIN_INDEX,0);
        if(ACTION_TRAIN_BEGIN.equals(action)||ACTION_TRAIN_SELECT.equals(action)){
            trainingMode=true;trainingArmed=false;
            commandClips.clear();captureGeneration++;
            trainingPhrase=Math.max(0,Math.min(CommandPhrases.count()-1,requestedPhrase));
            trainingCount=templates.count(trainingPhrase);
            trainingInfo="『"+CommandPhrases.at(trainingPhrase)+"』已录 "
                +trainingCount+" / 5 次 · 仅辅助交叉确认";
            status="指令个性化训练 · 录音仅保留声学特征，不存原始音频";
            publishTraining("TRAIN_SELECTED");
        }
        if(ACTION_TRAIN_CAPTURE.equals(action)){
            if(!trainingMode || requestedPhrase!=trainingPhrase){
                trainingInfo="提示词已改变，请重新选择当前指令";
                publishTraining("TRAIN_FAIL");
            }else if(templates.count(trainingPhrase)>=CommandTemplateStore.MAX_TAKES){
                trainingInfo="本条已采集 5 次，请切换指令或清除后重录";
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
            commandClips.clear();captureGeneration++;
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
                            ownerStatus="已保存主人声纹特征（实验性）";
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
        if(!running){running=true;worker=new Thread(this::recognitionLoop,"rex-bilingual-voice-v9");worker.start();}
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
    private void handleTrainingClip(CommandClip clip,String zh,String en,
                                    String focusedZh,String focusedEn){
        if(!trainingMode || clip.phrase!=trainingPhrase ||
           clip.generation!=captureGeneration)return;
        // The record button specifies a *label*, not a supervision signal.
        // A noise or different spoken word must not silently become a template.
        // Reject when either unrestricted model recognises a different known
        // command, and require the target phrase to appear verbatim in ASR or
        // the focused grammar (not a mere same-action synonym).
        String expected=RexCommand.norm(CommandPhrases.at(clip.phrase));
        boolean exact=expected.equals(RexCommand.norm(zh)) ||
            expected.equals(RexCommand.norm(en)) ||
            expected.equals(RexCommand.norm(focusedZh)) ||
            expected.equals(RexCommand.norm(focusedEn));
        if(!exact){
            trainingInfo="未核对到『"+CommandPhrases.at(clip.phrase)+"』，没有保存此样本；请清楚朗读后重试";
            publishTraining("TRAIN_FAIL");
            return;
        }
        String failure=templates.add(clip.phrase,clip.pcm);
        if(failure!=null){trainingInfo="采集失败："+failure;publishTraining("TRAIN_FAIL");}
        else {
            int n=templates.count(clip.phrase);trainingCount=n;
            trainingInfo="『"+CommandPhrases.at(clip.phrase)+"』采集 " + n
                +"/5"+(n>=3?" · 可辅助交叉确认（模板不会单独触发口令）":
                " · 再采 "+(3-n)+" 次才生效");
            publishTraining("TRAIN_OK");
        }
    }

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

    private static final class OptionalGrammar implements AutoCloseable {
        final Recognizer recognizer;
        OptionalGrammar(Model model,String grammar,String language){
            Recognizer result=null;
            try{result=new Recognizer(model,SAMPLE_RATE,grammar);}
            catch(Exception e){Log.w(TAG,language+"受限语法不可用，继续使用通用识别与训练模板",e);}
            recognizer=result;
        }
        @Override public void close(){if(recognizer!=null)recognizer.close();}
    }
    private static final class Decoded {
        final String text;final SpeakerEvidence speaker;
        Decoded(String text,SpeakerEvidence speaker){this.text=text;this.speaker=speaker;}
    }
    /** Decode one whole clipped utterance, avoiding mismatched ASR/template timestamps. */
    private static Decoded decodeClip(Recognizer recognizer,short[] samples) {
        recognizer.reset();
        StringBuilder words=new StringBuilder();
        SpeakerEvidence evidence=null;
        for(int start=0;start<samples.length;start+=1600){
            int length=Math.min(1600,samples.length-start);
            short[] frame=new short[length];
            System.arraycopy(samples,start,frame,0,length);
            if(recognizer.acceptWaveForm(frame,length)){
                String json=recognizer.getResult();
                String text=getText(json,"text");
                if(!text.isEmpty()){
                    if(words.length()>0)words.append(' ');
                    words.append(text);
                }
                SpeakerEvidence e=evidenceFrom(json);
                if(e!=null && (evidence==null || e.frames>evidence.frames))evidence=e;
            }
        }
        String json=recognizer.getFinalResult();
        String text=getText(json,"text");
        if(!text.isEmpty()){
            if(words.length()>0)words.append(' ');
            words.append(text);
        }
        SpeakerEvidence e=evidenceFrom(json);
        if(e!=null && (evidence==null || e.frames>evidence.frames))evidence=e;
        return new Decoded(words.toString().trim(),evidence);
    }
    private void recognitionLoop(){
        try {
            File zhDir=unpackBundledModel(ZH_MODEL_ZIP,ZH_MODEL_FOLDER,"中文");
            if(!running)return;
            File enDir=unpackBundledModel(EN_MODEL_ZIP,EN_MODEL_FOLDER,"英文");
            if(!running)return;
            File speakerDir=unpackBundledModel(SPEAKER_ZIP,SPEAKER_FOLDER,"声纹");
            if(!running)return;
            updateStatus("正在加载双语离线识别 + 固定词表 + 声纹模型…");
            try(Model zhModel=new Model(zhDir.getAbsolutePath());
                Model enModel=new Model(enDir.getAbsolutePath());
                SpeakerModel speakerModel=new SpeakerModel(speakerDir.getAbsolutePath());
                Recognizer zh=new Recognizer(zhModel,SAMPLE_RATE);
                Recognizer en=new Recognizer(enModel,SAMPLE_RATE);
                OptionalGrammar focusedZh=new OptionalGrammar(zhModel,VoskGrammar.chinese(),"中文");
                OptionalGrammar focusedEn=new OptionalGrammar(enModel,VoskGrammar.english(),"英文")) {
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
                    }}catch(Exception ex){Log.w(TAG,"NoiseSuppressor unavailable",ex);}
                    try{if(AutomaticGainControl.isAvailable()){
                        agc=AutomaticGainControl.create(mic.getAudioSessionId());
                        if(agc!=null)agc.setEnabled(true);
                    }}catch(Exception ex){Log.w(TAG,"AGC unavailable",ex);}
                    mic.startRecording();
                    updateStatus("离线监听中 · 双语模型+个人模板交叉确认 · 拒绝单路猜测 · 仅预览");
                    Thread capture=new Thread(()->capturePcm(mic),"rex-live-mic");
                    capture.start();
                    while(running){
                        CommandClip clip=commandClips.poll(250,TimeUnit.MILLISECONDS);
                        if(!running || clip==null)continue;
                        if(clip.generation!=captureGeneration)continue;
                        if(clip.phrase>=0){
                            // No more arbitrary audio labelled as a successful
                            // training take: first check the prompted phrase.
                            Decoded trainCn=decodeClip(zh,clip.pcm);
                            Decoded trainEn=decodeClip(en,clip.pcm);
                            String trainGrZh=focusedZh.recognizer==null?"":
                                decodeClip(focusedZh.recognizer,clip.pcm).text;
                            String trainGrEn=focusedEn.recognizer==null?"":
                                decodeClip(focusedEn.recognizer,clip.pcm).text;
                            if(clip.generation!=captureGeneration)continue;
                            handleTrainingClip(clip,trainCn.text,trainEn.text,
                                trainGrZh,trainGrEn);
                            continue;
                        }
                        if(trainingMode)continue;
                        synchronized(enrollLock){
                            if(profile.isEnrolling() && !enrollArmed)continue;
                        }
                        // Fast path for enrollment: only the full-vocabulary recognizers.
                        Decoded cn=decodeClip(zh,clip.pcm);
                        Decoded english=decodeClip(en,clip.pcm);
                        if(clip.generation!=captureGeneration)continue;
                        if(profile.isEnrolling()) {
                            consumeUtterance(cn.text,english.text,"","",cn.speaker,
                                english.speaker,null);
                            continue;
                        }
                        Decoded focusedCn=focusedZh.recognizer==null?new Decoded("",null):
                            decodeClip(focusedZh.recognizer,clip.pcm);
                        Decoded focusedEnglish=focusedEn.recognizer==null?new Decoded("",null):
                            decodeClip(focusedEn.recognizer,clip.pcm);
                        if(clip.generation!=captureGeneration)continue;
                        // Only compare costly personal templates if Vosk first
                        // proposed an allowed command. Random noise/ordinary
                        // speech must never be a template-only trigger path.
                        CommandTemplateStore.Result result=
                            SpeechFusion.hasAsrHypothesis(cn.text,english.text,
                                focusedCn.text,focusedEnglish.text,awake)
                                ? templates.match(clip.pcm) : null;
                        consumeUtterance(cn.text,english.text,focusedCn.text,
                            focusedEnglish.text,cn.speaker,english.speaker,result);
                    }
                }finally{
                    try{mic.stop();}catch(Exception ignored){}
                    if(ns!=null)ns.release();
                    if(agc!=null)agc.release();
                    mic.release();recording=null;
                }
            }
        }catch(Exception ex){
            if(!running)return;
            Log.e(TAG,"REx Vosk audio pipeline failed",ex);
            status="离线语音失败："+ex.getClass().getSimpleName()+" "+String.valueOf(ex.getMessage());
            publish(null,null,null,false,0f);
            running=false;stopSelf();
        }
    }

    /** Real-time PCM thread: samples + publishes level BEFORE any ASR decoding. */
    private void capturePcm(AudioRecord mic){
        short[] reusable=new short[800]; // 50ms, 16kHz mono
        CommandAudioSegmenter segmenter=new CommandAudioSegmenter();
        CommandActivityGate commandGate=new CommandActivityGate();
        long seenGeneration=-1;
        try{
            while(running){
                if(seenGeneration!=captureGeneration){
                    segmenter.reset();commandGate.reset();
                    seenGeneration=captureGeneration;
                }
                int count=mic.read(reusable,0,reusable.length,AudioRecord.READ_BLOCKING);
                if(!running)break;
                if(count<0)throw new IllegalStateException("mic.read failed " + count);
                if(count==0)continue;
                float energy=activityGate.process(reusable,count);
                speaking=activityGate.isActive();
                long now=SystemClock.elapsedRealtime();
                if(trainingArmed && now>trainingDeadline){
                    trainingArmed=false;trainingInfo="采集超时：没有完成一次有效的朗读，请重试";
                    publishTraining("TRAIN_FAIL");
                }
                // Noise/energy alone cannot initiate command capture. Only
                // sustained speech-like activity opens a candidate segment.
                boolean commandActivity=commandGate.accept(reusable,count,speaking);
                short[] utterance=segmenter.accept(reusable,count,commandActivity);
                if(utterance!=null){
                    int phrase=-1;
                    if(trainingMode){
                        if(trainingArmed){phrase=trainingPhrase;trainingArmed=false;}
                        else utterance=null;
                    }
                    if(utterance!=null){
                        CommandClip clip=new CommandClip(utterance,phrase,seenGeneration);
                        if(!commandClips.offer(clip)){
                            commandClips.poll();commandClips.offer(clip);
                            status="语音解码负载偏高，已丢弃最早一段；请稍放慢指令节奏";
                        }
                    }
                }
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
    private void consumeUtterance(String zhText,String enText,String grammarZh,
                                  String grammarEn,SpeakerEvidence zhSpk,
                                  SpeakerEvidence enSpk,CommandTemplateStore.Result personal){
        if(trainingMode)return;
        long now=SystemClock.elapsedRealtime();
        SpeakerEvidence ev=zhSpk;
        if(ev==null || (enSpk!=null && enSpk.frames>ev.frames))ev=enSpk;
        if(profile.isEnrolling()){
            synchronized(enrollLock){
                if(!enrollArmed || enrollReady)return;
                enrollArmed=false;
                boolean phraseCorrect=EnrollmentPrompts.matches(enrollStep,zhText,enText);
                boolean usableSpeaker=ev!=null;
                String heard="中: "+zhText+" | EN: "+enText;
                if(phraseCorrect && usableSpeaker && profile.add(ev.vector)){
                    enrollReady=true;
                    ownerStatus="第 "+(enrollStep+1)+"/"+EnrollmentPrompts.count()+" 条：声纹采集有效";
                    status="文字核对及声纹一致性初检通过 · 点击『完成』";
                    publish(heard,null,null,false,0f,"CAPTURE_OK");
                }else{
                    if(!phraseCorrect)status="提示词未核对通过，请完整念『"+EnrollmentPrompts.at(enrollStep)+"』";
                    else if(!usableSpeaker)status="声纹特征不足：请完整、稍慢地朗读较长句";
                    else status="这一段声纹与之前录入的声音差异太大，请重新录制";
                    publish(heard,null,null,false,0f,"CAPTURE_FAIL");
                }
            }
            return;
        }
        if(profile.hasProfile()){
            if(ev!=null){
                SpeakerProfile.Match m=profile.verify(ev.vector);
                if(m.verified)lastOwnerVerifiedMs=now;
                ownerStatus=(m.verified?"声纹相符":"声纹不符")+"（实验性）相似度 "
                    +String.format(java.util.Locale.ROOT,"%.2f",m.similarity)
                    +" · 门槛 "+String.format(java.util.Locale.ROOT,"%.2f",m.threshold)
                    +(m.calibrated?" · 已个人校准":" · 旧档案建议重录");
            }else if(now-lastOwnerVerifiedMs<180000 && lastOwnerVerifiedMs>0)
                ownerStatus="本句短，无法独立确认声纹；之前曾相符（不作为授权）";
            else ownerStatus="本句缺少足够声纹特征，不代表不是本人";
        }else ownerStatus="尚未录入声纹 · 仅预览识别";

        SpeechFusion.Decision decision=SpeechFusion.decide(
            zhText,enText,grammarZh,grammarEn,personal,awake);
        String diagnostic=personal==null?"无模板结果":personal.diagnostic();
        RexCommand cmd=decision.command;
        boolean wakeEvent=decision.woke;
        String signature=decision.origin+"|"+cmd+"|"+wakeEvent+"|"+RexCommand.norm(zhText)
            +"|"+RexCommand.norm(enText);
        if(signature.equals(lastFinalSignature)&&now-lastFinalMs<550)return;
        lastFinalSignature=signature;lastFinalMs=now;
        String transcribed="中: "+zhText+" | EN: "+enText;
        if(!grammarZh.isEmpty()||!grammarEn.isEmpty())
            transcribed+=" | 词表中: "+grammarZh+" / EN: "+grammarEn;
        if(decision.conflict){
            status="不同动作冲突，拒绝猜测 · "+decision.reason+" · "+diagnostic;
            cmd=null;wakeEvent=false;
        }else if(wakeEvent){
            awake=true;
            status="REx 已唤醒 · 多路确认 "+decision.origin+" · "+diagnostic;
        }else if(cmd==RexCommand.SLEEP){
            awake=false;lastOwnerVerifiedMs=0;
            status="已听到睡觉 · 来源 "+decision.origin+" · 返回待机 · "+diagnostic;
        }else if(cmd!=null){
            status="识别「"+cmd.label+"」· 来源 "+decision.origin
                +" · "+diagnostic+" · 仅预览，不发运动红外";
        }else{
            status=(awake?"保持唤醒":"待机等待唤醒")+" · "+decision.reason
                +" · 未确认，未输出动作";
        }
        publish(transcribed,null,cmd,wakeEvent,0f);
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
