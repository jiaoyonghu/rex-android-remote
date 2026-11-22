package com.rex.remote;

/**
 * Diagnostic observations of the REAL voice pipeline, not a synthetic progress bar.
 * Does not alter recognition thresholds, model results or motion/IR behaviour.
 * No audio or voiceprints are stored here.
 */
public final class VoiceDiagnostics {
    public static final class Snapshot {
        public final float dbfs;
        public final boolean activity;
        public final long frames, dropped, kwsAt, vadAt, verdictAt;
        public final String engine, keyword, vad, personal, verdict, cause, timing;
        Snapshot(float dbfs,boolean activity,long frames,long dropped,
                 long kwsAt,long vadAt,long verdictAt,String engine,String keyword,
                 String vad,String personal,String verdict,String cause,String timing){
            this.dbfs=dbfs;this.activity=activity;this.frames=frames;this.dropped=dropped;
            this.kwsAt=kwsAt;this.vadAt=vadAt;this.verdictAt=verdictAt;
            this.engine=engine;this.keyword=keyword;this.vad=vad;this.personal=personal;
            this.verdict=verdict;this.cause=cause;this.timing=timing;
        }
    }
    private float dbfs=-90f;
    private boolean activity;
    private long frames,dropped,kwsAt,vadAt,verdictAt;
    private String engine="未启动", keyword="160ms滚动PCM缓存未启动",vad="尚无完整语音片段",
        personal="未评估（需先得到VAD片段）",verdict="尚无最终裁决",
        cause="先启动监听，再说一条完整固定口令",timing="暂无耗时数据";

    public synchronized void reset(){
        frames=0;dropped=0;kwsAt=0;vadAt=0;verdictAt=0;dbfs=-90f;activity=false;
        keyword="160ms滚动PCM缓存未启动";vad="尚无完整语音片段";
        personal="未评估（需先得到VAD片段）";verdict="尚无最终裁决";
        cause="记录已清空；请说一条固定口令";timing="暂无耗时数据";
    }
    public synchronized void onEngine(String newState){engine=newState;}
    public synchronized void onAudio(short[] samples,int n,boolean activityNow){
        if(samples==null || n<=0)return;
        double sum=0;
        for(int i=0;i<n;i++){double x=samples[i]/32768.0;sum+=x*x;}
        dbfs=(float)Math.max(-90d,20d*Math.log10(Math.max(1e-5,Math.sqrt(sum/n))));
        activity=activityNow;frames++;
    }
    public synchronized void onDroppedAudio(){dropped++;cause="解码线程跟不上：过期录音已被丢弃";}
    /** PCM hop notification only; not a KWS hit and not neural inference. */
    public synchronized void onStreamHop(long hops,int bufferedSamples,long whenMs){
        kwsAt=whenMs;
        keyword="160ms PCM步进 "+hops+" 次 · 滚动缓存 "+
            String.format(java.util.Locale.ROOT,"%.2f",bufferedSamples/16000f)+" 秒";
    }
    public synchronized void onKeyword(String text,boolean conflict,long elapsedMs){
        if(text==null||text.isEmpty())return;
        kwsAt=elapsedMs;
        keyword="命中："+text+(conflict?"（与其他词冲突）":"");
        personal="等待VAD分段后评估";
        cause="KWS 已检测到关键词，等待 VAD 交付完整语音片段";
    }
    public synchronized void onVad(int sampleCount,boolean accepted,String keywordInSegment,
                                   long elapsedMs){
        vadAt=elapsedMs;
        vad=String.format(java.util.Locale.ROOT,"片段 %.2f 秒%s",
            sampleCount/16000f, accepted?" · 有效":" · 长度不合格");
        if(!accepted){cause="VAD 有片段，但长度未通过录音时长检查";return;}
        if(keywordInSegment==null||keywordInSegment.trim().isEmpty()){
            cause="VAD已产生有效片段，已交给独立TFLite神经线程";
        }else{
            cause="VAD片段包含旧版KWS证据（兼容测试）";
        }
    }
    public synchronized void onPersonal(String result){personal=result==null?"未评估":result;}
    public synchronized void onVerdict(String result,String why,long nowMs,long processingMs){
        verdictAt=nowMs;verdict=result==null?"未输出":result;
        cause=why==null?"无进一步说明":why;
        String a=(kwsAt>0&&kwsAt<=nowMs)?(nowMs-kwsAt)+"ms":"无KWS时间";
        String b=(vadAt>0&&vadAt<=nowMs)?(nowMs-vadAt)+"ms":"无VAD时间";
        timing="缓存步进→裁决 "+a+" / VAD→裁决 "+b+
            " / 本段策略处理 "+Math.max(0,processingMs)+"ms";
    }
    public synchronized void onError(String stage,String reason){
        cause="异常阶段："+stage+" · "+reason;
        verdict="发生错误（不输出动作）";
    }
    public synchronized Snapshot snapshot(){
        return new Snapshot(dbfs,activity,frames,dropped,kwsAt,vadAt,verdictAt,
            engine,keyword,vad,personal,verdict,cause,timing);
    }
}
