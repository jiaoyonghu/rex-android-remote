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
 * REx v0.4 dual particle volumes: large filaments + small dense inner kernel.
 * Neither is a geometric sphere/ring/disc; both have irregular radial surfaces.
 * Sleep: both keep particle-relative positions rigid while globally rotating.
 * Awake: independent particle motion and distinct core/outer trajectories.
 * Voice energy affects deformation only after offline ASR sees speech hypotheses.
 */
public final class RexView extends View {
    public interface Actions {
        void onSend(RexCommand command);
        void onStartVoice();
        void onStopVoice();
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
    private boolean manual;
    private String message="v0.4 · 中英双语离线监听 · 红外仅联调";
    private String irStatus="正在检测 IR";
    private String lastText="请说 Rex / 瑞克斯，再用中文或 English 发出命令";
    private String voiceStatus="语音未启动";
    private String commandText="";
    private float voiceLevel;
    private float activeBlend;
    private boolean speaking;
    private boolean voiceStarted;
    private long wakeUntilMs;
    private long lastTapMs;
    private long downMs;
    private int taps;
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
        add(121,165,239,225,"↑ 前进","0.6 m/s",RexCommand.FORWARD);
        add(20,237,119,299,"← 左移","0.4 m/s",RexCommand.LEFT);
        add(122,237,238,299,"■ 停止","站立保持",RexCommand.STOP);
        add(241,237,340,299,"右移 →","0.4 m/s",RexCommand.RIGHT);
        add(121,311,239,371,"↓ 后退","0.4 m/s",RexCommand.BACKWARD);
        add(20,389,172,444,"↶ 左转圈","0.4 rad/s",RexCommand.TURN_LEFT);
        add(188,389,340,444,"右转圈 ↷","0.4 rad/s",RexCommand.TURN_RIGHT);
        add(20,455,172,508,"站立","STAND",RexCommand.STAND);
        add(188,455,340,508,"闪身步","左右随机 3 秒",RexCommand.DODGE);
        // Leave the REX-left lower blank region free in BOTH voice and button modes.
        add(188,520,340,573,"转个圈","随机方向",RexCommand.TURN_AROUND);
        add(20,585,172,639,"激活","ACTIVE",RexCommand.ACTIVE);
        add(188,585,340,639,"失能 · 高风险","二次确认",RexCommand.DISABLE);
        setLayerType(View.LAYER_TYPE_HARDWARE,null);
    }
    private void add(float l,float t,float r,float b,String title,String sub,RexCommand cmd){
        controls.add(new TouchButton(l,t,r,b,title,sub,cmd));
    }
    public void setIrStatus(String value){irStatus=value;invalidate();}
    public void setMessage(String value){message=value;invalidate();}
    public void setVoiceStopped(){voiceStarted=false;voiceStatus="语音监听已关闭";speaking=false;invalidate();}
    public void onVoice(String text,String command,boolean wake,float level,String status,boolean speakingNow){
        voiceStarted=true;
        if(status!=null)voiceStatus=status;
        speaking=speakingNow;
        if(wake){ manual=false;wakeUntilMs=SystemClock.elapsedRealtime()+15000; }
        if(text!=null){
            lastText=text;
            if(command!=null)commandText="已识别 "+command+" · 声纹未认证，未发射红外";
            else if(wake)commandText="REx 已唤醒";
            else commandText="没有授权运动指令";
        }
        if(level>0)voiceLevel=Math.max(voiceLevel,level);
        else if(speakingNow) voiceLevel=Math.max(voiceLevel,.28f);
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
        text(c,manual?"TACTICAL MANUAL":"VOICE REACTIVE / PARTICLE FIELD",25,68,10,0xFFC06A7B,false);
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
            float speechWave=energy*active*(inner?.36f:.42f)*
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
    private void filaments(Canvas c,float seconds){
        long now=SystemClock.elapsedRealtime();
        float target=now<wakeUntilMs?1f:0f;
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
        center(c,target>.5f?"AWAKE / OFFLINE RECOGNITION":"DORMANT / ROTATING FIELDS",
            cx,574,10,target>.5f?0xFFFF9DB4:0xFFAE7B87,false);
        if(active>.1f && energy>.06f)
            center(c,"VOICE FIELD  "+Math.round(energy*100)+"%",
                cx,590,8,0xFFFF9EBE,false);
    }
    private void voicePanel(Canvas c){
        box(c,20,596,340,661,0xA3241825,11);
        text(c,shortText(voiceStatus,40),28,615,10,0xFFF1BAC8,false);
        text(c,shortText(lastText,40),28,636,10,0xFFCFA1B2,false);
        text(c,shortText(commandText,39),28,653,9,0xFFFF8FA7,false);
        box(c,20,668,340,699,0xA74B1F30,11);
        center(c,voiceStarted?"本地语音运行中 / 长按关闭":"点击启动 APK 内置离线识别",180,688,12,0xFFF8D4DF,true);
        center(c,"REX 左侧空白处三击 → 按键模式",180,697,7,0xFF8F6D7B,false);
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
        center(c,"点击开启 / 长按关闭 / REX 左侧空白三击返回",180,687,9,0xFFC593A4,false);
    }
    @Override protected void onDraw(Canvas raw){
        super.onDraw(raw);
        int count=raw.save();
        raw.scale(getWidth()/(float)W,getHeight()/(float)H);
        background(raw);
        float t=(SystemClock.elapsedRealtime()-startedAt)/1000f;
        if(manual)drawControls(raw);
        else {filaments(raw,t);voicePanel(raw);}
        raw.restoreToCount(count);
        voiceLevel*=.91f;
        if(voiceLevel<.009f)voiceLevel=0;
        postInvalidateDelayed(33);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        float x=e.getX()*W/getWidth();
        float y=e.getY()*H/getHeight();
        if(e.getAction()==MotionEvent.ACTION_DOWN){downMs=SystemClock.elapsedRealtime();return true;}
        if(e.getAction()==MotionEvent.ACTION_UP){
            long now=SystemClock.elapsedRealtime();
            // Large, practical REX-left touch area ABOVE the transcript panel.
            // Exactly the same vacant rectangle is reserved in manual mode.
            if(x>=22 && x<=140 && y>=517 && y<=582){
                taps=(now-lastTapMs<730)?taps+1:1;
                lastTapMs=now;
                if(taps>=3){manual=!manual;taps=0;invalidate();}
                return true;
            }
            boolean longPress=now-downMs>1100;
            if(!manual && x>20 && x<340 && y>668 && y<699){
                if(longPress)actions.onStopVoice();else actions.onStartVoice();
                return true;
            }
            if(manual && x>20 && x<340 && y>652 && y<697){
                if(longPress)actions.onStopVoice();else actions.onStartVoice();
                return true;
            }
            if(manual)for(TouchButton b:controls)if(b.r.contains(x,y)){
                actions.onSend(b.command);return true;
            }
            return true;
        }
        return true;
    }
}
