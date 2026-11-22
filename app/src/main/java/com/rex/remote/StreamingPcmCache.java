package com.rex.remote;

/** Bounded PCM rolling cache with exactly 160 ms / 2560-sample hops at 16 kHz.
 * This is incremental AUDIO buffering, not incremental TFLite Mel inference.
 * The final VAD clip is still embedded with the v1.4-compatible fixed 3s input.
 * No microphone data is stored to disk.
 */
public final class StreamingPcmCache {
    private final short[] ring;
    private final int hopSamples;
    private long generation=Long.MIN_VALUE,hopCount;
    private int next,available,pending;
    public StreamingPcmCache(int capacity,int hop){
        if(capacity<=0||hop<=0||hop>capacity)throw new IllegalArgumentException();
        ring=new short[capacity];hopSamples=hop;
    }
    public synchronized int add(short[] input,int count,long gen){
        if(input==null||count<0||count>input.length)throw new IllegalArgumentException();
        if(gen!=generation){generation=gen;next=0;available=0;pending=0;hopCount=0;}
        for(int i=0;i<count;i++){
            ring[next]=input[i];next=(next+1)%ring.length;
            if(available<ring.length)available++;
        }
        int completed=(pending+count)/hopSamples;
        pending=(pending+count)%hopSamples;
        hopCount+=completed;
        return completed;
    }
    public synchronized short[] snapshot(){
        short[] result=new short[available];
        int start=(next-available+ring.length)%ring.length;
        for(int i=0;i<available;i++)result[i]=ring[(start+i)%ring.length];
        return result;
    }
    public synchronized int available(){return available;}
    public synchronized long hops(){return hopCount;}
    public synchronized void reset(){generation=Long.MIN_VALUE;next=available=pending=0;hopCount=0;}
}
