package com.rex.remote;

import java.util.ArrayList;
import java.util.List;

/** One utterance / one decision. No async ASR/template overwrite or fuzzy command guess. */
public final class SpeechFusion {
    private SpeechFusion() {}
    public static final class Decision {
        public final RexCommand command;
        public final boolean woke,conflict;
        public final String origin,reason;
        Decision(RexCommand command,boolean woke,boolean conflict,String origin,String reason) {
            this.command=command;this.woke=woke;this.conflict=conflict;
            this.origin=origin;this.reason=reason;
        }
    }
    private static final class Candidate {
        final RexCommand command;final boolean woke;final String source;
        Candidate(RexCommand c,boolean woke,String source){command=c;this.woke=woke;this.source=source;}
        RexCommand effective(){return command==null && woke?RexCommand.WAKE:command;}
    }
    public static Decision decide(String zh,String en,String grammarZh,String grammarEn,
                                  CommandTemplateStore.Result personal,boolean awake) {
        List<Candidate> all=new ArrayList<>();
        collect(all,zh,awake,"通用中文");
        collect(all,en,awake,"通用英文");
        collect(all,grammarZh,awake,"固定词表中文");
        collect(all,grammarEn,awake,"固定词表英文");
        if(personal!=null && personal.accepted()) {
            RexCommand c=CommandPhrases.command(personal.index);
            if(c!=null && (awake || c==RexCommand.WAKE))
                all.add(new Candidate(c,c==RexCommand.WAKE,"个性化模板"));
        }
        if(all.isEmpty()) return new Decision(null,false,false,"未命中", "未识别到允许的完整口令");
        Candidate chosen=null;
        StringBuilder sources=new StringBuilder();
        for(Candidate c:all) {
            // "rex + action" and a separate wake-only result are compatible.
            if(chosen!=null && c.effective()!=chosen.effective()) {
                boolean wakeActionCompatible=chosen.effective()==RexCommand.WAKE
                    || c.effective()==RexCommand.WAKE; // wake-only is not a motion conflict
                if(!wakeActionCompatible)return new Decision(null,false,true,"冲突拒绝",
                    chosen.source+"识别为「"+label(chosen.effective())+"」，但"+c.source+
                    "识别为「"+label(c.effective())+"」；不猜测");
                if(chosen.effective()==RexCommand.WAKE)chosen=c;
            }
            if(chosen==null)chosen=c;
            if(sources.length()>0)sources.append(" + ");
            sources.append(c.source);
        }
        // Wake with action is allowed by the original whitelist router, but not
        // from a lone motion word while asleep. No new synonyms are permitted.
        return new Decision(chosen.effective()==RexCommand.WAKE?null:chosen.effective(),
            chosen.woke || chosen.effective()==RexCommand.WAKE,false,sources.toString(),
            "一致通过："+label(chosen.effective()));
    }
    private static String label(RexCommand c){return c==null?"未知":c.label;}
    private static void collect(List<Candidate> list,String text,boolean awake,String source) {
        if(text==null||text.trim().isEmpty())return;
        // [unk] is not a command. The original router enforces exact words and wake rules.
        BilingualCommandRouter.Match match=BilingualCommandRouter.resolve(text,"",awake);
        if(match.woke || match.command!=null)list.add(new Candidate(match.command,match.woke,source));
    }
}
