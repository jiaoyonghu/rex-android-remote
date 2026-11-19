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
 * REx particle agent v0.2. No center core and no rings.
 * Idle: small/dense/static cloud at 70% active size.
 * Wake: red filament particles disperse with animated swirl; recognized speech
 * causes large wave deformation. Animated volume uses Android speech onset,
 * not ambient background microphone noise. Voice verification remains locked.
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
    private final ArrayList<TouchButton> controls=new ArrayList<>();
    private final float[] px=new float[1940];
    private final float[] py=new float[1940];
    private boolean manual;
    private String message="v0.2 · 红外联调 · 机器人未确认";
    private String irStatus="正在检测 IR";
    private String lastText="请点「开启监听」，再说 Rex";
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
        add(121,165,239,225,"↑ 前进","0.6 m/s",RexCommand.FORWARD);
        add(20,237,119,299,"← 左移","0.4 m/s",RexCommand.LEFT);
        add(122,237,238,299,"■ 停止","站立保持",RexCommand.STOP);
        add(241,237,340,299,"右移 →","0.4 m/s",RexCommand.RIGHT);
        add(121,311,239,371,"↓ 后退","0.4 m/s",RexCommand.BACKWARD);
        add(20,389,172,444,"↶ 左转圈","0.4 rad/s",RexCommand.TURN_LEFT);
        add(188,389,340,444,"右转圈 ↷","0.4 rad/s",RexCommand.TURN_RIGHT);
        add(20,455,172,508,"站立","STAND",RexCommand.STAND);
        add(188,455,340,508,"闪身步","左右随机 3 秒",RexCommand.DODGE);
        add(20,520,172,573,"转个圈","随机方向",RexCommand.TURN_AROUND);
        add(188,520,340,573,"激活","ACTIVE",RexCommand.ACTIVE);
        add(20,585,340,639,"失能  ·  高风险","二次确认",RexCommand.DISABLE);
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
    private void filaments(Canvas c,float seconds){
        long now=SystemClock.elapsedRealtime();
        float target=now<wakeUntilMs?1f:0f;
        activeBlend+=(target-activeBlend)*.078f;
        if(Math.abs(activeBlend-target)<.001f)activeBlend=target;
        float energy=Math.min(1f,voiceLevel);
        // Exactly 70% when asleep; ACTIVE expands to 100%.
        float radius=122f*(.7f+.3f*activeBlend);
        float cx=180f,cy=367f;
        float movement=activeBlend;
        // Diffuse red atmosphere WITHOUT a separate white core.
        paint.setShader(new RadialGradient(cx,cy,159f,0x383C1321,0x00100C12,Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy,159f,paint);paint.setShader(null);
        float time=seconds*.67f;
        float t = time*movement;
        float waveTime = seconds*9f;
        int i=0;
        for(Particle a:swarm){
            float theta=a.theta+t*(.16f+.03f*(float)Math.sin(a.twist));
            float phi=a.phi;
            float sx=(float)(Math.sin(phi)*Math.cos(theta));
            float sy=(float)Math.cos(phi);
            float sz=(float)(Math.sin(phi)*Math.sin(theta));
            float ripple=(float)Math.sin(phi*9f+theta*5f+a.twist*2f+t*2.7f);
            float coil=(float)Math.sin(theta*7f+phi*12f+a.phase);
            float distortion=1f + .11f*ripple*movement + .075f*coil;
            // Large local deformation ONLY while the recognition engine reports speech.
            float voiceWave = energy*movement*(.20f+.26f*(float)Math.sin(waveTime+a.phase+phi*12f))
                    *(float)Math.sin(theta*3f+waveTime*.41f);
            float r=radius*a.spread*(distortion+voiceWave);
            float depth=1f/(1.23f-.25f*sz);
            float xx=cx+sx*r*depth + movement*energy*10f*(float)Math.sin(phi*8f+waveTime);
            float yy=cy+sy*r*depth*.92f + movement*energy*12f*(float)Math.cos(theta*6f+waveTime);
            px[i]=xx;py[i]=yy;
            float a1=Math.max(0f,Math.min(1f, (sz+1f)/2f));
            int alpha=(int)((38+140*a1)*a.brightness*(.76f+.24f*activeBlend));
            solid(Color.argb(alpha,235,56+(int)(50*a1),93+(int)(41*a1)));
            c.drawCircle(xx,yy,a.thickness*(.8f+.35f*a1),paint);
            // Micro-fibers create a dense, tangled visual instead of sparse glowing dots.
            if(i>0 && i%20!=0) {
                float dx=xx-px[i-1],dy=yy-py[i-1];
                if(dx*dx+dy*dy<2400f)
                    line(c,px[i-1],py[i-1],xx,yy,
                        Color.argb((int)(alpha*.38f),255,77,116),.43f);
            }
            // Additional adjacent fibers (never circular HUD geometry).
            if(i>3 && i%20>3 && i%5==0) {
                int j=i-3;
                float dx=px[i]-px[j], dy=py[i]-py[j];
                if(dx*dx+dy*dy<640f) {
                    line(c,px[j],py[j],xx,yy,Color.argb((int)(alpha*.27f),235,61,96),.40f);
                }
            }
            i++;
        }
        float s=activeBlend;
        center(c,"R E X",cx,552,22,0xFFF4C6CF,true);
        center(c,target>0.5f?"AWAKE  /  VOICE MONITOR":"DORMANT  /  AWAITING WAKE",cx,574,10,
            target>0.5f?0xFFE88A9C:0xFF956875,false);
        if(s>0.1f && energy>.06f) {
            center(c,"SPEECH REACTIVITY  "+Math.round(energy*100)+"%",cx,590,8,0xFFFF8DAD,false);
        }
    }
    private void voicePanel(Canvas c){
        box(c,20,596,340,661,0xA3241825,11);
        text(c,shortText(voiceStatus,40),28,615,10,0xFFF1BAC8,false);
        text(c,shortText(lastText,40),28,636,10,0xFFCFA1B2,false);
        text(c,shortText(commandText,39),28,653,9,0xFFFF8FA7,false);
        box(c,20,668,340,699,0xA74B1F30,11);
        center(c,voiceStarted?"点击重新检测 / 长按关闭监听":"点击开启语音监听",180,688,12,0xFFF8D4DF,true);
        center(c,"左下空白处三击 → 按键模式",180,697,7,0xFF8F6D7B,false);
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
        center(c,"语音监听 · "+(voiceStarted?"已请求启动":"未启动"),180,671,11,0xFFF2C0CD,true);
        center(c,"点击开启 / 长按关闭 / 说 Rex 返回粒子界面",180,687,9,0xFFC593A4,false);
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
            // Left-bottom region unoccupied by actionable controls in both modes.
            if(x<90 && y>702 && y<712){
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
