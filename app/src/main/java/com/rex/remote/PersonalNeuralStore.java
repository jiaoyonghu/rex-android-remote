package com.rex.remote;

import android.content.Context;
import java.io.*;
import java.util.*;

/**
 * REx few-shot command classifier on top of the frozen openWakeWord speech frontend.
 * Personalised classifier means discriminative deep embeddings + own training samples,
 * NOT a full fine-tune of the frozen neural backbone. Never authorises motor action.
 * Files are app-private, versioned, atomic and disjoint from the old MFCC store.
 */
public final class PersonalNeuralStore {
    public static final int MIN_TAKES=6, RECOMMENDED_TAKES=12, MAX_TAKES=30;
    public static final int NON_COMMAND=CommandPhrases.count();
    private static final int MAGIC=0x52584E32, VERSION=1;
    private static final int DIM=OpenWakeWordFeatures.DIM;
    private final File directory;
    private final Map<Integer,List<float[]>> cache=new HashMap<>();
    public static final class Result {
        public final int phrase, runnerUp;
        public final float score, second, threshold;
        public final int samples;
        public final boolean accepted;
        public final String reason;
        Result(int p,int runner,float s,float second,float limit,int count,boolean pass,String reason){
            phrase=p;runnerUp=runner;score=s;this.second=second;
            threshold=limit;samples=count;accepted=pass;this.reason=reason;
        }
    }
    public PersonalNeuralStore(Context ctx){this(new File(ctx.getFilesDir(),"rex_personal_neural_v12"));}
    // Package-private for pure JVM tests with a temporary directory.
    PersonalNeuralStore(File root){directory=root;}
    private File file(int phrase){return new File(directory,"phrase_"+phrase+".bin");}
    private synchronized List<float[]> load(int phrase){
        List<float[]> existing=cache.get(phrase);if(existing!=null)return existing;
        List<float[]> clips=new ArrayList<>();
        if(!valid(phrase)){cache.put(phrase,clips);return clips;}
        File f=file(phrase);
        if(f.isFile()&&f.length()<=MAX_TAKES*DIM*2L+32){
            try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(f)))){
                if(in.readInt()!=MAGIC||in.readInt()!=VERSION||in.readInt()!=DIM)throw new IOException("bad version");
                int n=in.readInt();if(n<0||n>MAX_TAKES)throw new IOException("bad count");
                for(int k=0;k<n;k++){
                    float[] v=new float[DIM];
                    for(int j=0;j<DIM;j++)v[j]=in.readShort()/32767f;
                    float[] u=unit(v);if(u==null)throw new IOException("invalid sample");
                    clips.add(u);
                }
                if(in.read()!=-1)throw new IOException("trailing bytes");
            }catch(IOException e){clips.clear();} // never auto-delete damaged file
        }
        cache.put(phrase,clips);return clips;
    }
    private static boolean valid(int phrase){return phrase>=0&&phrase<=NON_COMMAND;}
    /** Defensive copies for the supervised classifier; includes optional NON_COMMAND data. */
    public synchronized List<float[]> samples(int phrase){
        List<float[]> result=new ArrayList<>();
        if(valid(phrase))for(float[] v:load(phrase))result.add(v.clone());
        return result;
    }
    public synchronized int count(int phrase){return valid(phrase)?load(phrase).size():0;}
    public synchronized boolean clear(int phrase){
        if(!valid(phrase))return false;
        boolean ok=!file(phrase).exists()||file(phrase).delete();
        if(ok)cache.remove(phrase);return ok;
    }
    public synchronized String add(int phrase,float[] embedding){
        if(!valid(phrase))return "无效口令";
        float[] u=unit(embedding);if(u==null||u.length!=DIM)return "神经音频特征无效，重新朗读";
        List<float[]> saved=new ArrayList<>(load(phrase));
        // Do not force same-room consistency: far-field and accent variants are
        // useful training data. Other-phrase confusion is checked at inference.
        if(saved.size()>=MAX_TAKES)saved.remove(0);
        saved.add(u);
        if(!write(phrase,saved))return "本地存储失败";
        cache.put(phrase,saved);return null;
    }
    private boolean write(int phrase,List<float[]> takes){
        if(!directory.isDirectory()&&!directory.mkdirs())return false;
        File target=file(phrase),tmp=new File(directory,"phrase_"+phrase+".tmp");
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(DIM);out.writeInt(takes.size());
            for(float[] v:takes)for(float x:v)out.writeShort(Math.max(-32767,Math.min(32767,Math.round(x*32767f))));
        }catch(IOException ex){tmp.delete();return false;}
        try{
            try{java.nio.file.Files.move(tmp.toPath(),target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }catch(java.nio.file.AtomicMoveNotSupportedException unsupported){
                java.nio.file.Files.move(tmp.toPath(),target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        }catch(IOException ex){tmp.delete();return false;}
    }
    public static float[] unit(float[] v){
        if(v==null||v.length!=DIM)return null;
        double norm=0;
        for(float x:v){if(!Float.isFinite(x))return null;norm+=(double)x*x;}
        if(norm<1e-10)return null;
        float[] copy=new float[v.length];double scale=1/Math.sqrt(norm);
        for(int i=0;i<v.length;i++)copy[i]=(float)(v[i]*scale);
        return copy;
    }
    public static float cosine(float[] a,float[] b){
        if(a==null||b==null||a.length!=b.length)return -1f;
        double sum=0;for(int i=0;i<a.length;i++)sum+=(double)a[i]*b[i];
        return (float)sum;
    }
    private static float personalGate(List<float[]> takes){
        // Leave-one-out positive score calibration. Clean examples should not
        // define an unrealistically strict threshold for real-room speech.
        // A floor avoids classifying unrelated speech solely by nearest match.
        float lowest=1f;
        for(int i=0;i<takes.size();i++){
            float a=-2,b=-2,c=-2;
            for(int j=0;j<takes.size();j++)if(i!=j){
                float score=cosine(takes.get(i),takes.get(j));
                if(score>a){c=b;b=a;a=score;}
                else if(score>b){c=b;b=score;}
                else if(score>c)c=score;
            }
            if(c>-1f)lowest=Math.min(lowest,(a+b+c)/3f);
        }
        float offset=takes.size()>=RECOMMENDED_TAKES?.065f:.045f;
        return Math.max(.72f,Math.min(.90f,lowest-offset));
    }
    public synchronized Result evaluate(float[] input){
        float[] sample=unit(input);
        if(sample==null)return new Result(-1,-1,0,0,1,0,false,"神经特征无效");
        int best=-1,runner=-1;
        float high=-2,second=-2;
        for(int p=0;p<CommandPhrases.count();p++){
            List<float[]> take=load(p);
            if(take.size()<MIN_TAKES)continue;
            // Mean of 3 best takes instead of a one-shot nearest neighbour.
            float a=-2,b=-2,c=-2;
            for(float[] v:take){float s=cosine(v,sample);
                if(s>a){c=b;b=a;a=s;}else if(s>b){c=b;b=s;}else if(s>c)c=s;}
            float score=(a+b+c)/3f;
            if(score>high){second=high;runner=best;high=score;best=p;}
            else if(score>second){second=score;runner=p;}
        }
        if(best<0)return new Result(-1,-1,0,0,1,0,false,"至少录入6条同口令样本才启用个人模型");
        int n=load(best).size();
        // Positive-only samples cannot calibrate impostors. Require a high
        // absolute similarity PLUS a substantial margin over other phrases.
        // Thresholds are conservative defaults, not claimed calibrated FAR.
        float gate=personalGate(load(best));
        float margin=runner<0?1f:high-second;
        boolean pass=high>=gate && margin>=(runner<0?0f:.07f);
        String reason=pass?"openWakeWord 深层语音特征匹配":
            high<gate?"个人神经相似度不足":"与另一条口令过于相似";
        return new Result(best,runner,high,second,gate,n,pass,reason);
    }
}
