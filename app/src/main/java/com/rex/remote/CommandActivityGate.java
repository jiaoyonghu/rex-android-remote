package com.rex.remote;

/**
 * v0.9: candidate recording begins only after sustained speech-like activity.
 * Energy alone (a knock, fan, rustle or auto-gain noise burst) may never start
 * the command segmenter. After speech starts, softer consonants are retained.
 * This is a light-weight prefilter, not a trained neural VAD.
 */
public final class CommandActivityGate {
    private float floor=.0025f;
    private int onset,hangover;
    private boolean active;
    public void reset(){onset=0;hangover=0;active=false;}
    public boolean accept(short[] pcm,int count,boolean voiceLike){
        if(pcm==null || count<320)return false;
        double sum=0;
        for(int i=0;i<count;i++){
            float s=pcm[i]/32768f;
            sum+=s*s;
        }
        float rms=(float)Math.sqrt(sum/count);
        if(!active && !voiceLike && rms<floor*2.0f)
            floor=.995f*floor+.005f*rms;
        floor=Math.max(.0012f,Math.min(.025f,floor));
        float threshold=Math.max(.0032f,floor*1.58f);
        boolean plausible=voiceLike && rms>=threshold;
        if(!active){
            onset=plausible?Math.min(10,onset+1):Math.max(0,onset-1);
            if(onset>=2){active=true;hangover=0;}
        }else{
            // The upstream voice detector already holds softer consonants.
            // Loud non-speech alone must NOT keep a recording open indefinitely.
            hangover=plausible?0:hangover+1;
            if(hangover>=5){active=false;onset=0;}
        }
        return active;
    }
}
