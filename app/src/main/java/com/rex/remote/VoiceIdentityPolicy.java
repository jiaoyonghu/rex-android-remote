package com.rex.remote;

/**
 * Preview-only speaker session policy. It does NOT provide biometric security,
 * anti-replay, or authority to issue motion commands. A short (unverifiable)
 * syllable must never be described as independently speaker-verified.
 */
public final class VoiceIdentityPolicy {
    private VoiceIdentityPolicy() {}
    public static final long SESSION_MS=120_000L;
    public enum Speaker { NO_PROFILE, MATCH, MISMATCH, TOO_SHORT }
    public static final class Decision {
        public final RexCommand command;
        public final boolean wake, refreshSession;
        public final String explanation;
        Decision(RexCommand command,boolean wake,boolean refresh,String explanation){
            this.command=command;this.wake=wake;this.refreshSession=refresh;
            this.explanation=explanation;
        }
    }
    public static Decision decide(SherpaCommandPolicy.Decision candidate,
                                  Speaker speaker,boolean sessionActive,
                                  boolean personalWakeRescue){
        if(candidate.command==null && !candidate.wake)
            return new Decision(null,false,false,candidate.reason);
        if(candidate.command==RexCommand.SLEEP)
            return new Decision(candidate.command,false,false,"退出唤醒并清除会话（UI操作）");
        if(personalWakeRescue && speaker!=Speaker.MATCH)
            return new Decision(null,false,false,"个人唤醒模板必须同时通过长语音声纹复核");
        if(speaker==Speaker.MISMATCH)
            return new Decision(null,false,false,"检测到与录入者不同的声纹，拒绝本句");
        if(speaker==Speaker.NO_PROFILE)
            return new Decision(candidate.command,candidate.wake,false,
                "未建立新版声纹档案：仅显示候选，不提供本人认证");
        if(candidate.wake) {
            // Single-syllable wake may toggle the UI, but cannot open a verified
            // speaker session by itself. The owner can say a longer wake phrase.
            return new Decision(null,true,speaker==Speaker.MATCH,
                speaker==Speaker.MATCH?"较长唤醒词与本人声纹相符":
                "短唤醒已识别，但声纹未确认；请说『Rex醒了吗』完成确认");
        }
        if(speaker==Speaker.MATCH)
            return new Decision(candidate.command,false,true,"本句声纹相符");
        if(sessionActive)
            return new Decision(candidate.command,false,false,"复用近期已核对的本人声纹会话");
        return new Decision(null,false,false,"未确认本人声纹；请先说较长唤醒词『Rex醒了吗』");
    }
}
