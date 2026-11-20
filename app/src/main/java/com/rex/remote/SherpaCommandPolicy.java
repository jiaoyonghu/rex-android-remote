package com.rex.remote;

/**
 * Command-only offline KWS decision. Keyword recognition is not speaker
 * authentication and never sends infrared. A template can veto a strongly
 * different action but cannot create a new command from arbitrary sound.
 */
public final class SherpaCommandPolicy {
    private SherpaCommandPolicy() {}
    public static final class Decision {
        public final RexCommand command;
        public final boolean wake;
        public final String reason;
        Decision(RexCommand c, boolean w, String reason) {
            command=c; wake=w; this.reason=reason;
        }
    }
    public static Decision decide(String keyword, boolean awake,
                                  CommandTemplateStore.Result personal,
                                  boolean multipleKeywords) {
        if (multipleKeywords) return new Decision(null,false,"同一语音段识别到冲突口令，已拒绝");
        String wakeSuffix=RexCommand.stripWakePrefix(keyword);
        boolean combined=wakeSuffix!=null && !wakeSuffix.isEmpty();
        RexCommand candidate=combined?RexCommand.parse(wakeSuffix):RexCommand.parse(keyword);
        if (candidate==null) return new Decision(null,false,"神经关键词模型未命中合法口令");
        if (candidate==RexCommand.WAKE) {
            return new Decision(null,true,"独立关键词模型识别唤醒口令");
        }
        if (!awake && !combined) return new Decision(null,false,"未唤醒，不执行动作");
        // Extremely short words are often embedded in non-command conversation.
        // Do not permit one syllable to create a command by itself.
        String norm=RexCommand.norm(keyword);
        if ("退".equals(norm)||"停".equals(norm))
            return new Decision(null,false,"单字指令需改说『后退』或『好，停』");
        // A non-matching accepted personal template for a DIFFERENT action is
        // evidence of acoustic ambiguity. Fail closed; never guess.
        if (personal!=null && personal.accepted()) {
            RexCommand other=CommandPhrases.command(personal.index);
            if (other!=candidate && other!=null)
                return new Decision(null,false,"个性化样本与关键词指向不同动作，拒绝猜测");
        }
        // Explicit disable requires a manual control path, not KWS alone.
        if (candidate==RexCommand.DISABLE)
            return new Decision(null,false,"失能类高风险口令仅显示候选，使用手动控制");
        return new Decision(candidate,combined,"流式关键词确认"+
            (personal!=null && personal.accepted()?" + 个人声学模板一致":""));
    }
}
