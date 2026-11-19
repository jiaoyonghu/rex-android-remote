package com.rex.remote;

/**
 * Deterministic command fusion for two independent ASR transcripts of SAME audio.
 * Cases such as English="Rex" and Chinese="前进" become wake+FORWARD.
 * No substring-based motion matching and no infrared authorization.
 */
public final class BilingualCommandRouter {
    private BilingualCommandRouter() { }

    public static final class Match {
        public final boolean woke;
        public final RexCommand command;
        public final String chinese;
        public final String english;
        Match(boolean woke, RexCommand command, String chinese, String english) {
            this.woke = woke;
            this.command = command;
            this.chinese = chinese;
            this.english = english;
        }
        public String display() {
            if (!chinese.isEmpty() && !english.isEmpty())
                return "中: " + chinese + " | EN: " + english;
            if (!chinese.isEmpty()) return "中: " + chinese;
            return "EN: " + english;
        }
    }

    public static Match resolve(String chinese, String english, boolean alreadyAwake) {
        String zh = chinese == null ? "" : chinese.trim();
        String en = english == null ? "" : english.trim();
        String afterZh = RexCommand.stripWakePrefix(zh);
        String afterEn = RexCommand.stripWakePrefix(en);
        boolean wake = afterZh != null || afterEn != null;
        RexCommand cmd = null;
        if (wake) {
            // A bilingual sentence can be split across the two decoders.
            if (afterZh != null) cmd = motion(afterZh);
            if (cmd == null && afterEn != null) cmd = motion(afterEn);
            if (cmd == null && afterZh == null) cmd = motion(zh);
            if (cmd == null && afterEn == null) cmd = motion(en);
        } else if (alreadyAwake) {
            cmd = motion(zh);
            if (cmd == null) cmd = motion(en);
        }
        return new Match(wake, cmd, zh, en);
    }

    private static RexCommand motion(String text) {
        RexCommand c = RexCommand.parse(text);
        return c == RexCommand.WAKE ? null : c;
    }
}
