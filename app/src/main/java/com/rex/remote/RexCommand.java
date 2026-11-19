package com.rex.remote;

import java.util.Locale;

/**
 * Exact user-approved vocabulary ONLY. All motion text is matched as a COMPLETE
 * utterance, never substring-search in arbitrary speech. SLEEP is UI-only.
 * Voice recognition cannot transmit IR in this version.
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
    DODGE(0x19, "闪身步", "随机左/右 · 3 秒后 STOP"),
    TURN_AROUND(0x1A, "转个圈", "随机左右转 · 0.4 rad/s"),
    DISABLE(0x1B, "失能", ""),
    ACTIVE(0x1C, "激活", ""),
    SLEEP(-1, "睡觉（回待机）", "仅 App 状态，无红外");

    public final int code;
    public final String label;
    public final String speed;
    RexCommand(int code, String label, String speed) {
        this.code=code; this.label=label; this.speed=speed;
    }

    public static String norm(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\p{Punct}，。！？、：；“”‘’（）《》～…]", "");
    }

    public static boolean isWake(String text) { return stripWakePrefix(text) != null; }

    /** Only Rex, Rex醒了吗, 灭建国 are wake words. */
    public static String stripWakePrefix(String text) {
        String s=norm(text);
        for(String wake:new String[]{"rex醒了吗","灭建国","rex"}) {
            if(s.startsWith(wake)) {
                String rest=s.substring(wake.length());
                // Prevent accidental wake via arbitrary phrases beginning with 'rex'.
                if(rest.isEmpty() || isAction(parse(rest))) return rest;
            }
        }
        return null;
    }

    public static boolean isAction(RexCommand c) {
        return c!=null && c!=WAKE && c!=SLEEP;
    }

    /** Only the exact spoken phrases explicitly approved by the user. */
    public static RexCommand parse(String text) {
        switch(norm(text)) {
            case "rex": case "rex醒了吗": case "灭建国": return WAKE;
            case "睡觉": return SLEEP;
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
            case "挂机": case "挂壁": case "disable": case "失能": return DISABLE;
            case "激活": case "active": return ACTIVE;
            default: return null;
        }
    }
}
