package com.rex.remote;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

/** REx 1.3: transparent, tunable evidence fusion, not a probability calibration.
 * KWS Java API exports only hit/no-hit; 1 and 0 are BINARY EVIDENCE,
 * NOT acoustic confidence. Personal score is cosine similarity of embeddings.
 * VAD gating is performed by the caller before this function is invoked.
 * All results remain PREVIEW ONLY, never voice-to-IR or motor commands.
 */
public final class CommandFusion {
    private CommandFusion() { }
    private static final String PREF="rex_fusion_v13_private";
    private static final String KEY="saved_config_v1";

    public static final class Config {
        /** MODE_CONTINUOUS weights valid personal scores even below the UI gate;
         * MODE_ZERO_BELOW keeps the original v1.3 truncation behavior. */
        public static final int MODE_CONTINUOUS=1, MODE_ZERO_BELOW=2;
        public final float kwsWeight, neuralWeight, neuralThreshold, totalThreshold;
        public final int neuralMode;
        public Config(float kws, float neural, float neuralGate, float totalGate) {
            this(kws,neural,neuralGate,totalGate,MODE_ZERO_BELOW);
        }
        public Config(float kws, float neural, float neuralGate, float totalGate,
                      int mode) {
            if(!Float.isFinite(kws)||!Float.isFinite(neural)||
               !Float.isFinite(neuralGate)||!Float.isFinite(totalGate)||
               kws<0||kws>1||neural<0||neural>1||
               Math.abs(kws+neural-1f)>.002f||
               neuralGate<.5f||neuralGate>1f||totalGate<0f||totalGate>1f||
               (mode!=MODE_CONTINUOUS && mode!=MODE_ZERO_BELOW))
                throw new IllegalArgumentException("Invalid fusion settings");
            kwsWeight=kws;neuralWeight=neural;
            neuralThreshold=neuralGate;totalThreshold=totalGate;neuralMode=mode;
        }
        public String encode(){return String.format(Locale.ROOT,"%.4f,%.4f,%.4f,%.4f,%d",
            kwsWeight,neuralWeight,neuralThreshold,totalThreshold,neuralMode);}
        public static Config decode(String raw){
            if(raw==null)return defaults();
            try{
                String[] s=raw.split(",",-1);
                if(s.length!=4 && s.length!=5)return defaults();
                // Saved v1.3 profiles used four fields; preserve exact old behavior.
                int mode=s.length==5?Integer.parseInt(s[4]):Config.MODE_ZERO_BELOW;
                return new Config(Float.parseFloat(s[0]),Float.parseFloat(s[1]),
                        Float.parseFloat(s[2]),Float.parseFloat(s[3]),mode);
            }catch(IllegalArgumentException ex){return defaults();}
        }
    }
    /** KWS-only still works; personal-only strong matches can pass as well.
     * Threshold and weights are tunable; defaults have NOT been field-calibrated. */
    public static Config defaults(){return new Config(.44f,.56f,.92f,.42f);}
    public static Config load(Context ctx){
        return Config.decode(ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY,null));
    }
    public static boolean save(Context ctx,Config config){
        if(config==null)return false;
        return ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
            .putString(KEY,config.encode()).commit();
    }
    public static final class Decision {
        public final RexCommand command;
        public final boolean wake,pass;
        public final float kwsEvidence,neuralEvidence,combined;
        public final String reason;
        Decision(RexCommand command,boolean wake,boolean pass,float kws,float neural,
                 float total,String reason){
            this.command=command;this.wake=wake;this.pass=pass;
            kwsEvidence=kws;neuralEvidence=neural;combined=total;this.reason=reason;
        }
        public String report(Config cfg){
            return String.format(Locale.ROOT,
                "融合[%s] KWS(0/1)=%.0f × %.0f%% + 个人=%.3f × %.0f%% = %.3f / 门槛 %.3f；%s",
                cfg.neuralMode==Config.MODE_CONTINUOUS?"连续加权":"低于归零",
                kwsEvidence,cfg.kwsWeight*100,neuralEvidence,cfg.neuralWeight*100,
                combined,cfg.totalThreshold,reason);
        }
    }
    private static Decision denied(String why,float k,float n,float total){
        return new Decision(null,false,false,k,n,total,why);
    }
    public static Decision evaluate(String rawKeyword,boolean awake,boolean conflicting,
                                    PersonalNeuralStore.Result match,Config config){
        if(config==null)config=defaults();
        if(conflicting)return denied("KWS 同段出现互相冲突口令",0,0,0);
        SherpaCommandPolicy.Decision k=SherpaCommandPolicy.decide(rawKeyword,awake,null,false);
        RexCommand kwsCandidate=k.command!=null?k.command:
            (k.wake?RexCommand.WAKE:null);
        // MODE_CONTINUOUS bypasses ONLY the UI cutoff (e.g. 0.92).
        // It never bypasses the personal model's own sample-quality, competitor
        // margin or ambiguity gate encoded in match.accepted.
        boolean personalValid=match!=null && match.accepted &&
            match.phrase>=0 && Float.isFinite(match.score) &&
            match.score>=0f && match.score<=1f;
        boolean neuralStrong=personalValid &&
            (config.neuralMode==Config.MODE_CONTINUOUS ||
             match.score>=config.neuralThreshold);
        RexCommand neuralCandidate=neuralStrong?CommandPhrases.command(match.phrase):null;
        float n=neuralCandidate==null?0f:match.score;
        float kw=kwsCandidate==null?0f:1f;
        if(kwsCandidate==null && neuralCandidate==null)
            return denied(config.neuralMode==Config.MODE_CONTINUOUS?
                "KWS 未命中且个人模型未通过内部质量/歧义检查":
                "KWS 未命中且个人模型未过门槛",0,0,0);
        if(kwsCandidate!=null && neuralCandidate!=null && kwsCandidate!=neuralCandidate)
            return denied("KWS 与个人模型指向不同指令，拒绝加权",kw,n,0);
        RexCommand winner=kwsCandidate!=null?kwsCandidate:neuralCandidate;
        float total=config.kwsWeight*kw+config.neuralWeight*n;
        if(winner==RexCommand.DISABLE)
            return denied("失能口令只显示候选，必须使用手动控制",kw,n,total);
        if(!awake && winner!=RexCommand.WAKE && !k.wake)
            return denied("尚未唤醒，动作口令不生效",kw,n,total);
        if(winner!=RexCommand.WAKE && winner!=RexCommand.SLEEP &&
           ("退".equals(RexCommand.norm(rawKeyword)) ||
            "停".equals(RexCommand.norm(rawKeyword)) ||
            (kw==0 && match!=null &&
             ("退".equals(CommandPhrases.at(match.phrase)) ||
              "停".equals(CommandPhrases.at(match.phrase))))))
            return denied("单字口令容易误触发，请说『后退』『好，停』",kw,n,total);
        if(total+1e-6f<config.totalThreshold)
            return denied("综合评分未达到设定门槛",kw,n,total);
        return new Decision(winner==RexCommand.WAKE?null:winner,
            winner==RexCommand.WAKE || (k.wake && winner==k.command),true,kw,n,total,
            kw>0 && n>0?"两路对同一动作一致":kw>0?"仅 Zipformer 命中":
            "仅个人神经强匹配（非声纹身份认证）");
    }
}
