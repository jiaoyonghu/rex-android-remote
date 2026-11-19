package com.rex.remote;

import java.util.Locale;

/** The phrases are ONLY for voiceprint enrollment, never robot commands. */
public final class EnrollmentPrompts {
    private EnrollmentPrompts() {}
    public static final String[] PROMPTS = {
        "今天我要录入自己的声音",
        "我正在测试离线语音识别",
        "机器人可以听到我说的话",
        "现在准备完成声纹的采集",
        "Rex turn around active"
    };
    public static int count(){return PROMPTS.length;}
    public static String at(int index){return PROMPTS[index];}

    static String normalize(String text) {
        if(text==null) return "";
        return text.toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\p{Punct}，。！？、：；“”‘’（）《》～…]", "");
    }
    static float similarity(String a, String b){
        a=normalize(a);b=normalize(b);
        if(a.isEmpty() || b.isEmpty())return 0f;
        int[] prev=new int[b.length()+1],next=new int[b.length()+1];
        for(int j=0;j<=b.length();j++)prev[j]=j;
        for(int i=1;i<=a.length();i++){
            next[0]=i;
            for(int j=1;j<=b.length();j++){
                int cost=a.charAt(i-1)==b.charAt(j-1)?0:1;
                next[j]=Math.min(Math.min(next[j-1]+1,prev[j]+1),prev[j-1]+cost);
            }
            int[] t=prev;prev=next;next=t;
        }
        return Math.max(0f,1f-prev[b.length()]/(float)Math.max(a.length(),b.length()));
    }
    public static boolean matches(int index,String chinese,String english){
        if(index<0 || index>=count())return false;
        String expected=PROMPTS[index];
        // Fairly close whole-utterance match, not substring keyword spotting.
        // ASR transcription may contain some errors, especially code-switching.
        float threshold=index==count()-1?.72f:.69f;
        return Math.max(similarity(expected,chinese),similarity(expected,english))>=threshold;
    }
}
