package com.rex.remote;

import java.util.Locale;

/**
 * Deliberately SMALL bilingual vocabulary. A free-form transcript cannot trigger
 * a robot command by substring matching. Voice results remain UI-only until a
 * separate, tested owner-authentication + robot safety layer is implemented.
 */
public enum RexCommand {
    WAKE(0x10, "唤醒", ""),
    STAND(0x11, "站立", ""),
    FORWARD(0x12, "前进", "0.6 m/s"),
    BACKWARD(0x13, "后退", "0.4 m/s"),
    LEFT(0x14, "左移", "0.4 m/s"),
    RIGHT(0x15, "右移", "0.4 m/s"),
    TURN_LEFT(0x16, "左转圈", "0.4 rad/s"),
    TURN_RIGHT(0x17, "右转圈", "0.4 rad/s"),
    STOP(0x18, "停止保持站立", "0"),
    DODGE(0x19, "闪身步", "随机左/右移动 · 3 秒"),
    TURN_AROUND(0x1A, "转个圈", "随机转向 · 0.4 rad/s"),
    DISABLE(0x1B, "失能", ""),
    ACTIVE(0x1C, "激活", "");

    public final int code;
    public final String label;
    public final String speed;
    RexCommand(int code, String label, String speed) {
        this.code = code; this.label = label; this.speed = speed;
    }

    public static String norm(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\p{Punct}，。！？、：；“”‘’（）《》～…]", "");
    }

    public static boolean isWake(String text) {
        return stripWakePrefix(text) != null;
    }

    /** Strict wake matching: e.g. "Rex 前进" works, "rexterity" does not. */
    public static String stripWakePrefix(String text) {
        String s = norm(text);
        for (String prefix : new String[]{"rexareyouawake", "rexareyouthere", "rexwakeup",
                "rex醒了吗", "瑞克斯醒了吗", "雷克斯醒了吗", "rex", "瑞克斯", "雷克斯"}) {
            if (s.startsWith(prefix)) {
                String remainder = s.substring(prefix.length());
                if (remainder.isEmpty() || parse(remainder) != null) return remainder;
            }
        }
        return null;
    }

    /** Whole utterance matching: English, Mandarin and selected established aliases. */
    public static RexCommand parse(String text) {
        String s = norm(text);
        switch (s) {
            case "rex": case "rex醒了吗": case "瑞克斯": case "瑞克斯醒了吗":
            case "雷克斯": case "雷克斯醒了吗": case "rexareyouawake":
            case "rexareyouthere": case "rexwakeup": return WAKE;
            case "站立": case "立正": case "stand": case "standup":
            case "attention": return STAND;
            case "前进": case "疯狂水世界启动": case "forward": case "goforward":
            case "moveforward": case "crazywaterworldstart": return FORWARD;
            case "左移": case "moveleft": case "stepleft": case "shiftleft": return LEFT;
            case "右移": case "moveright": case "stepright": case "shiftright": return RIGHT;
            case "后退": case "退": case "back": case "backward": case "goback":
            case "movebackward": return BACKWARD;
            case "左转圈": case "turnleft": case "rotateleft": case "spinleft": return TURN_LEFT;
            case "右转圈": case "turnright": case "rotateright": case "spinright": return TURN_RIGHT;
            case "好停": case "停": case "停止": case "stop": case "okaystop":
            case "okstop": case "hold": return STOP;
            case "闪身步": case "dodge": case "sidestep": return DODGE;
            case "转个圈": case "turnaround": case "spinaround": return TURN_AROUND;
            case "挂机": case "挂壁": case "disable": return DISABLE;
            case "激活": case "active": case "activate": return ACTIVE;
            default: return null;
        }
    }
}
