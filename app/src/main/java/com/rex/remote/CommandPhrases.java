package com.rex.remote;

/** Exact spoken phrases the owner approved, with no added aliases. */
public final class CommandPhrases {
    private CommandPhrases() {}
    public static final String[] ALL = {
        "Rex", "Rex醒了吗", "灭建国", "睡觉",
        "站立", "立正", "前进", "疯狂水世界，启动",
        "左移", "右移", "后退", "退", "左转圈", "右转圈",
        "好，停", "停", "闪身步", "转个圈", "turn around",
        "挂机", "挂壁", "disable", "失能", "激活", "active"
    };
    public static int count(){return ALL.length;}
    public static String at(int index){return ALL[index];}
    public static RexCommand command(int index){
        return index>=0 && index<ALL.length ? RexCommand.parse(ALL[index]) : null;
    }
}
