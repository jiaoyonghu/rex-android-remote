package com.rex.remote;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.Closeable;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;
import android.content.Context;

/**
 * openWakeWord v0.5.1 frozen MEL + Google Speech Embedding frontend.
 * This is NOT the official openWakeWord DNN head and NOT speaker verification.
 * Each recorded command becomes a 16 x 96 neural sequence; the small personalized
 * classifier is fitted locally from supervised takes and other phrases as negatives.
 * Only the existing single decoder worker calls this class; never capture thread.
 */
public final class OpenWakeWordFeatures implements Closeable {
    public static final int DIM=16*96;
    private static final int MEL_BINS=32, MEL_WINDOW=76, EMBEDDINGS=16;
    private static final int RATE=16000, CONTEXT_SAMPLES=48000;
    private final OrtEnvironment env;
    private final OrtSession mel, speech;

    public OpenWakeWordFeatures(Context ctx) throws IOException, OrtException {
        env=OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts=new OrtSession.SessionOptions();
        try {
            opts.setIntraOpNumThreads(1);opts.setInterOpNumThreads(1);
            byte[] melBytes=read(ctx,"openwakeword/melspectrogram.onnx");
            byte[] embBytes=read(ctx,"openwakeword/embedding_model.onnx");
            mel=env.createSession(melBytes,opts);
            speech=env.createSession(embBytes,opts);
        } finally { opts.close(); }
    }
    private static byte[] read(Context ctx,String path) throws IOException{
        try(java.io.InputStream s=ctx.getAssets().open(path)){
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            byte[] buf=new byte[16384];int n;
            while((n=s.read(buf))!=-1){
                if(out.size()+n>10_000_000)throw new IOException("模型尺寸异常: "+path);
                out.write(buf,0,n);
            }
            return out.toByteArray();
        }
    }
    public float[] embed(short[] pcm) {
        if(pcm==null||pcm.length<3200||pcm.length>RATE*5)return null;
        // Align the utterance centrally in a stable ~3 s context. Avoid having
        // 1-syllable templates entirely overwhelmed by trailing silence.
        float[] audio=new float[CONTEXT_SAMPLES];
        int use=Math.min(pcm.length,CONTEXT_SAMPLES-3200);
        int from=Math.max(0,pcm.length-use);
        int start=(CONTEXT_SAMPLES-use)/2;
        for(int i=0;i<use;i++)audio[start+i]=pcm[from+i]; // model uses unnormalised PCM16
        try(OnnxTensor input=OnnxTensor.createTensor(env,FloatBuffer.wrap(audio),new long[]{1,CONTEXT_SAMPLES});
            OrtSession.Result output=mel.run(Collections.singletonMap("input",input))) {
            OnnxTensor spectral=(OnnxTensor)output.get(0);
            long[] shape=spectral.getInfo().getShape();
            if(shape.length!=4||shape[3]!=MEL_BINS||shape[2]<MEL_WINDOW)return null;
            int count=(int)shape[2];
            FloatBuffer buf=spectral.getFloatBuffer();
            float[] full=new float[count*MEL_BINS];
            for(int i=0;i<full.length;i++)full[i]=buf.get(i)/10f+2f;
            float[] packed=new float[EMBEDDINGS*MEL_WINDOW*MEL_BINS];
            int available=count-MEL_WINDOW;
            for(int step=0;step<EMBEDDINGS;step++){
                int fromFrame=Math.round(step*(available/(float)(EMBEDDINGS-1)));
                System.arraycopy(full,fromFrame*MEL_BINS,packed,step*MEL_WINDOW*MEL_BINS,MEL_WINDOW*MEL_BINS);
            }
            try(OnnxTensor featureInput=OnnxTensor.createTensor(env,FloatBuffer.wrap(packed),
                    new long[]{EMBEDDINGS,MEL_WINDOW,MEL_BINS,1});
                OrtSession.Result result=speech.run(Collections.singletonMap("input_1",featureInput))){
                OnnxTensor vectors=(OnnxTensor)result.get(0);
                FloatBuffer v=vectors.getFloatBuffer();
                if(v.remaining()<DIM)return null;
                float[] features=new float[DIM];
                for(int i=0;i<features.length;i++)features[i]=v.get(i);
                return PersonalNeuralStore.unit(features);
            }
        }catch(OrtException|RuntimeException ex){
            android.util.Log.e("RExPersonalNeural","openWakeWord feature inference failed",ex);
            return null;
        }
    }
    @Override public void close(){try{speech.close();}catch(Exception ignored){}try{mel.close();}catch(Exception ignored){}}
}
