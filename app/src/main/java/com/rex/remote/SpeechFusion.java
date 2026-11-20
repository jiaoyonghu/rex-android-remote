package com.rex.remote;

import java.util.ArrayList;
import java.util.List;

/**
 * v0.9: fail-closed spoken-command arbitration.
 *
 * A constrained grammar and an MFCC template are *hypothesis generators*, not
 * proof that the user spoke a command. Never accept either by itself. Require
 * two agreeing paths, reject competing commands and meaningful unrestricted
 * speech that contradicts a grammar guess. This policy is deliberately tuned
 * for fewer false actions, not maximum recall.
 */
public final class SpeechFusion {
    private SpeechFusion() {}

    public static final class Decision {
        public final RexCommand command;
        public final boolean woke, conflict;
        public final String origin, reason;
        Decision(RexCommand command, boolean woke, boolean conflict,
                 String origin, String reason) {
            this.command=command;this.woke=woke;this.conflict=conflict;
            this.origin=origin;this.reason=reason;
        }
    }

    private static final class Candidate {
        final RexCommand command;
        final boolean wakePrefix;
        final String source;
        Candidate(RexCommand command,boolean wakePrefix,String source) {
            this.command=command;this.wakePrefix=wakePrefix;this.source=source;
        }
        RexCommand key(){return command==null?RexCommand.WAKE:command;}
    }

    private static Candidate parse(String text,boolean awake,String source){
        if(text==null || text.trim().isEmpty() || text.contains("[unk]"))return null;
        BilingualCommandRouter.Match m=BilingualCommandRouter.resolve(text,"",awake);
        if(!m.woke && m.command==null)return null;
        return new Candidate(m.command,m.woke,source);
    }
    private static boolean isUnknownSpeech(String text,Candidate parsed){
        if(parsed!=null || text==null)return false;
        String norm=RexCommand.norm(text);
        return !norm.isEmpty() && !norm.equals("unk") && !norm.contains("[unk]");
    }
    private static Decision reject(String reason,boolean conflict){
        return new Decision(null,false,conflict,"安全拒绝",reason);
    }
    /** Skip MFCC/DTW for arbitrary clips with no ASR command hypothesis. */
    public static boolean hasAsrHypothesis(String zh,String en,String grammarZh,
                                          String grammarEn,boolean awake){
        return parse(zh,awake,"通用中文")!=null
            || parse(en,awake,"通用英文")!=null
            || parse(grammarZh,awake,"固定词表中文")!=null
            || parse(grammarEn,awake,"固定词表英文")!=null;
    }
    public static Decision decide(String zh,String en,String grammarZh,String grammarEn,
                                  CommandTemplateStore.Result personal,boolean awake){
        Candidate fullZh=parse(zh,awake,"通用中文");
        Candidate fullEn=parse(en,awake,"通用英文");
        Candidate limitedZh=parse(grammarZh,awake,"固定词表中文");
        Candidate limitedEn=parse(grammarEn,awake,"固定词表英文");
        List<Candidate> asr=new ArrayList<>();
        if(fullZh!=null)asr.add(fullZh);
        if(fullEn!=null)asr.add(fullEn);
        if(limitedZh!=null)asr.add(limitedZh);
        if(limitedEn!=null)asr.add(limitedEn);
        // An explicit wake prefix in the unrestricted ASR is enough to make an
        // accompanying action template eligible during the same utterance.
        boolean heardWake=(fullZh!=null && fullZh.wakePrefix)
            || (fullEn!=null && fullEn.wakePrefix);
        Candidate template=null;
        if(personal!=null && personal.accepted()){
            RexCommand c=CommandPhrases.command(personal.index);
            if(c!=null && (awake || heardWake || c==RexCommand.WAKE))
                template=new Candidate(c,c==RexCommand.WAKE,"个性化模板");
        }
        if(asr.isEmpty() && template==null)
            return reject("没有可靠口令；普通声音不参与模板猜测",false);

        // A wake-only output in a second path is harmless when the other path
        // knows an action; it is not evidence that the action was correctly heard.
        RexCommand target=null;
        for(Candidate c:asr){
            if(c.key()!=RexCommand.WAKE){target=c.key();break;}
        }
        if(target==null && template!=null && template.key()!=RexCommand.WAKE)
            target=template.key();
        if(target==null)target=RexCommand.WAKE;
        for(Candidate c:asr){
            if(c.key()!=RexCommand.WAKE && c.key()!=target)
                return reject("识别器给出不同动作，拒绝猜测",true);
        }
        if(template!=null && template.key()!=RexCommand.WAKE && template.key()!=target)
            return reject("个性化模板与语音模型的动作不同，拒绝猜测",true);

        boolean baseZh=fullZh!=null && fullZh.key()==target;
        boolean baseEn=fullEn!=null && fullEn.key()==target;
        boolean grammarMatchZh=limitedZh!=null && limitedZh.key()==target;
        boolean grammarMatchEn=limitedEn!=null && limitedEn.key()==target;
        boolean templateMatch=template!=null && template.key()==target;
        boolean unrestricted=baseZh||baseEn;
        boolean grammar=grammarMatchZh||grammarMatchEn;
        int votes=(baseZh?1:0)+(baseEn?1:0)
            +(grammarMatchZh?1:0)+(grammarMatchEn?1:0)+(templateMatch?1:0);

        if(votes<2)return reject("只有一路匹配：模板或固定词表不能单独下结论",false);
        // If full-vocabulary Chinese heard an unrelated sentence, the matching
        // Chinese grammar hypothesis is likely a forced match, NOT an action.
        // Equivalently for English. Do not let the template override that veto.
        if(!unrestricted){
            if((grammarMatchZh && isUnknownSpeech(zh,fullZh)) ||
               (grammarMatchEn && isUnknownSpeech(en,fullEn)))
                return reject("通用模型听到非口令句子，拒绝固定词表强制匹配",false);
            if(!grammar || !templateMatch)
                return reject("通用模型未确认；需要固定词表与个人模板同时一致",false);
            // The two fallback paths can both over-fit unfamiliar sounds.
            // Only rescue blank unrestricted ASR on a VERY close personal
            // match, well separated from any other trained action.
            boolean strongTemplate=personal!=null &&
                personal.distance<=Math.min(.42f,personal.limit*.63f) &&
                (!Float.isFinite(personal.competitor) ||
                    personal.competitor-personal.distance>=.15f);
            if(!strongTemplate)
                return reject("通用模型未确认，模板分数不足以安全补救",false);
        }

        // Extremely short words have a high accidental-match rate. Require
        // three-way evidence for a *short spoken alias*; exact longer synonyms
        // (e.g. 后退, 好停) may follow the regular two-evidence rule.
        boolean oneSyllable=("退".equals(RexCommand.norm(zh)) ||
            "停".equals(RexCommand.norm(zh)) ||
            (fullZh==null && ("退".equals(RexCommand.norm(grammarZh)) ||
                                "停".equals(RexCommand.norm(grammarZh)))));
        if(oneSyllable && (target==RexCommand.BACKWARD || target==RexCommand.STOP)
             && !(unrestricted && grammar && templateMatch))
            return reject("单字口令必须通用识别、固定词表和个人模板三方一致",false);

        StringBuilder origins=new StringBuilder();
        if(baseZh)origins.append("通用中文");
        if(baseEn){if(origins.length()>0)origins.append(" + ");origins.append("通用英文");}
        if(grammarMatchZh){if(origins.length()>0)origins.append(" + ");origins.append("词表中文");}
        if(grammarMatchEn){if(origins.length()>0)origins.append(" + ");origins.append("词表英文");}
        if(templateMatch){if(origins.length()>0)origins.append(" + ");origins.append("个人模板");}
        boolean wake=target==RexCommand.WAKE || heardWake;
        return new Decision(target==RexCommand.WAKE?null:target,wake,false,
            origins.toString(),"通过多路确认（"+votes+" 路一致）");
    }
}
