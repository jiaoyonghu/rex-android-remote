package com.rex.remote;

/**
 * Very low-latency heuristic VOICE-LIKE sound gate on 50 ms 16 kHz PCM frames.
 * Combines adaptive noise floor, high-pass filtering, and periodicity typical
 * of voiced human phonemes. NOT speaker ID, NOT reliable speech detection:
 * recorded speech/music can still pass and breathy/whispered speech can fail.
 */
public final class SpeechActivityGate {
    private float background=.003f;
    private int consecutiveOn,consecutiveOff;
    private boolean active;
    public boolean isActive(){return active;}
    public float process(short[] samples,int count){
        if(count<320)return 0f;
        double square=0,highSquare=0;
        float previous=0,highPrevious=0;
        float[] high=new float[count];
        int zeroCrossings=0;
        for(int i=0;i<count;i++){
            float v=samples[i]/32768f;
            square+=v*v;
            float hp=v-previous+.972f*highPrevious;
            previous=v;highPrevious=hp;
            high[i]=hp;highSquare+=hp*hp;
            if(i>0 && ((high[i-1]>=0)!=(hp>=0)))zeroCrossings++;
        }
        float rms=(float)Math.sqrt(square/count);
        float zcr=zeroCrossings/(float)(count-1);
        float filteredRms=(float)Math.sqrt(highSquare/count);
        if(!active && rms<background*2.0f){
            background=.995f*background+.005f*rms;
        }
        background=Math.max(.0016f,Math.min(.03f,background));
        float threshold=Math.max(.0038f,background*1.70f);
        float ratio=rms/Math.max(.001f,background);
        float corr=0f;
        // 85-360Hz fundamental, 50ms window: ~13k multiply-adds/frame.
        if(rms>=threshold && filteredRms>.002f && zcr>.012f && zcr<.29f){
            for(int lag=45;lag<=185;lag+=5){
                double prod=0,d1=0,d2=0;
                for(int i=lag;i<count;i+=2){
                    float a=high[i],b=high[i-lag];
                    prod+=a*b;d1+=a*a;d2+=b*b;
                }
                if(d1>.00001 && d2>.00001){
                    float value=(float)(prod/Math.sqrt(d1*d2));
                    if(value>corr)corr=value;
                }
            }
        }
        // Fundamental periodicity is the inexpensive, immediate hint of speech.
        // Stronger nearby voices are allowed with slightly weaker periodicity.
        boolean probableVoice=(ratio>1.65f && corr>.28f) ||
            (ratio>3.8f && corr>.19f && zcr>.03f && zcr<.23f);
        if(probableVoice){consecutiveOn++;consecutiveOff=0;}
        else{consecutiveOff++;consecutiveOn=0;}
        if(!active && consecutiveOn>=2)active=true; // ~100ms onset
        if(active && consecutiveOff>=6)active=false; // ~300ms hangover
        if(!active)return 0f;
        float voiceLoudness=Math.max(0f,Math.min(1f,(rms-threshold)*38f));
        return Math.min(1f,.22f+voiceLoudness*1.55f);
    }
}
