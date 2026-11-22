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
    /** Routing uses the USER's saved template threshold, never the old
     * template's hidden dynamic threshold or runner-up margin.
     * This selects a cheap one-head fast path; it is NOT a final pass verdict.
     */
    public static boolean useTemplateFastPath(PersonalNeuralStore.Result template,Config cfg){
        if(cfg==null)cfg=defaults();
        return validTemplate(template)&&template.score>=cfg.templateThreshold;
    }
    /** Align evidence to the final trained-head phrase. A different best
     * template must NEVER be blended with the head's score. In the absence
     * of any head, keep the old template-only fallback only on a user-gate hit.
     */
    public static PersonalNeuralStore.Result templateForHead(
            PersonalNeuralStore store,float[] embedding,PersonalNeuralStore.Result top,
            PersonalWakeHead.Result head,boolean fastPath){
        if(validHead(head)){
            if(validTemplate(top)&&top.phrase==head.phrase)return top;
            return store==null?null:store.evaluatePhrase(embedding,head.phrase);
        }
        return fastPath&&validTemplate(top)?top:null;
    }
    private static boolean validTemplate(PersonalNeuralStore.Result t){
        return t!=null&&t.phrase>=0&&t.phrase<CommandPhrases.count()&&
            Float.isFinite(t.score)&&t.score>=0f&&t.score<=1f;
    }
    private static boolean validHead(PersonalWakeHead.Result h){
        return h!=null&&h.phrase>=0&&h.phrase<CommandPhrases.count()&&
            Float.isFinite(h.score)&&h.score>=0f&&h.score<=1f;
    }
    /** Both scores must describe the SAME candidate phrase. VoiceService
     * routes one head on a template hit; otherwise searches all trained heads
     * and recomputes the raw template score for the winning head's phrase.
     * Old hidden 'accepted' gates are diagnostic only. Mode 1 weights raw
     * evidence, mode 2 zeros evidence below the USER's corresponding gate.
     * Final acceptance is determined by the composite total threshold and
     * existing whitelist / awake / manual-only safety restrictions.
     */
    public static Decision evaluate(boolean awake,PersonalNeuralStore.Result template,
                                    PersonalWakeHead.Result head,Config cfg){
        if(cfg==null)cfg=defaults();
        boolean templatePresent=validTemplate(template);
        boolean headPresent=validHead(head);
        if(templatePresent&&headPresent&&template.phrase!=head.phrase)
            return reject("模板与MLP不是同一指令，拒绝交叉加权",0f,0f,0f);
        if(!templatePresent&&!headPresent)
            return reject("未找到有分数的模板或已训练分类头",0f,0f,0f);
        float t=templatePresent&&(cfg.mode==Config.MODE_CONTINUOUS||
            template.score>=cfg.templateThreshold)?template.score:0f;
        float h=headPresent&&(cfg.mode==Config.MODE_CONTINUOUS||
            head.score>=cfg.headThreshold)?head.score:0f;
        if(t==0f&&h==0f)
            return reject("两路分数经用户门槛处理后均为零",0f,0f,0f);
        int phrase=headPresent?head.phrase:template.phrase;
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
            total,t>0&&h>0?"同口令模板+MLP加权通过":t>0?
            "模板兜底通过（该口令暂无分类头或分类头权重为零）":
            "MLP搜索/候选评分通过");
    }
}
