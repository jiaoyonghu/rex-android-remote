package com.rex.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Random;

/** Dark sci-fi assistant, particle orb, and manual buttons. Pure Android Canvas. */
public final class RexView extends View {
    public interface Actions {
        void onSend(RexCommand command);
        void onStartVoice();
        void onStopVoice();
    }
    private final Actions actions;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<TouchButton> buttons = new ArrayList<>();
    private boolean manual;
    private String message = "红外联调版 · 尚未连接机器人";
    private String irStatus = "检测中";
    private String recognized = "说“Rex”唤醒粒子界面（语音只预览）";
    private float voiceLevel = 0f;
    private long voiceFlash;
    private long lastTapMs;
    private long touchDownMs;
    private int tapCount;
    private float touchX, touchY;
    private long startTime = android.os.SystemClock.elapsedRealtime();

    private static final class Particle {
        final float theta, phi, size, phase;
        Particle(Random r) {
            theta = (float) (r.nextDouble() * Math.PI * 2);
            phi = (float) Math.acos(2 * r.nextDouble() - 1);
            size = 0.7f + r.nextFloat() * 1.4f;
            phase = r.nextFloat() * 10;
        }
    }
    private static final class TouchButton {
        final RectF region;
        final String title, subtitle;
        final RexCommand command;
        TouchButton(float left,float top,float right,float bottom,String title,String subtitle,RexCommand cmd) {
            region = new RectF(left,top,right,bottom);
            this.title=title; this.subtitle=subtitle; command=cmd;
        }
    }

    public RexView(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        Random r = new Random(40311);
        for (int i = 0; i < 610; ++i) particles.add(new Particle(r));
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }
    public void setIrStatus(String s) { irStatus=s; invalidate(); }
    public void setMessage(String s) { message=s; invalidate(); }
    public void onVoice(String text,String command,boolean wake,float level) {
        if (wake) { manual=false; voiceFlash=android.os.SystemClock.elapsedRealtime(); }
        if (text != null) recognized = text + (command == null ? "" : "  ·  "+command+"（声纹未验证，未发射）");
        voiceLevel = Math.max(voiceLevel,level);
        invalidate();
    }
    private void color(int color) { p.setColor(color);p.setShader(null);p.setStyle(Paint.Style.FILL); }
    private void text(Canvas c,String value,float x,float y,float size,int color) {
        color(color);p.setTextSize(size);p.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        c.drawText(value,x,y,p);
    }
    private void centered(Canvas c,String value,float x,float y,float size,int color) {
        color(color);p.setTextSize(size);
        c.drawText(value, x-p.measureText(value)/2f, y,p);
    }
    private void panel(Canvas c,float l,float t,float r,float b,int color) {
        color(color);c.drawRoundRect(l,t,r,b,14f,14f,p);
    }
    private void drawHeader(Canvas c) {
        color(0xFF050B18);c.drawColor(0xFF050B18);
        p.setShader(new LinearGradient(0,20,0,780,0xFF0C203F,0xFF050A16,Shader.TileMode.CLAMP));
        c.drawRect(0,0,360,780,p);p.setShader(null);
        text(c,"R E X",25,50,24,0xFFDDF8FF);
        text(c,manual ? "MANUAL CONTROL" : "NEURAL VOICE CORE",24,70,10,0xFF65C9EC);
        text(c,"ONEPLUS ACE 3V  ·  IR 38KHz",24,91,9,0xFF6791AE);
        panel(c,18,105,342,145,0xAA102B41);
        text(c,abbreviate(irStatus,41),27,130,11,0xFF99D8F1);
    }
    private static String abbreviate(String s,int n) { return s.length()>n?s.substring(0,n-1)+"…":s; }
    private void orb(Canvas c,float time) {
        float cx=180, cy=344;
        float energy = Math.max(0f, Math.min(1f,voiceLevel));
        float pulse = 1f + energy*.22f + .035f * (float)Math.sin(time*1.4);
        // Neon rings
        for (int i=0;i<4;i++) {
            color(0x3019BDFE + (i<<24)); p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(.9f);
            float r=(110+i*12)*pulse;
            c.drawCircle(cx,cy,r,p);
            p.setStyle(Paint.Style.FILL);
        }
        for (Particle a : particles) {
            float th=a.theta + time*(.18f + .04f*(float)Math.sin(a.phase));
            float x=(float)(Math.sin(a.phi)*Math.cos(th));
            float y=(float)(Math.cos(a.phi));
            float z=(float)(Math.sin(a.phi)*Math.sin(th));
            float d= 1f/(1.25f-z*.35f);
            float wave = (float)Math.sin(time*2.4f + a.phase * 1.5f + a.phi*6);
            float radius=94* (1+ .08f*wave + energy*.30f) * pulse;
            float xx=cx+x*radius*d;
            float yy=cy+y*radius*d*.90f;
            int alpha=(int)(65+140*(z+1f)/2f);
            color(Color.argb(alpha,75+(int)(50*z),183+(int)(45*z),255));
            c.drawCircle(xx,yy,a.size*d*(1+energy*.3f),p);
        }
        color(0x4053D8FF);c.drawCircle(cx,cy,24+energy*12,p);
        color(0xFFBEF9FF);c.drawCircle(cx,cy,5+energy*2,p);
        centered(c,"R E X",cx,cy+154,21,0xFFCEEFFF);
        centered(c,"声纹验证尚未启用 · 语音只用于演示",cx,cy+180,10,0xFF79C0DF);
    }
    private void add(float l,float t,float r,float b,String title,String sub,RexCommand cmd) {
        buttons.add(new TouchButton(l,t,r,b,title,sub,cmd));
    }
    private void controls(Canvas c) {
        buttons.clear();
        add(124,174,236,230,"↑ 前进","0.6 m/s",RexCommand.FORWARD);
        add(20,240,121,302,"← 左移","0.4 m/s",RexCommand.LEFT);
        add(125,240,235,302,"■ 停止","保持站立",RexCommand.STOP);
        add(239,240,340,302,"右移 →","0.4 m/s",RexCommand.RIGHT);
        add(124,312,236,369,"↓ 后退","0.4 m/s",RexCommand.BACKWARD);
        add(20,391,173,448,"↶ 左转","0.4 rad/s",RexCommand.TURN_LEFT);
        add(187,391,340,448,"右转 ↷","0.4 rad/s",RexCommand.TURN_RIGHT);
        add(20,463,173,516,"站立","STAND",RexCommand.STAND);
        add(187,463,340,516,"闪身步","随机左右 · 3s",RexCommand.DODGE);
        add(20,530,173,582,"转个圈","随机旋转",RexCommand.TURN_AROUND);
        add(187,530,340,582,"激活","ACTIVE",RexCommand.ACTIVE);
        add(20,596,340,649,"失能 · 高风险","需要二次确认",RexCommand.DISABLE);
        for (TouchButton b : buttons) {
            panel(c,b.region.left,b.region.top,b.region.right,b.region.bottom,
                b.command==RexCommand.DISABLE?0x99432236:0x9A133C5B);
            centered(c,b.title,b.region.centerX(),b.region.top+24,14,0xFFD4F2FF);
            centered(c,b.subtitle,b.region.centerX(),b.region.top+42,9,0xFF89BDD8);
        }
        centered(c,"左下角空白处连续三击 → 返回粒子界面",180,688,10,0xFF82A3BD);
    }
    @Override protected void onDraw(Canvas original) {
        super.onDraw(original);
        Canvas c=original;
        int save=c.save();
        c.scale(getWidth()/360f,getHeight()/780f);
        float t=(android.os.SystemClock.elapsedRealtime()-startTime)/1000f;
        drawHeader(c);
        if (manual) controls(c);
        else {
            orb(c,t);
            panel(c,23,563,337,610,0xAA0C2942);
            text(c,abbreviate(recognized,27),31,583,10,0xFFB5E6FA);
            text(c,"识别仅显示，不发送红外",31,601,9,0xFF76AAC7);
            panel(c,23,623,337,670,0xAA104263);
            centered(c,"点此启用后台语音预览",180,651,13,0xFFC1F5FF);
            centered(c,"左下角连续三击：按键模式",180,693,11,0xFF85BAD2);
        }
        panel(c,16,720,344,765,0xBB0A253C);
        text(c,abbreviate(message,32),23,740,11,0xFFAFDCEB);
        text(c,"IR 单向 · 无机器人确认 · 声纹未验证",23,757,9,0xFF7198AF);
        c.restoreToCount(save);
        voiceLevel *= .88f;
        postInvalidateDelayed(40);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        float x=event.getX()*360f/getWidth();
        float y=event.getY()*780f/getHeight();
        if (event.getAction()==MotionEvent.ACTION_DOWN) {
            touchX=x;touchY=y;touchDownMs=android.os.SystemClock.elapsedRealtime();return true;
        }
        if (event.getAction()==MotionEvent.ACTION_UP) {
            // Hidden triple tap: lower left empty area, not a manual control button.
            if (x<105 && y>672 && y<718) {
                long now=android.os.SystemClock.elapsedRealtime();
                tapCount=now-lastTapMs<650?tapCount+1:1;
                lastTapMs=now;
                if (tapCount>=3) {manual=!manual;tapCount=0;invalidate();}
                return true;
            }
            if (!manual && x>23 && x<337 && y>623 && y<670) {
                if (android.os.SystemClock.elapsedRealtime()-touchDownMs > 1200) {actions.onStopVoice(); setMessage("语音预览已停止");}
                else actions.onStartVoice();return true;
            }
            if (manual) {
                for (TouchButton b : buttons) if (b.region.contains(x,y)) {
                    actions.onSend(b.command);return true;
                }
            }
            return true;
        }
        return true;
    }
}
