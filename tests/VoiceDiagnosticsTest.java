package com.rex.remote;

/** Regression checks that diagnostic observations are factual and non-operative. */
public final class VoiceDiagnosticsTest {
    private static void check(boolean yes,String msg){if(!yes)throw new AssertionError(msg);}
    public static void main(String[] args){
        VoiceDiagnostics d=new VoiceDiagnostics();
        VoiceDiagnostics.Snapshot s=d.snapshot();
        check(s.frames==0 && s.kwsAt==0 && s.verdictAt==0,"initial state");
        short[] quiet=new short[800];
        d.onAudio(quiet,quiet.length,false);
        check(d.snapshot().dbfs<=-85 && d.snapshot().frames==1,"silence dBFS");
        short[] voice=new short[800];
        java.util.Arrays.fill(voice,(short)8192);
        d.onAudio(voice,voice.length,true);
        check(d.snapshot().dbfs>-15 && d.snapshot().dbfs<-10,"PCM rms scale");
        d.onKeyword("Rex醒了吗",false,1000);
        check(d.snapshot().kwsAt==1000,"actual KWS hit");
        d.onVad(16000,true,"Rex醒了吗",1330);
        check(d.snapshot().vad.contains("1.00") && d.snapshot().vadAt==1330,
            "real VAD segment");
        d.onPersonal("个人匹配通过 · 0.852 / 门槛 0.780");
        d.onVerdict("唤醒成功","KWS验证成功",1450,30);
        s=d.snapshot();
        check(s.timing.contains("450ms") && s.timing.contains("120ms"),"latency");
        check(s.personal.contains("0.852"),"scores visible");
        d.onDroppedAudio();
        check(d.snapshot().dropped==1,"queue overrun visible");
        d.reset();
        s=d.snapshot();
        check(s.dropped==0 && s.kwsAt==0 && s.frames==0,"reset all counters");
        d.onVad(1000,false,"",1800);
        check(d.snapshot().vad.contains("不合格"),"VAD rejection visible");
        d.onError("模型加载","UnsatisfiedLinkError");
        check(d.snapshot().cause.contains("模型加载"),"initialization failure visible");
        System.out.println("PASS: pipeline telemetry, dBFS, KWS/VAD events, match score, timing, drops, reset");
    }
}
