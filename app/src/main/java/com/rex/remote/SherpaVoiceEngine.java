package com.rex.remote;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 16 kHz streaming sherpa-onnx keyword recognizer + Silero neural VAD.
 * Called ONLY on one decode thread; never on the AudioRecord thread.
 */
public final class SherpaVoiceEngine implements AutoCloseable {
    public static final class Clip {
        public final short[] pcm;
        public final String keyword;
        public final boolean conflictingKeywords;
        Clip(short[] pcm, String keyword, boolean conflict) {
            this.pcm=pcm;this.keyword=keyword;this.conflictingKeywords=conflict;
        }
    }
    private final KeywordSpotter kws;
    private SpeakerEmbeddingExtractor speaker;
    private final File folder;
    private static final int MIN_SPEAKER_SAMPLES=16000; // too-short audio is not biometrically reliable
    private OnlineStream stream;
    private final Vad vad;
    private final float[] vadWindow=new float[512];
    private int windowFill;
    private String keyword="";
    private boolean conflict;
    private final List<Clip> completed=new ArrayList<>();
    public SherpaVoiceEngine(File folder){
        this.folder=folder;
        String prefix="epoch-13-avg-2-chunk-16-left-64";
        OnlineTransducerModelConfig t=OnlineTransducerModelConfig.builder()
            .setEncoder(new File(folder,"encoder-"+prefix+".int8.onnx").getAbsolutePath())
            .setDecoder(new File(folder,"decoder-"+prefix+".onnx").getAbsolutePath())
            .setJoiner(new File(folder,"joiner-"+prefix+".int8.onnx").getAbsolutePath())
            .build();
        OnlineModelConfig m=OnlineModelConfig.builder()
            .setTransducer(t)
            .setTokens(new File(folder,"tokens.txt").getAbsolutePath())
            .setNumThreads(2).setDebug(false).build();
        KeywordSpotterConfig k=KeywordSpotterConfig.builder()
            .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).build())
            .setOnlineModelConfig(m)
            .setKeywordsFile(new File(folder,"keywords.txt").getAbsolutePath())
            .setMaxActivePaths(4).setKeywordsScore(1.0f)
            .setKeywordsThreshold(0.40f).setNumTrailingBlanks(1).build();
        kws=new KeywordSpotter(k);
        stream=kws.createStream();
        SileroVadModelConfig s=SileroVadModelConfig.builder()
            .setModel(new File(folder,"silero_vad.onnx").getAbsolutePath())
            .setThreshold(0.45f).setMinSpeechDuration(0.18f)
            .setMinSilenceDuration(0.22f).setMaxSpeechDuration(5f)
            .setWindowSize(512).build();
        VadModelConfig v=VadModelConfig.builder()
            .setSileroVadModelConfig(s).setSampleRate(16000)
            .setNumThreads(1).setProvider("cpu").setDebug(false).build();
        vad=new Vad(v);

    }
    /** At most one on-demand speaker extraction per meaningful long clip. */
    public float[] speakerEmbedding(short[] pcm){
        if(pcm==null || pcm.length<MIN_SPEAKER_SAMPLES || pcm.length>80000)return null;
        if(speaker==null){
            SpeakerEmbeddingExtractorConfig sc=SpeakerEmbeddingExtractorConfig.builder()
                .setModel(new File(folder,"campplus_zh_en.onnx").getAbsolutePath())
                .setNumThreads(1).setDebug(false).build();
            speaker=new SpeakerEmbeddingExtractor(sc);
        }
        float[] samples=new float[pcm.length];
        for(int i=0;i<pcm.length;i++)samples[i]=pcm[i]/32768f;
        OnlineStream s=speaker.createStream();
        try{
            s.acceptWaveform(samples,16000);s.inputFinished();
            return SpeakerMath.unit(speaker.compute(s));
        }finally{s.release();}
    }
    private void addKeyword(String hit){
        if(hit==null || hit.isEmpty())return;
        String normalized=hit.replace('_',' ');
        if(keyword.isEmpty())keyword=normalized;
        else if(!RexCommand.norm(keyword).equals(RexCommand.norm(normalized))){
            // One utterance may legitimately contain wake + ONE exact action.
            // Preserve that ordering; all other competing keywords conflict.
            RexCommand prev=RexCommand.parse(keyword);
            RexCommand next=RexCommand.parse(normalized);
            if(prev==RexCommand.WAKE && RexCommand.isAction(next))
                keyword=keyword+","+normalized;
            else conflict=true;
        }
    }
    public List<Clip> accept(short[] pcm,int length){
        float[] audio=new float[length];
        for(int i=0;i<length;i++)audio[i]=pcm[i]/32768f;
        stream.acceptWaveform(audio,16000);
        int iterations=0;
        while(kws.isReady(stream) && iterations++<40){
            kws.decode(stream);
            String hit=kws.getResult(stream).getKeyword();
            if(hit!=null && !hit.isEmpty()){
                addKeyword(hit);
                // Official sherpa-onnx KWS examples reset immediately after a
                // hit. The VAD segment is maintained separately.
                kws.reset(stream);
            }
        }
        for(float f:audio){
            vadWindow[windowFill++]=f;
            if(windowFill==vadWindow.length){
                vad.acceptWaveform(vadWindow);
                windowFill=0;
                while(!vad.empty()){
                    float[] samples=vad.front().getSamples();
                    vad.pop();
                    if(samples.length>=2400 && samples.length<=80000){
                        short[] out=new short[samples.length];
                        for(int i=0;i<samples.length;i++)
                            out[i]=(short)Math.round(Math.max(-1,Math.min(1,samples[i]))*32767f);
                        completed.add(new Clip(out,keyword,conflict));
                    }
                    keyword="";conflict=false;
                    kws.reset(stream);
                }
            }
        }
        List<Clip> result=new ArrayList<>(completed);
        completed.clear();
        return result;
    }
    /** Must discard unfinished utterance across a training/enrollment mode change. */
    public void reset(){
        kws.reset(stream);vad.reset();keyword="";conflict=false;windowFill=0;completed.clear();
    }
    @Override public void close(){
        try{stream.release();}finally{try{kws.release();}finally{try{vad.release();}finally{if(speaker!=null)speaker.release();}}}
    }
}
