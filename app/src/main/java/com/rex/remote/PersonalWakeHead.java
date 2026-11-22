package com.rex.remote;

import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * REx v1.4: a TRAINED 2-layer binary neural detection head per phrase on top of
 * the frozen openWakeWord TFLite embeddings. Supervised labels come from the
 * chosen command and from explicit non-command clips / different commands.
 * This is deliberately NOT the upstream large-scale synthetic openWakeWord
 * training pipeline; small personal heads can overfit and require field testing.
 * The old cosine-template pathway remains untouched as fallback.
 */
public final class PersonalWakeHead {
    private static final int MAGIC=0x52584E48, VERSION=1, HIDDEN=12;
    private static final int DIM=OpenWakeWordFeatures.DIM;
    public static final int MIN_POS=6, MIN_NEG=6;
    private final File dir;
    private final PersonalNeuralStore store;
    private final Map<Integer,Model> cache=new HashMap<>();

    private static final class Model {
        final float[] w1=new float[HIDDEN*DIM],b1=new float[HIDDEN];
        final float[] w2=new float[HIDDEN];
        float b2;
        int positives,negatives;
        float predict(float[] v){
            float z=b2;
            for(int h=0;h<HIDDEN;h++){
                int base=h*DIM;double a=b1[h];
                for(int i=0;i<DIM;i++)a+=w1[base+i]*v[i]*12.0;
                z+=w2[h]*(float)Math.tanh(a);
            }
            return sigmoid(z);
        }
    }
    public static final class Result {
        public final int phrase,runnerUp,trainedCount;
        public final float score,second;
        public final boolean accepted;
        public final String reason;
        Result(int p,int r,int n,float s,float second,boolean ok,String why){
            phrase=p;runnerUp=r;trainedCount=n;score=s;this.second=second;
            accepted=ok;reason=why;
        }
    }
    public PersonalWakeHead(Context ctx,PersonalNeuralStore store){
        this(new File(ctx.getFilesDir(),"rex_personal_heads_v14"),store);
    }
    PersonalWakeHead(File dir,PersonalNeuralStore store){this.dir=dir;this.store=store;}
    private File file(int p){return new File(dir,"phrase_"+p+".mlp");}
    private static float sigmoid(double x){
        if(x>25)return 1f;if(x< -25)return 0f;
        return (float)(1.0/(1.0+Math.exp(-x)));
    }
    public synchronized void forget(int phrase){
        cache.remove(phrase);
        File f=file(phrase);if(f.isFile())f.delete();
    }
    private Model load(int phrase){
        if(cache.containsKey(phrase))return cache.get(phrase);
        Model m=null;File f=file(phrase);
        if(f.isFile() && f.length()<500_000){
            try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(f)))){
                if(in.readInt()!=MAGIC||in.readInt()!=VERSION||in.readInt()!=DIM||in.readInt()!=HIDDEN)
                    throw new IOException("head version");
                Model x=new Model();x.positives=in.readInt();x.negatives=in.readInt();
                if(x.positives<MIN_POS || x.negatives<MIN_NEG ||
                   x.positives>PersonalNeuralStore.MAX_TAKES || x.negatives>PersonalNeuralStore.MAX_TAKES*CommandPhrases.count())
                    throw new IOException("head counts");
                for(int i=0;i<x.w1.length;i++)x.w1[i]=readFinite(in);
                for(int i=0;i<HIDDEN;i++)x.b1[i]=readFinite(in);
                for(int i=0;i<HIDDEN;i++)x.w2[i]=readFinite(in);
                x.b2=readFinite(in);
                if(in.read()!=-1)throw new IOException("trailing head bytes");
                m=x;
            }catch(IOException ignored){ /* preserve damaged disk file for diagnosis */ }
        }
        cache.put(phrase,m);return m;
    }
    private static float readFinite(DataInputStream in)throws IOException{
        float x=in.readFloat();if(!Float.isFinite(x)||Math.abs(x)>1e5)throw new IOException("bad weight");
        return x;
    }
    private boolean persist(int phrase,Model m){
        if(!dir.isDirectory()&&!dir.mkdirs())return false;
        File tmp=new File(dir,"phrase_"+phrase+".tmp");
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))){
            out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(DIM);out.writeInt(HIDDEN);
            out.writeInt(m.positives);out.writeInt(m.negatives);
            for(float v:m.w1)out.writeFloat(v);
            for(float v:m.b1)out.writeFloat(v);
            for(float v:m.w2)out.writeFloat(v);
            out.writeFloat(m.b2);
        }catch(IOException ex){tmp.delete();return false;}
        try{
            try{Files.move(tmp.toPath(),file(phrase).toPath(),StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException ex){
                Files.move(tmp.toPath(),file(phrase).toPath(),StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        }catch(IOException ex){tmp.delete();return false;}
    }
    public synchronized int trainedCount(){
        int n=0;for(int p=0;p<CommandPhrases.count();p++)if(load(p)!=null)n++;
        return n;
    }
    public synchronized boolean hasModel(int p){return p>=0&&p<CommandPhrases.count()&&load(p)!=null;}
    /** Must run on a background worker: trains both dense layers with cross entropy. */
    public String train(int phrase){return train(phrase,()->true);}
    /** Inference never waits for gradient descent; only a brief model swap is locked. */
    public String train(int phrase,java.util.function.BooleanSupplier current){
        if(phrase<0||phrase>=CommandPhrases.count())return "无效口令";
        List<float[]> pos=store.samples(phrase);
        List<float[]> neg=new ArrayList<>(store.samples(PersonalNeuralStore.NON_COMMAND));
        for(int p=0;p<CommandPhrases.count();p++)if(p!=phrase)neg.addAll(store.samples(p));
        if(pos.size()<MIN_POS || neg.size()<MIN_NEG)return "尚需至少6条本口令和6条非口令/其他口令，保留旧识别";
        // Balance classes. Other-phrase examples are true negatives for THIS head.
        Collections.shuffle(neg,new Random(1400+phrase));
        if(neg.size()>Math.max(24,pos.size()*3))
            neg=new ArrayList<>(neg.subList(0,Math.max(24,pos.size()*3)));
        ArrayList<float[]> xs=new ArrayList<>();ArrayList<Integer> ys=new ArrayList<>();
        for(float[] v:pos){xs.add(v);ys.add(1);}
        for(float[] v:neg){xs.add(v);ys.add(0);}
        Model m=new Model();m.positives=pos.size();m.negatives=neg.size();
        Random rnd=new Random(81234+phrase);
        for(int i=0;i<m.w1.length;i++)m.w1[i]=(rnd.nextFloat()-.5f)*.09f;
        for(int i=0;i<HIDDEN;i++)m.w2[i]=(rnd.nextFloat()-.5f)*.16f;
        int[] order=new int[xs.size()];for(int i=0;i<order.length;i++)order[i]=i;
        double[] h=new double[HIDDEN],g=new double[HIDDEN];
        for(int epoch=0;epoch<75;epoch++){
            for(int i=order.length-1;i>0;i--){int j=rnd.nextInt(i+1);int t=order[i];order[i]=order[j];order[j]=t;}
            float lr1=(float)(.008/(1+epoch*.015));
            float lr2=(float)(.05/(1+epoch*.015));
            for(int index:order){
                float[] x=xs.get(index);int y=ys.get(index);
                double z=m.b2;
                for(int k=0;k<HIDDEN;k++){
                    int base=k*DIM;double sum=m.b1[k];
                    for(int i=0;i<DIM;i++)sum+=m.w1[base+i]*x[i]*12.0;
                    h[k]=Math.tanh(sum);z+=m.w2[k]*h[k];
                }
                double error=y-sigmoid(z);
                for(int k=0;k<HIDDEN;k++)g[k]=error*m.w2[k]*(1-h[k]*h[k]);
                for(int k=0;k<HIDDEN;k++){
                    m.w2[k]+=(float)(lr2*error*h[k]);
                    m.b1[k]+=(float)(lr1*g[k]);
                    int base=k*DIM;double grad=lr1*g[k]*12.0;
                    for(int i=0;i<DIM;i++)m.w1[base+i]+=(float)(grad*x[i]);
                }
                m.b2+=(float)(lr2*error);
            }
        }
        // Reject nonfinite/degenerate models instead of overwriting a working head.
        double posMean=0,negMean=0;
        for(float[] v:pos)posMean+=m.predict(v);
        for(float[] v:neg)negMean+=m.predict(v);
        posMean/=pos.size();negMean/=neg.size();
        if(!Double.isFinite(posMean)||!Double.isFinite(negMean)||posMean<negMean+.12)
            return "训练未收敛；保留原识别和原模型";
        if(!current.getAsBoolean())return "旧训练已取消";
        synchronized(this){
            if(!current.getAsBoolean())return "旧训练已取消";
            if(!persist(phrase,m))return "训练模型保存失败，原模型保持不变";
            cache.put(phrase,m);
        }
        return "训练成功：正样本"+pos.size()+" / 负样本"+neg.size()+
            "；训练均值 "+String.format(Locale.ROOT,"%.2f/%.2f",posMean,negMean)+
            "（非独立验证准确率）";
    }
    /** Fast path: template supplies a phrase, so score ONLY its matching MLP.
     * No separate TFLite interpreter is created; weights are plain Java arrays.
     * In the absence of competing heads this is a one-vs-rest binary score.
     */
    public synchronized Result evaluatePhrase(float[] raw,int phrase){
        if(phrase<0||phrase>=CommandPhrases.count())return null;
        float[] x=PersonalNeuralStore.unit(raw);
        if(x==null)return null;
        Model m=load(phrase);if(m==null)return null;
        float score=m.predict(x);
        boolean pass=score>=.64f;
        return new Result(phrase,-1,m.positives,score,0f,pass,
            pass?"候选MLP已训练并通过内部门槛":"候选MLP分数低于内部0.64门槛");
    }
    public synchronized Result evaluate(float[] raw){
        float[] x=PersonalNeuralStore.unit(raw);
        if(x==null)return null;
        int best=-1,runner=-1,n=0;float high=-1f,second=-1f;
        for(int p=0;p<CommandPhrases.count();p++){
            Model m=load(p);if(m==null)continue;
            float s=m.predict(x);
            if(s>high){runner=best;second=high;best=p;high=s;n=m.positives;}
            else if(s>second){runner=p;second=s;}
        }
        if(best<0)return null;
        // A binary discriminator can be confidently wrong on unseen audio.
        // Do not claim speaker verification or calibrated probability.
        boolean accepted=high>=.64f && (runner<0||high-second>=.08f);
        return new Result(best,runner,n,high,second,accepted,
            accepted?"已训练神经分类头":high<.64f?"分类头分数偏低":"不同分类头结果接近");
    }
}
