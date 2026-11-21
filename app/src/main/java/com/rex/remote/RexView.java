package com.rex.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Random;

/**
 * REx dual particle volumes: large filaments + small dense inner kernel.
 * Neither is a geometric sphere/ring/disc; both have irregular radial surfaces.
 * Sleep: both keep particle-relative positions rigid while globally rotating.
 * Awake: independent particle motion and distinct core/outer trajectories.
 * Voice energy comes directly from live microphone PCM, independent of ASR results.
 */
public final class RexView extends View {
    public interface Actions {
        void onSend(RexCommand command);
        void onStartVoice();
        void onStopVoice();
        void onEnrollVoice();
        void onCaptureVoice(int step);
        void onCompleteVoice(int step);
        void onCancelVoiceEnrollment();
        void onOpenCommandTraining();
        void onSelectCommandPhrase(int index);
        void onCaptureCommandPhrase(int index);
        void onClearCommandPhrase(int index);
        void onFinishCommandTraining();
        void onResetDiagnostics();
        void onCopyDiagnostics(String report);
        default boolean onSaveFusionSettings(CommandFusion.Config settings){return false;}
    }
    private static final int W=360,H=780;
    private final Actions actions;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Particle> swarm=new ArrayList<>();
    private final ArrayList<Particle> coreSwarm=new ArrayList<>();
    private final ArrayList<TouchButton> controls=new ArrayList<>();
    private final float[] px=new float[1940];
    private final float[] py=new float[1940];
    private final float[] corePx=new float[1248];
    private final float[] corePy=new float[1248];
    private static final int PAGE_VOICE=0,PAGE_MANUAL=1,PAGE_OWNER=2,PAGE_TRAIN=3,
        PAGE_DIAG=4,PAGE_TUNE=5;
    // Voice-page-only transition: native particle positions <-> stable,
    // screen-wide scattering positions. All other page changes remain instant.
    private static final long PARTICLE_TRANSITION_MS=720;
    private final float[] scatterX=new float[1940],scatterY=new float[1940];
    private final float[] scatterCoreX=new float[1248],scatterCoreY=new float[1248];
    private boolean particleTransition,particleGathering;
    private long particleTransitionAt;
    private int page=PAGE_VOICE;
    private int outgoingPage=-1;
    private int trainIndex,trainCount;
    private boolean trainCapturing;
    private String trainInfo="点击采集，为这条固定指令录音至少6次，建议12至20次";
    private int enrollStep;
    private boolean enrollReady;
    private boolean enrollmentDone;
    private boolean enrollmentCapturing;
    private String message="v1.3 · 口令融合调参 · 语音红外未授权";
    private String irStatus="正在检测 IR";
    private String lastText="请说 Rex / Rex醒了吗 / 灭建国";
    private String voiceStatus="语音未启动";
    private String commandText="";
    private String ownerStatus="尚未录入声纹";
    private int enrollCount;
    private boolean awake;
    private float voiceLevel;
    private float activeBlend;
    private boolean speaking;
    private boolean voiceStarted;
    private long lastLeftTapMs;
    private long lastRightTapMs;
    private int leftTaps,rightTaps;
    private int diagnosticTaps;
    private int tuningTaps,tuningDrag=-1;
    private long lastTuningTapMs;
    private CommandFusion.Config tuningDraft;
    private long lastDiagnosticTapMs;
    private String diagEngine="未启动",diagKws="未捕获关键词",diagVad="尚无完整语音片段",
        diagPersonal="未评估",diagVerdict="尚无最终裁决",
        diagCause="请启动语音并说出一条固定指令",diagTiming="暂无耗时数据";
    private float diagDbfs=-90f;
    private boolean diagActivity;
    private long diagFrames,diagDrops,diagKwsAt,diagVadAt,diagVerdictAt;
    private long downMs;
    private final long startedAt=SystemClock.elapsedRealtime();
    private final Typeface regular=Typeface.create("sans-serif",Typeface.NORMAL);
    private final Typeface medium=Typeface.create("sans-serif-medium",Typeface.NORMAL);

    private static final class Particle {
        final float theta,phi,twist,phase,thickness,brightness,spread;
        Particle(Random random,float theta,float phi){
            this.theta=theta;
            this.phi=phi;
            twist=random.nextFloat()*6.28f;
            phase=random.nextFloat()*6.28f;
            thickness=0.24f+0.65f*random.nextFloat();
            brightness=0.22f+random.nextFloat()*0.78f;
            spread=0.82f+random.nextFloat()*0.46f;
        }
    }
    private static final class TouchButton {
        final RectF r;
        final String title,sub;
        final RexCommand command;
        TouchButton(float l,float t,float rr,float b,String title,String sub,RexCommand command){
            r=new RectF(l,t,rr,b);this.title=title;this.sub=sub;this.command=command;
        }
    }
    public RexView(Context context,Actions actions){
        super(context);this.actions=actions;
        Random r=new Random(271828);
        // 97 tangled micro-filaments, 20 small points per strand.
        // Neighboring points belong to the same strand, never long random chords.
        for(int g=0;g<97;g++){
            float baseTheta=(float)(2*Math.PI*r.nextDouble());
            float basePhi=(float)Math.acos(2*r.nextDouble()-1);
            float bend=r.nextFloat()*6.28f;
            for(int k=0;k<20;k++){
                float theta=baseTheta+(k-9.5f)*.056f+.10f*(float)Math.sin(k*.39f+bend);
                float phi=basePhi+(k-9.5f)*.028f+.09f*(float)Math.sin(k*.31f+bend);
                phi=Math.max(.05f,Math.min((float)Math.PI-.05f,phi));
                swarm.add(new Particle(r,theta,phi));
            }
        }
        // Small inner field: many more tightly packed microfilaments, intentionally
        // different spatial distribution and spin axis than the outer field.
        for (int g=0; g<78; g++) {
            float baseTheta=(float)(2*Math.PI*r.nextDouble());
            float basePhi=(float)Math.acos(2*r.nextDouble()-1);
            float bend=r.nextFloat()*6.28f;
            for (int k=0; k<16; k++) {
                float theta=baseTheta+(k-7.5f)*.084f+.13f*(float)Math.sin(k*.62f+bend);
                float phi=basePhi+(k-7.5f)*.035f+.11f*(float)Math.sin(k*.43f+bend);
                phi=Math.max(.04f,Math.min((float)Math.PI-.04f,phi));
                coreSwarm.add(new Particle(r,theta,phi));
            }
        }
        // Stratified random points cover the *entire* screen, including corners,
        // when the voice field disperses. Fixed destinations avoid visual jumps.
        Random scatterRandom=new Random(90731);
        initScatter(scatterRandom,scatterX,scatterY);
        initScatter(scatterRandom,scatterCoreX,scatterCoreY);
        add(121,165,239,225,"↑ 前进","0.6 m/s",RexCommand.FORWARD);
        add(20,237,119,299,"← 左移","0.4 m/s",RexCommand.LEFT);
        add(122,237,238,299,"■ 停止","站立保持",RexCommand.STOP);
        add(241,237,340,299,"右移 →","0.4 m/s",RexCommand.RIGHT);
        add(121,311,239,371,"↓ 后退","0.4 m/s",RexCommand.BACKWARD);
        add(20,389,172,444,"↶ 左转圈","0.4 rad/s",RexCommand.TURN_LEFT);
        add(188,389,340,444,"右转圈 ↷","0.4 rad/s",RexCommand.TURN_RIGHT);
        add(20,455,172,508,"站立","STAND",RexCommand.STAND);
        add(188,455,340,508,"闪身步","左右随机 3 秒",RexCommand.DODGE);
        // The left and right LOWER triple-tap spaces are free in BOTH modes.
        add(143,520,217,578,"转个圈","随机",RexCommand.TURN_AROUND);
        add(20,585,172,639,"激活","ACTIVE",RexCommand.ACTIVE);
        add(188,585,340,639,"失能 · 高风险","二次确认",RexCommand.DISABLE);
        tuningDraft=CommandFusion.load(context);
        setLayerType(View.LAYER_TYPE_HARDWARE,null);
    }
    private static void initScatter(Random random,float[] xs,float[] ys){
        for(int i=0;i<xs.length;i++){
            xs[i]=5f+random.nextFloat()*(W-10f);
            ys[i]=6f+random.nextFloat()*(H-12f);
        }
    }
    private void changePage(int target){
        if(target==page)return;
        if(page==PAGE_VOICE && target!=PAGE_VOICE){
            // v0.9 rendered the NEW page behind still-visible particles. Keep
            // the voice scene active until the very last scatter frame; then
            // switch atomically without leaving a single foreground particle.
            outgoingPage=target;
            particleTransition=true;particleGathering=false;
            particleTransitionAt=SystemClock.elapsedRealtime();
            invalidate();return;
        }
        if(target==PAGE_VOICE){
            outgoingPage=-1;particleTransition=true;particleGathering=true;
            particleTransitionAt=SystemClock.elapsedRealtime();
        }else{particleTransition=false;outgoingPage=-1;}
        page=target;
        invalidate();
    }
    private void openTuning(){
        // Reload only the SAVED values. Unsaved edits never reach VoiceService.
        tuningDraft=CommandFusion.load(getContext());
        changePage(PAGE_TUNE);
    }
    private void discardTuning(){
        tuningDraft=CommandFusion.load(getContext());tuningDrag=-1;
        changePage(PAGE_VOICE);
    }
    private void updateTuning(float x,int control){
        if(tuningDraft==null)tuningDraft=CommandFusion.defaults();
        float t=Math.max(0f,Math.min(1f,(x-36f)/288f));
        t=Math.round(t*100f)/100f;
        float k=tuningDraft.kwsWeight,n=tuningDraft.neuralWeight;
        float ng=tuningDraft.neuralThreshold,total=tuningDraft.totalThreshold;
        if(control==0){k=t;n=1f-t;}
        if(control==1){n=t;k=1f-t;}
        if(control==2)ng=.5f+.5f*t;
        if(control==3)total=t;
        tuningDraft=new CommandFusion.Config(k,n,ng,total);
        invalidate();
    }
    private void drawSettingSlider(Canvas c,int top,String name,float value,float min,float max,
                                   String note){
        box(c,20,top,340,top+80,0xB02D1B2E,11);
        text(c,name,30,top+22,12,0xFFFFCBDB,true);
        text(c,String.format(java.util.Locale.ROOT,"%.2f",value),285,top+22,14,0xFFFFA2C2,true);
        line(c,36,top+54,324,top+54,0x795C3B52,5);
        float pos=Math.max(0f,Math.min(1f,(value-min)/(max-min)));
        line(c,36,top+54,36+288*pos,top+54,0xFFFF83B2,5);
        solid(0xFFFFD5E3);c.drawCircle(36+288*pos,top+54,8,paint);
        text(c,note,32,top+74,8,0xFFC6A5B4,false);
    }
    private void drawFusionTuning(Canvas c){
        if(tuningDraft==null)tuningDraft=CommandFusion.defaults();
        center(c,"口令加权识别 / WEIGHTED FUSION",180,176,13,0xFFFFD1E1,true);
        center(c,"右上角三击返回不保存 · 拖动调整百分比",180,199,9,0xFFBDA0B1,false);
        drawSettingSlider(c,208,"Zipformer 命中权重",tuningDraft.kwsWeight,0f,1f,
            "KWS 仅命中=1 / 未命中=0，不是声学概率");
        drawSettingSlider(c,295,"openWakeWord 权重",tuningDraft.neuralWeight,0f,1f,
            "双权重合计100%，调整一项自动调整另一项");
        drawSettingSlider(c,382,"神经相似度门槛",tuningDraft.neuralThreshold,.5f,1f,
            "低于门槛记0；默认 0.92（未经实机校准）");
        drawSettingSlider(c,469,"最终综合得分门槛",tuningDraft.totalThreshold,0f,1f,
            "得分高于门槛才确认；VAD 必须先通过");
        box(c,20,560,340,639,0xAA261C2D,11);
        text(c,"计算：KWS×权重 + 个人有效分×权重",31,581,10,0xFFECC3D2,true);
        text(c,"CAM++ 仅用于声纹录入，不参与日常加权",31,600,9,0xFFC9A7B8,false);
        text(c,"冲突拒绝 · 单字风险保护 · 不自动发射红外",31,620,9,0xFFFFB3C6,false);
        box(c,20,650,170,697,0xAF3B2539,11);
        center(c,"取消修改",95,678,13,0xFFE4B5C9,true);
        box(c,190,650,340,697,0xC35F2B48,11);
        center(c,"保存参数",265,678,13,0xFFFFDAE9,true);
    }
    private void add(float l,float t,float r,float b,String title,String sub,RexCommand cmd){
        controls.add(new TouchButton(l,t,r,b,title,sub,cmd));
    }
    public void setIrStatus(String value){irStatus=value;invalidate();}
    public void setMessage(String value){message=value;invalidate();}
    public void onTraining(int index,int count,String info,String event){
        trainIndex=Math.max(0,Math.min(CommandPhrases.count()-1,index));
        trainCount=count;
        if(info!=null)trainInfo=info;
        if("TRAIN_LISTEN".equals(event))trainCapturing=true;
        if("TRAIN_OK".equals(event)||"TRAIN_FAIL".equals(event)||
            "TRAIN_EXIT".equals(event)||"TRAIN_CLEARED".equals(event)||
            "TRAIN_SELECTED".equals(event))trainCapturing=false;
        invalidate();
    }
    public void onDiagnostics(String engine,float dbfs,boolean activity,long frames,long drops,
                              String kws,String vad,String personal,String verdict,
                              String cause,String timing,long kwsAt,long vadAt,long verdictAt){
        if(engine!=null)diagEngine=engine;
        diagDbfs=dbfs;diagActivity=activity;diagFrames=frames;diagDrops=drops;
        if(kws!=null)diagKws=kws;
        if(vad!=null)diagVad=vad;
        if(personal!=null)diagPersonal=personal;
        if(verdict!=null)diagVerdict=verdict;
        if(cause!=null)diagCause=cause;
        if(timing!=null)diagTiming=timing;
        diagKwsAt=kwsAt;diagVadAt=vadAt;diagVerdictAt=verdictAt;
        if(page==PAGE_DIAG)invalidate();
    }
    public boolean isVoiceStarted(){return voiceStarted;}
    public void resetDiagnosticDisplay(){
        onDiagnostics("未启动",-90f,false,0,0,"未捕获关键词",
            "尚无完整语音片段","未评估","尚无最终裁决",
            "记录已清空，请启动监听并说一条指令","暂无耗时数据",0,0,0);
    }
    public void setVoiceStopped(){
        voiceStarted=false;voiceStatus="语音监听已关闭";speaking=false;awake=false;invalidate();
    }
    public void onVoice(String text,String partial,String command,boolean wakeEvent,
                        boolean awakeNow,float level,String status,boolean speakingNow,
                        String ownerNow,int voiceSamples,int currentStep,boolean sampleReady,
                        boolean captureActive){
        voiceStarted=true;
        if(status!=null)voiceStatus=status;
        if(ownerNow!=null)ownerStatus=ownerNow;
        enrollCount=voiceSamples;
        enrollStep=currentStep;
        enrollReady=sampleReady;
        enrollmentCapturing=captureActive;
        if(currentStep>=EnrollmentPrompts.count())enrollmentDone=true;
        awake=awakeNow;
        speaking=speakingNow;
        if(wakeEvent && page!=PAGE_OWNER && page!=PAGE_TRAIN && page!=PAGE_DIAG && page!=PAGE_TUNE)
            changePage(PAGE_VOICE); // never interrupt enrollment
        if(text!=null){
            lastText=text;
            if("SLEEP".equals(command))commandText="已听到『睡觉』 · 回待机";
            else if(command!=null)commandText="识别 "+command+" · 未授权发射语音红外";
            else if(wakeEvent)commandText="REx 已唤醒 · 持续待命";
            else commandText="非白名单指令 · 不执行";
        }else if(partial!=null && !partial.isEmpty()){
            lastText="实时（未确认）："+partial;
        }
        // Realtime samples arrive even BEFORE partial/final recognition output.
        if(level>0)voiceLevel=Math.max(voiceLevel,level);
        invalidate();
    }
    private void solid(int color){paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setColor(color);}
    private void line(Canvas c,float x1,float y1,float x2,float y2,int color,float width){
        solid(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(width);
        c.drawLine(x1,y1,x2,y2,paint);paint.setStyle(Paint.Style.FILL);
    }
    private void text(Canvas c,String s,float x,float y,float size,int color,boolean heavy){
        solid(color);paint.setTypeface(heavy?medium:regular);paint.setTextSize(size);
        c.drawText(s,x,y,paint);
    }
    private void center(Canvas c,String s,float x,float y,float size,int color,boolean heavy){
        solid(color);paint.setTypeface(heavy?medium:regular);paint.setTextSize(size);
        c.drawText(s,x-paint.measureText(s)*.5f,y,paint);
    }
    private static String shortText(String s,int chars){
        if(s==null)return "";
        return s.length()>chars?s.substring(0,chars-1)+"…":s;
    }
    private void box(Canvas c,float x1,float y1,float x2,float y2,int fill,float rad){
        solid(fill);c.drawRoundRect(x1,y1,x2,y2,rad,rad,paint);
    }
    private void background(Canvas c){
        c.drawColor(0xFF07080D);
        paint.setShader(new LinearGradient(0,0,0,H,0xFF14101D,0xFF07080D,Shader.TileMode.CLAMP));
        c.drawRect(0,0,W,H,paint);paint.setShader(null);
        // Very thin technical grid, never circular HUD rings.
        for(int x=18;x<=342;x+=36)line(c,x,112,x,698,0x142F4054,.55f);
        for(int y=112;y<=698;y+=36)line(c,18,y,342,y,0x142F4054,.55f);
        line(c,18,108,342,108,0x55485A70,.7f);
        line(c,18,704,342,704,0x55485A70,.7f);
        text(c,"R E X",25,48,27,0xFFFFDCE0,true);
        text(c,page==PAGE_MANUAL?"TACTICAL MANUAL":
            (page==PAGE_OWNER?"VOICEPRINT / GUIDED ENROLLMENT":
            (page==PAGE_TRAIN?"PERSONALIZED SPEECH COMMANDS":
            (page==PAGE_DIAG?"LIVE VOICE PIPELINE / DIAGNOSTICS":
            (page==PAGE_TUNE?"VOICE COMMAND / WEIGHTED SETTINGS":"VOICE REACTIVE / PARTICLE FIELD")))),
            25,68,10,0xFFC06A7B,false);
        text(c,"ONEPLUS ACE 3V  ·  ANDROID 16",25,87,9,0xFF8A6674,false);
        box(c,18,115,342,150,0x6C211B27,10);
        text(c,shortText(irStatus,39),27,137,10,0xFFD3ADB7,false);
        box(c,17,712,343,764,0xBC16151D,12);
        text(c,shortText(message,44),26,734,10,0xFFF0C5CB,false);
        text(c,"IR 单向传输 · 语音动作未授权 · 状态不代表机器人确认",26,752,8,0xFF9D7581,false);
    }
    /** Each volume is an irregular particle mesh; no primitive sphere or orbit rings. */
    private void renderVolume(Canvas c,float seconds,boolean inner,float cx,float cy,
                              float radius,float active,float energy){
        ArrayList<Particle> particles=inner?coreSwarm:swarm;
        float[] xs=inner?corePx:px;
        float[] ys=inner?corePy:py;
        int perStrand=inner?16:20;
        // Always rotate as a whole in idle. Relative particle positions DO NOT change
        // with time until active grows. The inner volume spins about its own
        // tilted axes at a different speed and direction.
        float spin=seconds*(inner?-.29f:.19f);
        float waveClock=seconds*(inner?11.4f:8.5f);
        int i=0;
        for(Particle a:particles){
            float theta=a.theta+spin;
            float phi=a.phi;
            // Movement between particles only during wake; core has a distinct
            // deformation field (not copied from the shell trajectories).
            if(active>.001f){
                theta+=active*(inner?.19f:.11f)*(float)Math.sin(
                    seconds*(inner?2.9f:1.7f)+a.phase*2.1f+a.phi*8f);
                phi+=active*(inner?.12f:.055f)*(float)Math.cos(
                    seconds*(inner?3.1f:2.0f)+a.twist+a.theta*7f);
            }
            float sx=(float)(Math.sin(phi)*Math.cos(theta));
            float sy=(float)Math.cos(phi);
            float sz=(float)(Math.sin(phi)*Math.sin(theta));
            // Static non-spherical bulges: visible even in idle, rotate rigidly.
            float lobes=(float)Math.sin(a.theta*(inner?8f:5f)+a.phi*4f+a.twist);
            float ridges=(float)Math.cos(a.phi*(inner?11f:7f)+a.phase);
            float outline=1f + (inner?.14f:.11f)*lobes + (inner?.09f:.055f)*ridges;
            float living=active*(inner?.18f:.12f)*(float)Math.sin(
                seconds*(inner?4.1f:2.2f)+a.twist*2f+phi*9f);
            float speechWave=energy*active*(inner?.52f:.65f)*
                (float)Math.sin(waveClock+phi*(inner?15f:10f)+a.phase*2.3f);
            float rr=radius*a.spread*(outline+living+speechWave);
            // 2.5D projection without a rendered circular core.
            float depth=1f/(1.18f-.20f*sz);
            float xx=cx+sx*rr*depth;
            float yy=cy+sy*rr*depth*(inner?.98f:.93f);
            if(inner){ // independent drift in a different plane
                xx+=active*energy*5f*(float)Math.sin(waveClock*.7f+phi*3f);
                yy+=active*energy*4f*(float)Math.cos(waveClock*.9f+theta*4f);
            } else {
                xx+=active*energy*8f*(float)Math.cos(waveClock*.4f+phi*4f);
                yy+=active*energy*10f*(float)Math.sin(waveClock*.5f+theta*3f);
            }
            xs[i]=xx;ys[i]=yy;
            float near=Math.max(0f,Math.min(1f,(sz+1f)*.5f));
            float brightness=inner?1.27f:1.15f;
            int alpha=Math.max(0,Math.min(235,
                Math.round(((inner?66:48)+(inner?158:146)*near)
                    *a.brightness*brightness)));
            int red=255;
            int green=inner?(100+(int)(76*near)):(70+(int)(67*near));
            int blue=inner?(130+(int)(72*near)):(100+(int)(51*near));
            solid(Color.argb(alpha,red,green,blue));
            c.drawCircle(xx,yy,a.thickness*(inner?.72f:1.03f),paint);
            if(i%perStrand!=0){
                float dx=xx-xs[i-1],dy=yy-ys[i-1];
                float maxDist=inner?270f:2400f;
                if(dx*dx+dy*dy<maxDist)
                    line(c,xs[i-1],ys[i-1],xx,yy,
                        Color.argb((int)(alpha*(inner?.48f:.41f)),255,green,blue),
                        inner?.32f:.45f);
            }
            if(i>3 && i%perStrand>3 && i%4==0){
                int j=i-3;
                float dx=xx-xs[j],dy=yy-ys[j];
                if(dx*dx+dy*dy<(inner?100f:590f))
                    line(c,xs[j],ys[j],xx,yy,
                        Color.argb((int)(alpha*.28f),255,green,blue),
                        inner?.24f:.32f);
            }
            i++;
        }
    }
    /** Smoothly moves each filament particle between the actual voice mesh and
     * a deterministic screen-wide distribution. The page itself is unchanged. */
    private void drawParticleTransition(Canvas c,float progress){
        float e=progress*progress*(3f-2f*progress); // cubic smoothstep
        float scattered=particleGathering?1f-e:e;
        float fade=particleGathering?(1f-e):(1f-e)*(1f-e);
        drawParticleCloud(c,false,scattered,fade,e);
        drawParticleCloud(c,true,scattered,fade,e);
    }
    private void drawParticleCloud(Canvas c,boolean inner,float scatter,float fade,float eased){
        ArrayList<Particle> particles=inner?coreSwarm:swarm;
        float[] meshX=inner?corePx:px,meshY=inner?corePy:py;
        float[] destX=inner?scatterCoreX:scatterX,destY=inner?scatterCoreY:scatterY;
        float linkStrength=particleGathering
            ? Math.max(0f,(eased-.62f)/.38f)
            : Math.max(0f,1f-eased*3f);
        int strandLength=inner?16:20;
        float lastX=0f,lastY=0f;
        for(int i=0;i<particles.size();i++){
            Particle point=particles.get(i);
            float x=meshX[i]+(destX[i]-meshX[i])*scatter;
            float y=meshY[i]+(destY[i]-meshY[i])*scatter;
            int alpha=Math.max(0,Math.min(210,
                (int)((inner?166:132)*point.brightness*fade)));
            int red=255,green=inner?144:111,blue=inner?189:147;
            solid(Color.argb(alpha,red,green,blue));
            c.drawCircle(x,y,(inner?.50f:.70f)+point.thickness*.50f,paint);
            if(linkStrength>0 && i%strandLength!=0){
                float dx=x-lastX,dy=y-lastY;
                if(dx*dx+dy*dy<2500f)
                    line(c,lastX,lastY,x,y,
                        Color.argb((int)(alpha*.37f*linkStrength),red,green,blue),
                        inner?.27f:.36f);
            }
            lastX=x;lastY=y;
        }
    }
    private void filaments(Canvas c,float seconds){
        float target=awake?1f:0f;
        activeBlend+=(target-activeBlend)*.08f;
        if(Math.abs(activeBlend-target)<.001f)activeBlend=target;
        float active=activeBlend;
        float energy=Math.min(1f,voiceLevel);
        float cx=180f,cy=367f;
        float shellRadius=122f*(.70f+.30f*active);
        float coreRadius=shellRadius*.25f;
        // Subtle atmospheric scatter only; no bright sphere and no HUD circles.
        paint.setShader(new RadialGradient(cx,cy,145f,
            0x292E111C,0x00100C12,Shader.TileMode.CLAMP));
        c.drawRect(32,202,328,525,paint);
        paint.setShader(null);
        renderVolume(c,seconds,false,cx,cy,shellRadius,active,energy);
        renderVolume(c,seconds,true,cx,cy,coreRadius,active,energy);
        center(c,"R E X",cx,552,22,0xFFFFD9E5,true);
        center(c,target>.5f?"AWAKE / UNTIL 睡觉":"DORMANT / ROTATING FIELDS",
            cx,574,10,target>.5f?0xFFFF9DB4:0xFFAE7B87,false);
        if(active>.1f && energy>.06f)
            center(c,"LIVE MIC  "+Math.round(energy*100)+"%",
                cx,590,8,0xFFFF9EBE,false);
    }
    private void voicePanel(Canvas c){
        // Both left and right of REX are reserved as triple-tap hot zones.
        // No single-tap action is mapped here to avoid accidental entry.
        box(c,20,596,340,665,0xA3241825,11);
        text(c,shortText(voiceStatus,38),28,609,9,0xFFF1BAC8,false);
        text(c,shortText(lastText,39),28,625,9,0xFFFFC0CE,false);
        text(c,shortText(ownerStatus,39),28,642,9,0xFFEFA2B7,false);
        text(c,shortText(commandText,39),28,657,8,0xFFFF8FA7,false);
        box(c,20,668,340,699,0xA74B1F30,11);
        center(c,voiceStarted?"离线语音运行中 / 长按关闭":"点击启动内置离线语音",180,687,12,0xFFF8D4DF,true);
        center(c,"REX 左侧三击按键 / 右侧三击声纹录入",180,697,7,0xFF8F6D7B,false);
    }
    private void drawControls(Canvas c){
        for(TouchButton b:controls){
            boolean danger=b.command==RexCommand.DISABLE;
            box(c,b.r.left,b.r.top,b.r.right,b.r.bottom,
                danger?0xAA572532:0xA72A1A29,10);
            line(c,b.r.left+9,b.r.top+1,b.r.right-9,b.r.top+1,
                danger?0xBBFF6574:0x88694760,.7f);
            center(c,b.title,b.r.centerX(),b.r.top+24,14,
                danger?0xFFFFB1BD:0xFFF5D6DD,true);
            center(c,b.sub,b.r.centerX(),b.r.top+43,9,0xFFB693A1,false);
        }
        box(c,20,652,340,697,0x96382537,11);
        center(c,"本地语音 · "+(voiceStarted?"已启动":"未启动"),180,671,11,0xFFF2C0CD,true);
        center(c,"左下三击切换 / 右下三击进入声纹录入",180,687,9,0xFFC593A4,false);
    }
    private void drawEnrollment(Canvas c){
        center(c,"主人声纹 / VOICEPRINT",180,191,18,0xFFFFCEDD,true);
        if(enrollmentDone){
            box(c,20,230,340,360,0xA32F1829,15);
            center(c,"全部提示词采集完成",180,286,16,0xFFFFCDD9,true);
            center(c,"声纹特征已保存在本机",180,319,11,0xFFCFA9B6,false);
        }else{
            box(c,20,225,340,349,0xAF231925,15);
            text(c,"当前提示词 · "+(enrollStep+1)+" / "+EnrollmentPrompts.count(),34,248,12,0xFFC9A1B1,true);
            center(c,EnrollmentPrompts.at(Math.max(0,Math.min(enrollStep,EnrollmentPrompts.count()-1))),
                180,289,EnrollmentPrompts.at(Math.max(0,Math.min(enrollStep,EnrollmentPrompts.count()-1))).length()>16?13:15,
                0xFFFFE5EB,true);
            center(c,"点击『采集』朗读，只采样有效声音特征",180,323,10,0xFFC3A3AF,false);
        }
        box(c,20,377,172,442,enrollmentDone?0x6643212B:0xAD50263B,12);
        center(c,"采 集",96,413,16,0xFFFFD9E4,true);
        box(c,188,377,340,442,enrollReady?0xA04F2945:0x66301C2C,12);
        center(c,"完 成",264,413,16,0xFFFFD9E4,true);
        String stage=enrollmentDone?"录入结束":
            enrollReady?"采集成功 ✓  点击『完成』进入下一条":
            enrollmentCapturing?"正在采集 · 请朗读上方提示词…":
            "尚未完成本条 · 请点击『采集』";
        center(c,stage,180,468,11,enrollReady?0xFFF6C6D4:0xFFBBA2AE,false);
        box(c,20,482,172,521,0x8D331C2E,9);
        center(c,"取消本次",96,506,12,0xFFD9A6B8,false);
        box(c,188,482,340,521,0x8D331C2E,9);
        center(c,"返回语音页",264,506,12,0xFFD9A6B8,false);
        // Matching left/right triple-tap areas remain blank (y = 532..582).
        center(c,"左侧三击按键",85,598,9,0xFFC79EAE,false);
        center(c,"右侧三击声纹",278,598,9,0xFFC79EAE,false);
        box(c,20,605,340,635,0x9D563046,10);
        center(c,"进入固定口令个性化训练  →",180,625,11,0xFFFFC5D4,true);
        box(c,20,640,340,697,0x9F251A27,11);
        text(c,shortText(voiceStatus,39),30,658,9,0xFFF0B8CA,false);
        text(c,shortText(lastText,37),30,675,9,0xFFFFC5D4,false);
        text(c,shortText(ownerStatus,38),30,690,8,0xFFD2A2B7,false);
    }
    private String ageFor(long eventAt){
        if(eventAt<=0)return "无事件";
        long seconds=Math.max(0,(SystemClock.elapsedRealtime()-eventAt)/1000);
        return seconds<120?seconds+"秒前":"超过2分钟";
    }
    private void diagRow(Canvas c,int top,String number,String heading,String detail,String meta){
        box(c,20,top,340,top+72,0xB12B1B2D,11);
        text(c,number,30,top+22,13,0xFFFF9EB9,true);
        text(c,heading,61,top+21,12,0xFFFFD2DE,true);
        text(c,shortText(detail,37),30,top+44,10,0xFFF1C2D2,false);
        text(c,shortText(meta,48),30,top+61,8,0xFFC4A0B0,false);
    }
    private String diagnosticReport(){
        return "REx v1.3 语音链路诊断\n"+
            "引擎: "+diagEngine+"\n"+
            String.format(java.util.Locale.ROOT,"PCM: %.1f dBFS, 活动提示=%s, 帧=%d, 丢弃=%d\n",
                diagDbfs,diagActivity,diagFrames,diagDrops)+
            "KWS: "+diagKws+" ("+ageFor(diagKwsAt)+")\n"+
            "VAD: "+diagVad+" ("+ageFor(diagVadAt)+")\n"+
            "个人模型: "+diagPersonal+"\n"+
            "最终裁决: "+diagVerdict+" ("+ageFor(diagVerdictAt)+")\n"+
            "卡点提示: "+diagnosticHint()+"\n"+
            "阶段耗时: "+diagTiming+"\n"+
            "注：音量活动提示不等于VAD通过；不保存原始录音。";
    }
    private String diagnosticHint(){
        long now=SystemClock.elapsedRealtime();
        if(diagDrops>0)return "存在过期录音丢弃：请检查模型计算负载";
        if(diagKwsAt>diagVadAt && diagKwsAt>0 && now-diagKwsAt>3000)
            return "KWS已命中但VAD未出段：请检查语音分段";
        if(diagVadAt>0 && diagKwsAt==0 && diagVerdictAt>=diagVadAt)
            return "VAD已收到语音，但KWS未命中：检查发音或阈值";
        return diagCause;
    }
    private void drawDiagnostics(Canvas c){
        center(c,"实时语音链路诊断 · 仅观察，不改阈值",180,178,13,0xFFFFD4E1,true);
        center(c,"每说一次固定口令，观察停在哪一阶段",180,197,9,0xFFC6A5B4,false);
        diagRow(c,209,"01","麦克风 / PCM",
            String.format(java.util.Locale.ROOT,"音量 %.1f dBFS · %s",diagDbfs,
                diagActivity?"声音活动提示":"未触发活动提示"),
            "已读 "+diagFrames+" 帧 · 丢弃队列 "+diagDrops+" 次 · "+shortText(diagEngine,19));
        diagRow(c,286,"02","Zipformer KWS（实时）",diagKws,
            "最近 KWS 事件 "+ageFor(diagKwsAt)+" · 只检测预设关键词");
        diagRow(c,363,"03","Silero VAD（语音分段）",diagVad,
            "最近 VAD 片段 "+ageFor(diagVadAt)+" · 与音量活动提示独立");
        diagRow(c,440,"04","个人神经模板（按需）",diagPersonal,
            "TFLite 特征 · 未训练时不会执行个人匹配");
        diagRow(c,517,"05","最终裁决",diagVerdict,
            "最近裁决 "+ageFor(diagVerdictAt)+" · 不连接红外动作");
        box(c,20,594,340,647,0xBA402339,10);
        text(c,shortText(diagnosticHint(),35),29,611,10,0xFFFFC6D8,true);
        text(c,shortText(diagTiming,57),29,627,8,0xFFE2B3C9,false);
        text(c,"没有VAD片段时无法确认原因；请结合KWS和音量观察",29,641,8,0xFFB78A9F,false);
        box(c,20,656,120,699,0xAD502842,10);
        center(c,voiceStarted?"监听运行中":"启动监听",70,673,10,0xFFFFD4E1,true);
        center(c,"长按关闭",70,690,8,0xFFCAA1B4,false);
        box(c,129,656,229,699,0xA83B2A3C,10);
        center(c,"清空记录",179,673,10,0xFFFFD4E1,true);
        center(c,"长按复制",179,690,8,0xFFCAA1B4,false);
        box(c,238,656,340,699,0xAD502842,10);
        center(c,"返回智能体",289,682,10,0xFFFFD4E1,true);
    }
    private void drawCommandTraining(Canvas c){
        center(c,"个性化口令训练 / COMMAND TRAINING",180,187,15,0xFFFFC6D7,true);
        center(c,"openWakeWord 深层语音特征 · 个人分类器持续训练",180,211,9,0xFFCDA8B9,false);
        box(c,20,231,340,300,0xAD2C1C2C,12);
        center(c,"第 "+(trainIndex+1)+" / "+CommandPhrases.count()+" 条口令",180,250,10,0xFFC9AAB7,false);
        center(c,CommandPhrases.at(trainIndex),180,276,
            CommandPhrases.at(trainIndex).length()>9?16:22,0xFFFFD9E6,true);
        box(c,20,310,172,361,0xA6382335,10);
        center(c,"← 上一条",96,343,13,0xFFFFCDDC,true);
        box(c,188,310,340,361,0xA6382335,10);
        center(c,"下一条 →",264,343,13,0xFFFFCDDC,true);
        center(c,"已采集 "+trainCount+" / 20 次 · 可重复采集",180,385,10,
            trainCount>=PersonalNeuralStore.MIN_TAKES?0xFFFFBED0:0xFFB6A2AF,false);
        box(c,20,400,340,451,0xBB5A2842,12);
        center(c,trainCapturing?"正在采集 · 请朗读后停顿":
            (trainCount>=PersonalNeuralStore.MAX_TAKES?"再次采集 · 自动替换最早一次":"点击采集本条口令"),180,431,15,0xFFFFDEE9,true);
        box(c,20,465,172,511,0xA2362432,10);
        center(c,"清除本条样本",96,493,11,0xFFFFBCC9,false);
        box(c,188,465,340,511,0xA2362432,10);
        center(c,"返回声纹页",264,493,11,0xFFFFBCC9,false);
        // Keep original symmetrical empty triple-tap hot areas (526..584).
        center(c,"左侧三击按键 / 右侧三击声纹",180,603,9,0xFFC69EAF,false);
        box(c,20,619,340,697,0xA1271B2A,11);
        text(c,shortText(trainInfo,42),28,641,10,0xFFFFCBDA,false);
        text(c,shortText(voiceStatus,42),28,658,9,0xFFC8A5B2,false);
        text(c,"拒绝相似口令的模糊猜测 · 未授权发射语音红外",28,675,9,0xFFD9AFC1,false);
        text(c,"每次只念一条；失败可以重采；录过的样本保存在本机",28,690,8,0xFFAC8796,false);
    }
    public void openCommandTraining(){
        changePage(PAGE_TRAIN);trainIndex=0;trainCount=0;trainCapturing=false;
        trainInfo="请选择口令，点击采集并朗读；至少录 3 次";
        invalidate();
    }
    public void openEnrollment(){
        changePage(PAGE_OWNER);enrollStep=0;enrollReady=false;enrollmentDone=false;
        enrollmentCapturing=false;lastText="按『采集』后朗读第一条提示词";invalidate();
    }
    public void setEnrollmentMessage(String text){lastText=text;invalidate();}
    @Override protected void onDraw(Canvas raw){
        super.onDraw(raw);
        int count=raw.save();
        raw.scale(getWidth()/(float)W,getHeight()/(float)H);
        background(raw);
        float t=(SystemClock.elapsedRealtime()-startedAt)/1000f;
        float transitionProgress=1f;
        if(particleTransition){
            transitionProgress=Math.max(0f,Math.min(1f,
                (SystemClock.elapsedRealtime()-particleTransitionAt)
                    /(float)PARTICLE_TRANSITION_MS));
        }
        boolean leaving=particleTransition && !particleGathering && outgoingPage>=0;
        if(leaving && transitionProgress>=1f){
            // The target screen becomes visible on the first frame with ZERO
            // transition particles. No draw calls can leak over its contents.
            page=outgoingPage;outgoingPage=-1;
            particleTransition=false;leaving=false;
        }
        if(page==PAGE_MANUAL)drawControls(raw);
        else if(page==PAGE_OWNER)drawEnrollment(raw);
        else if(page==PAGE_TRAIN)drawCommandTraining(raw);
        else if(page==PAGE_DIAG)drawDiagnostics(raw);
        else if(page==PAGE_TUNE)drawFusionTuning(raw);
        else if(leaving){
            // Fade the old HUD independently; do not redraw stable filaments
            // underneath the particles that are already scattering.
            float e=transitionProgress*transitionProgress*(3f-2f*transitionProgress);
            int layer=raw.saveLayerAlpha(0,0,W,H,
                Math.max(0,Math.min(255,(int)(255f*(1f-e)))));
            voicePanel(raw);
            raw.restoreToCount(layer);
        }else {
            if(particleTransition && particleGathering){
                float e=transitionProgress*transitionProgress*(3f-2f*transitionProgress);
                int layer=raw.saveLayerAlpha(0,0,W,H,
                    Math.min(255,Math.max(0,(int)(255f*e))));
                filaments(raw,t);voicePanel(raw);
                raw.restoreToCount(layer);
            }else{filaments(raw,t);voicePanel(raw);}
        }
        if(particleTransition){
            drawParticleTransition(raw,transitionProgress);
            if(transitionProgress>=1f)particleTransition=false;
        }
        raw.restoreToCount(count);
        voiceLevel*=.91f;
        if(voiceLevel<.009f)voiceLevel=0;
        postInvalidateDelayed(33);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        float x=e.getX()*W/getWidth();
        float y=e.getY()*H/getHeight();
        if(e.getAction()==MotionEvent.ACTION_DOWN){
            downMs=SystemClock.elapsedRealtime();
            tuningDrag=-1;
            if(page==PAGE_TUNE && x>=20 && x<=340){
                int[] tops={208,295,382,469};
                for(int i=0;i<4;i++)if(y>=tops[i]+35 && y<=tops[i]+76){
                    tuningDrag=i;updateTuning(x,i);break;
                }
            }
            return true;
        }
        if(e.getAction()==MotionEvent.ACTION_MOVE && page==PAGE_TUNE && tuningDrag>=0){
            updateTuning(x,tuningDrag);return true;
        }
        if(e.getAction()==MotionEvent.ACTION_CANCEL){tuningDrag=-1;return true;}
        if(e.getAction()==MotionEvent.ACTION_UP){
            long now=SystemClock.elapsedRealtime();
            // Dedicated top-right triple tap opens / closes parameter editor.
            // Changes only persist when the SAVE button is explicitly pressed.
            if((page==PAGE_VOICE||page==PAGE_TUNE) && x>=235 && x<=348 &&
                    y>=12 && y<=104){
                tuningTaps=(now-lastTuningTapMs<730)?tuningTaps+1:1;
                lastTuningTapMs=now;
                if(tuningTaps>=3){
                    tuningTaps=0;
                    if(page==PAGE_TUNE)discardTuning();else openTuning();
                }
                return true;
            }
            if(page==PAGE_TUNE){
                if(tuningDrag>=0){updateTuning(x,tuningDrag);tuningDrag=-1;return true;}
                if(y>=650 && y<=699){
                    if(x<180)discardTuning();
                    else if(actions.onSaveFusionSettings(tuningDraft)){
                        tuningDraft=CommandFusion.load(getContext());
                        changePage(PAGE_VOICE);
                    }
                }
                return true;
            }
            // NEW: top-left three taps on the agent page (or the diagnostic
            // page) are the isolated diagnostics shortcut. Original lower
            // left/right triple-tap controls remain unchanged.
            if((page==PAGE_VOICE||page==PAGE_DIAG) && x>=12 && x<=125 && y>=12 && y<=104){
                diagnosticTaps=(now-lastDiagnosticTapMs<730)?diagnosticTaps+1:1;
                lastDiagnosticTapMs=now;
                if(diagnosticTaps>=3){
                    diagnosticTaps=0;
                    changePage(page==PAGE_DIAG?PAGE_VOICE:PAGE_DIAG);
                }
                return true;
            }
            if(page==PAGE_DIAG){
                if(y>=656 && y<=699){
                    if(x<123){
                        if(now-downMs>1100)actions.onStopVoice();
                        else actions.onStartVoice();
                    }else if(x<233){
                        if(now-downMs>800)actions.onCopyDiagnostics(diagnosticReport());
                        else actions.onResetDiagnostics();
                    }
                    else changePage(PAGE_VOICE);
                }
                return true;
            }
            // Symmetric large blanks immediately LEFT and RIGHT of centered REX.
            if(y>=526 && y<=584 && x>=22 && x<=140){
                leftTaps=(now-lastLeftTapMs<730)?leftTaps+1:1;
                lastLeftTapMs=now;rightTaps=0;
                if(leftTaps>=3){
                    leftTaps=0;
                    if(page==PAGE_OWNER)actions.onCancelVoiceEnrollment();
                    if(page==PAGE_TRAIN)actions.onFinishCommandTraining();
                    changePage((page==PAGE_MANUAL)?PAGE_VOICE:PAGE_MANUAL);
                }
                return true;
            }
            if(y>=526 && y<=584 && x>=220 && x<=338){
                rightTaps=(now-lastRightTapMs<730)?rightTaps+1:1;
                lastRightTapMs=now;leftTaps=0;
                if(rightTaps>=3){
                    rightTaps=0;
                    if(page==PAGE_OWNER){
                        actions.onCancelVoiceEnrollment();changePage(PAGE_VOICE);
                    }else if(page==PAGE_TRAIN){
                        actions.onFinishCommandTraining();openEnrollment();actions.onEnrollVoice();
                    }else{
                        openEnrollment();actions.onEnrollVoice();
                    }
                    invalidate();
                }
                return true;
            }
            boolean longPress=now-downMs>1100;
            if(page==PAGE_TRAIN){
                if(y>=310 && y<=361){
                    trainIndex=Math.max(0,Math.min(CommandPhrases.count()-1,
                        trainIndex+(x<180?-1:1)));
                    trainCount=0;trainCapturing=false;
                    actions.onSelectCommandPhrase(trainIndex);invalidate();return true;
                }
                if(y>=400 && y<=451){
                    if(!trainCapturing){
                        trainCapturing=true;actions.onCaptureCommandPhrase(trainIndex);
                    }
                    invalidate();return true;
                }
                if(y>=465 && y<=511){
                    if(x<180){actions.onClearCommandPhrase(trainIndex);}
                    else{actions.onFinishCommandTraining();openEnrollment();actions.onEnrollVoice();}
                    invalidate();return true;
                }
                return true;
            }
            if(page==PAGE_OWNER){
                if(y>=377 && y<=442){
                    if(x>=20 && x<=172 && !enrollReady && !enrollmentDone)
                        actions.onCaptureVoice(enrollStep);
                    if(x>=188 && x<=340 && enrollReady && !enrollmentDone)
                        actions.onCompleteVoice(enrollStep);
                    return true;
                }
                if(y>=605 && y<=635){
                    actions.onCancelVoiceEnrollment();
                    openCommandTraining();actions.onOpenCommandTraining();return true;
                }
                if(y>=482 && y<=521){
                    if(x>=20 && x<=172){actions.onCancelVoiceEnrollment();openEnrollment();actions.onEnrollVoice();}
                    else if(x>=188 && x<=340){actions.onCancelVoiceEnrollment();changePage(PAGE_VOICE);}
                    return true;
                }
                return true;
            }
            if(page==PAGE_VOICE && x>20 && x<340 && y>668 && y<699){
                if(longPress)actions.onStopVoice();else actions.onStartVoice();
                return true;
            }
            if(page==PAGE_MANUAL && x>20 && x<340 && y>652 && y<697){
                if(longPress)actions.onStopVoice();else actions.onStartVoice();
                return true;
            }
            if(page==PAGE_MANUAL)for(TouchButton b:controls)if(b.r.contains(x,y)){
                actions.onSend(b.command);return true;
            }
            return true;
        }
        return true;
    }
}
