package com.rex.remote;

import java.util.Locale;

/** 16 bit NEC extended address + 8 bit command + ~command; single fire only. */
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
    DODGE(0x19, "闪身步", "随机左右 3s"),
    TURN_AROUND(0x1A, "转个圈", "随机方向"),
    DISABLE(0x1B, "失能", ""),
    ACTIVE(0x1C, "激活", "");

    public final int code;
    public final String label;
    public final String speed;

    RexCommand(int code, String label, String speed) {
        this.code = code;
        this.label = label;
        this.speed = speed;
    }

    /** Local command parser: strict phrase list. Caller must validate speaker before transmitting. */
    public static RexCommand parse(String text) {
        if (text == null) return null;
        String s = text.toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？,.!?]", "");
        if (s.equals("rex") || s.equals("rex醒了吗")) return WAKE;
        if (s.equals("站立") || s.equals("立正")) return STAND;
        if (s.equals("前进") || s.equals("疯狂水世界启动")) return FORWARD;
        if (s.equals("左移")) return LEFT;
        if (s.equals("右移")) return RIGHT;
        if (s.equals("后退") || s.equals("退")) return BACKWARD;
        if (s.equals("左转圈")) return TURN_LEFT;
        if (s.equals("右转圈")) return TURN_RIGHT;
        if (s.equals("好停") || s.equals("停")) return STOP;
        if (s.equals("闪身步")) return DODGE;
        if (s.equals("转个圈") || s.equals("turnaround")) return TURN_AROUND;
        if (s.equals("挂机") || s.equals("挂壁") || s.equals("disable")) return DISABLE;
        if (s.equals("激活") || s.equals("active")) return ACTIVE;
        return null;
    }
}
