package com.rex.remote;

/**
 * Sensitive energy-only gate for buffering candidate command audio, separate
 * from the stricter speech-likeness gate used to animate particles. It can
 * capture noise/music; template rejection and the ASR whitelist remain vital.
 */
public final class CommandActivityGate {
    private float floor=.0018f;
    private int start,end;
    private boolean active;
    public void reset(){start=0;end=0;active=false;}
    public boolean accept(short[] pcm,int count,boolean voiceLike){
        if(count<160)return false;
        double sum=0;
        for(int i=0;i<count;i++){
            float s=pcm[i]/32768f;sum+=s*s;
        }
        float rms=(float)Math.sqrt(sum/count);
        if(!active && !voiceLike && rms<floor*2.0f)
            floor=.993f*floor+.007f*rms;
        floor=Math.max(.0008f,Math.min(.02f,floor));
        boolean loud=voiceLike || rms>Math.max(.0020f,floor*1.65f);
        if(loud){start++;end=0;}
        else{end++;start=0;}
        if(!active&&start>=2)active=true;
        if(active&&end>=7)active=false;
        return active;
    }
}
