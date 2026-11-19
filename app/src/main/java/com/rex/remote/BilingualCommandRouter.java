package com.rex.remote;

/** Strict same-audio CN+EN fusion. Neither model can introduce extra aliases. */
public final class BilingualCommandRouter {
    private BilingualCommandRouter() {}

    public static final class Match {
        public final boolean woke;
        public final RexCommand command;
        public final String chinese, english;
        Match(boolean woke, RexCommand command, String chinese, String english) {
            this.woke=woke; this.command=command; this.chinese=chinese; this.english=english;
        }
        public String display() {
            if(!chinese.isEmpty() && !english.isEmpty()) return "中: "+chinese+" | EN: "+english;
            if(!chinese.isEmpty()) return "中: "+chinese;
            return "EN: "+english;
        }
    }

    public static Match resolve(String chinese,String english,boolean alreadyAwake) {
        String zh=chinese==null?"":chinese.trim();
        String en=english==null?"":english.trim();
        String remainingZh=RexCommand.stripWakePrefix(zh);
        String remainingEn=RexCommand.stripWakePrefix(en);
        boolean wake=remainingZh!=null || remainingEn!=null;
        RexCommand cmd=null;
        if(wake) {
            cmd=motion(remainingZh);
            if(cmd==null) cmd=motion(remainingEn);
            if(cmd==null && remainingZh==null) cmd=motion(zh);
            if(cmd==null && remainingEn==null) cmd=motion(en);
        } else if(alreadyAwake) {
            cmd=motion(zh);
            if(cmd==null) cmd=motion(en);
            if(cmd==null) {
                // Sleeping is a mode change, not a robot command.
                if(RexCommand.parse(zh)==RexCommand.SLEEP ||
                   RexCommand.parse(en)==RexCommand.SLEEP) cmd=RexCommand.SLEEP;
            }
        }
        return new Match(wake,cmd,zh,en);
    }
    private static RexCommand motion(String text) {
        RexCommand result=RexCommand.parse(text);
        return RexCommand.isAction(result)?result:null;
    }
}
