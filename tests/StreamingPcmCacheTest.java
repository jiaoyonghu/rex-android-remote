package com.rex.remote;

public final class StreamingPcmCacheTest {
    private static void ok(boolean yes,String msg){if(!yes)throw new AssertionError(msg);}
    public static void main(String[] args){
        StreamingPcmCache c=new StreamingPcmCache(48000,2560);
        short[] packet=new short[800];
        for(int i=0;i<packet.length;i++)packet[i]=(short)i;
        ok(c.add(packet,800,1)==0,"50ms no hop yet");
        ok(c.add(packet,800,1)==0,"100ms no hop yet");
        ok(c.add(packet,800,1)==0,"150ms no hop yet");
        ok(c.add(packet,800,1)==1,"200ms crosses one 160ms hop");
        ok(c.hops()==1 && c.available()==3200,"hops and bounded data");
        short[] snapshot=c.snapshot();
        ok(snapshot.length==3200 && snapshot[799]==799&&snapshot[800]==0,
            "rolling PCM keeps chronological samples");
        short[] huge=new short[48000];
        java.util.Arrays.fill(huge,(short)99);
        ok(c.add(huge,huge.length,1)>=18,"large batch handles many hops");
        ok(c.snapshot().length==48000 && c.snapshot()[0]==99,"bounded ring wraps");
        ok(c.add(packet,800,2)==0 && c.hops()==0 && c.available()==800,
            "generation reset prevents stale audio replay");
        c.reset();
        ok(c.available()==0,"explicit reset");
        System.out.println("PASS: 160ms incremental PCM cache (not incremental Mel)");
    }
}
