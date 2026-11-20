package com.rex.remote;

/**
 * Bounded personalized wake recovery when neural KWS missed a word.
 * Long phrases still require new speaker embedding verification.
 * For short wake words, require two independent strong template matches
 * before opening ONLY the preview UI (never an authenticated session).
 */
public final class PersonalWakePolicy {
    private static final long SHORT_CONFIRM_MS=3500L;
    private int pendingIndex=-1;
    private long pendingAt;
    public static final class Result {
        public final String phrase;
        public final boolean twoShort,awaitSecond;
        Result(String phrase,boolean twoShort){this(phrase,twoShort,false);}
        Result(String phrase,boolean twoShort,boolean awaitSecond){this.phrase=phrase;this.twoShort=twoShort;this.awaitSecond=awaitSecond;}
        public boolean found(){return phrase!=null&&!phrase.isEmpty();}
    }
    public synchronized void clear(){pendingAt=0;pendingIndex=-1;}
    public synchronized Result evaluate(CommandTemplateStore.Result match,int takeCount,
                           int samples,long now){
        if(match==null || !match.accepted() || match.index<0 ||
            CommandPhrases.command(match.index)!=RexCommand.WAKE){
            clear();return new Result("",false);
        }
        if(match.limit<=0 || match.distance>=match.limit*.79f ||
           (match.competitorIndex>=0 &&
            match.competitor-match.distance<.16f)){
            clear();return new Result("",false);
        }
        if(samples>=16000){ // ~1 second: checked by independent CAM++ speaker
            clear();return new Result(CommandPhrases.at(match.index),false);
        }
        if(samples<3200 || takeCount<4 ||
           match.distance>=match.limit*.58f ||
           (match.competitorIndex>=0 &&
            match.competitor-match.distance<.22f)){
            clear();return new Result("",false);
        }
        if(pendingIndex==match.index && now>=pendingAt &&
           now-pendingAt<=SHORT_CONFIRM_MS){
            clear();return new Result(CommandPhrases.at(match.index),true);
        }
        pendingIndex=match.index;pendingAt=now;
        return new Result("",false,true);
    }
}
