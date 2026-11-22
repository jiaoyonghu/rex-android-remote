package com.rex.remote;

import android.content.Context;
import java.util.Locale;

/** v1.5: VAD-gated, KWS-free fusion of template similarity and trained MLP.
 * The score is an engineering heuristic, NOT a calibrated probability.
 * No method authorizes IR transmission or robot motion.
 */
public final class NeuralFusion {
    private NeuralFusion() {}
    private static final String PREF="rex_v15_neural_fusion";
    private static final String KEY="config_v1";
    public static final class Config {
        public static final int MODE_CONTINUOUS=1, MODE_ZERO_BELOW=2;
        public final float templateWeight,headWeight,templateThreshold,headThreshold,totalThreshold;
        public final int mode;
        public Config(float tw,float hw,float tg,float hg,float total,int m){
            if(!Float.isFinite(tw)||!Float.isFinite(hw)||!Float.isFinite(tg)||
               !Float.isFinite(hg)||!Float.isFinite(total)||tw<0||tw>1||hw<0||hw>1||
               Math.abs(tw+hw-1f)>.002f||tg<.5f||tg>1f||hg<.5f||hg>1f||
               total<0||total>1||(m!=MODE_CONTINUOUS&&m!=MODE_ZERO_BELOW))
                throw new IllegalArgumentException("Invalid neural fusion settings");
            templateWeight=tw;headWeight=hw;templateThreshold=tg;
            headThreshold=hg;totalThreshold=total;mode=m;
        }
        public String encode(){return String.format(Locale.ROOT,"%.4f,%.4f,%.4f,%.4f,%.4f,%d",
            templateWeight,headWeight,templateThreshold,headThreshold,totalThreshold,mode);}
        public static Config decode(String value){
            if(value==null)return defaults();
            try{
                String[] f=value.split(",",-1);
                if(f.length!=6)return defaults();
                return new Config(Float.parseFloat(f[0]),Float.parseFloat(f[1]),
                    Float.parseFloat(f[2]),Float.parseFloat(f[3]),Float.parseFloat(f[4]),
                    Integer.parseInt(f[5]));
            }catch(IllegalArgumentException e){return defaults();}
        }
    }
    /** Recall-oriented experimental defaults, NOT tested on the user's phone. */
    public static Config defaults(){return new Config(.44f,.56f,.85f,.64f,.30f,Config.MODE_CONTINUOUS);}
    public static Config load(Context ctx){return Config.decode(
        ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,null));}
    public static boolean save(Context ctx,Config cfg){return cfg!=null&&
        ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(KEY,cfg.encode()).commit();}
    public static final class Decision {
        public final RexCommand command;
        public final boolean wake,pass;
        public final float templateScore,headScore,total;
        public final String reason;
        Decision(RexCommand cmd,boolean wake,boolean pass,float t,float h,float total,String why){
            command=cmd;this.wake=wake;this.pass=pass;
            templateScore=t;headScore=h;this.total=total;reason=why;
        }
        public String report(Config c){return String.format(Locale.ROOT,
            "神经融合[%s] 模板 %.3f×%.0f%% + MLP %.3f×%.0f%% = %.3f / 门槛 %.3f；%s",
            c.mode==Config.MODE_CONTINUOUS?"连续加权":"低于归零",
            templateScore,c.templateWeight*100,headScore,c.headWeight*100,
            total,c.totalThreshold,reason);}
    }
    private static Decision reject(String why,float t,float h,float total){
        return new Decision(null,false,false,t,h,total,why);
    }
    /** Caller evaluates one corresponding head if a valid template is found;
     * otherwise it evaluates all trained heads. Neither path needs KWS.
     * A returned head must pass its intrinsic one-vs-rest gate (>=0.64 and,
     * for global search, runner-up margin), even in continuous mode.
     */
    public static Decision evaluate(boolean awake,PersonalNeuralStore.Result template,
                                    PersonalWakeHead.Result head,Config cfg){
        if(cfg==null)cfg=defaults();
        boolean templateValid=template!=null&&template.accepted&&template.phrase>=0&&
            template.phrase<CommandPhrases.count()&&Float.isFinite(template.score)&&
            template.score>=0&&template.score<=1;
        boolean headValid=head!=null&&head.accepted&&head.phrase>=0&&
            head.phrase<CommandPhrases.count()&&Float.isFinite(head.score)&&
            head.score>=0&&head.score<=1;
        if(templateValid&&headValid&&template.phrase!=head.phrase)
            return reject("模板与MLP口令不一致，拒绝融合",0,0,0);
        float t=templateValid&&(cfg.mode==Config.MODE_CONTINUOUS||
            template.score>=cfg.templateThreshold)?template.score:0f;
        float h=headValid&&(cfg.mode==Config.MODE_CONTINUOUS||
            head.score>=cfg.headThreshold)?head.score:0f;
        if(t==0f&&h==0f)return reject("模板与分类头均未通过有效性/门槛检查",0,0,0);
        int phrase=t>0?template.phrase:head.phrase;
        RexCommand cmd=CommandPhrases.command(phrase);
        float total=cfg.templateWeight*t+cfg.headWeight*h;
        if(cmd==null)return reject("口令不在固定指令白名单",t,h,total);
        if(cmd==RexCommand.DISABLE)return reject("失能只允许手动确认",t,h,total);
        if(!awake&&cmd!=RexCommand.WAKE)
            return reject("尚未唤醒，其他口令仅预览拒绝",t,h,total);
        String normalized=RexCommand.norm(CommandPhrases.at(phrase));
        if("退".equals(normalized)||"停".equals(normalized))
            return reject("单字动作有歧义，请说完整指令",t,h,total);
        if(total+1e-6f<cfg.totalThreshold)
            return reject("综合评分未达到门槛",t,h,total);
        return new Decision(cmd==RexCommand.WAKE?null:cmd,cmd==RexCommand.WAKE,true,t,h,
            total,t>0&&h>0?"模板和MLP对同一口令一致":t>0?
            "仅模板有效，允许兜底":"仅已训练MLP有效，允许兜底");
    }
}
