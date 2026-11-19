package com.rex.remote;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;

/** 50ms PCM frames -> bounded isolated speech segments, using existing VAD. */
public final class CommandAudioSegmenter {
    private final ArrayDeque<short[]> leading=new ArrayDeque<>();
    private final ByteArrayOutputStream recording=new ByteArrayOutputStream(48000);
    private boolean inSpeech;
    private int trailingFrames;
    private int speechFrames;
    public void reset(){leading.clear();recording.reset();inSpeech=false;trailingFrames=0;speechFrames=0;}
    public short[] accept(short[] pcm,int count,boolean speaking){
        if(pcm==null||count<1)return null;
        if(!inSpeech){
            if(!speaking){
                short[] frame=java.util.Arrays.copyOf(pcm,count);
                leading.addLast(frame);
                while(leading.size()>7)leading.removeFirst();
                return null;
            }
            inSpeech=true;
            while(!leading.isEmpty())append(leading.removeFirst());
        }
        append(pcm,count);
        if(speaking){trailingFrames=0;speechFrames++;}
        else trailingFrames++;
        if(trailingFrames<7 && recording.size()<2*16000*5)return null;
        short[] result=null;
        if(speechFrames>=4){
            byte[] bytes=recording.toByteArray();
            result=new short[bytes.length/2];
            for(int i=0;i<result.length;i++)result[i]=(short)((bytes[i*2]&0xff)|(bytes[i*2+1]<<8));
        }
        reset();return result;
    }
    private void append(short[] samples){append(samples,samples.length);}
    private void append(short[] samples,int count){
        for(int i=0;i<count;i++){
            short s=samples[i];recording.write(s&255);recording.write((s>>>8)&255);
        }
    }
}
