package com.rex.remote;

import java.util.Locale;

/** Fixed robot vocabulary. Exact matching after punctuation normalization. */
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

    /** Recognizer may transcribe the spoken English wake word as Chinese. */
    public static boolean isWake(String text) {
        String s = norm(text);
        return s.equals("rex") || s.equals("rex醒了吗")
            || s.equals("瑞克斯") || s.equals("瑞克斯醒了吗")
            || s.equals("雷克斯") || s.equals("雷克斯醒了吗");
    }

    /** Returns the trailing command only when the utterance starts with a wake word. */
    public static String stripWakePrefix(String text) {
        String s = norm(text);
        for (String prefix : new String[]{"rex醒了吗", "瑞克斯醒了吗", "雷克斯醒了吗",
                "rex", "瑞克斯", "雷克斯"}) {
            if (s.startsWith(prefix)) return s.substring(prefix.length());
        }
        return null;
    }

    public static RexCommand parse(String text) {
        String s = norm(text);
        switch (s) {
            case "rex": case "rex醒了吗": case "瑞克斯": case "瑞克斯醒了吗":
            case "雷克斯": case "雷克斯醒了吗": return WAKE;
            case "站立": case "立正": return STAND;
            case "前进": case "疯狂水世界启动": return FORWARD;
            case "左移": return LEFT;
            case "右移": return RIGHT;
            case "后退": case "退": return BACKWARD;
            case "左转圈": return TURN_LEFT;
            case "右转圈": return TURN_RIGHT;
            case "好停": case "停": return STOP;
            case "闪身步": return DODGE;
            case "转个圈": case "turnaround": return TURN_AROUND;
            case "挂机": case "挂壁": case "disable": return DISABLE;
            case "激活": case "active": return ACTIVE;
            default: return null;
        }
    }
}
