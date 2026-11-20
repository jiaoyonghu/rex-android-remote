package com.rex.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Existing v0.7 takes remain available; every finished utterance is evaluated. */
public final class CommandTemplateStore {
    public static final int MIN_TAKES=3,MAX_TAKES=5;
    private static final String PREF="rex_phrase_templates_v7"; // no destructive migration
    private final SharedPreferences prefs;
    private final Map<Integer,Trained> cache=new HashMap<>();
    public static final class Result {
        public final int index,candidateIndex,competitorIndex;
        public final float distance,limit,competitor;
        public final String reason;
        Result(int accepted,int candidate,int other,float d,float threshold,float otherCost,String why){
            index=accepted;candidateIndex=candidate;competitorIndex=other;
            distance=d;limit=threshold;competitor=otherCost;reason=why;
        }
        public boolean accepted(){return index>=0;}
        public String diagnostic(){
            if(candidateIndex<0)return reason;
            String msg="模板「"+CommandPhrases.at(candidateIndex)+"」"
                +String.format(java.util.Locale.ROOT," %.2f/%.2f",distance,limit);
            if(competitorIndex>=0)
                msg+=" · 其他动作「"+CommandPhrases.at(competitorIndex)+"」"
                    +String.format(java.util.Locale.ROOT," %.2f",competitor);
            return msg+" · "+reason;
        }
    }
    private static final class Trained {
        final List<float[][]> takes;
        final float radius;
        Trained(List<float[][]> takes,float radius){this.takes=takes;this.radius=radius;}
    }
    private static final class Score {
        final int phrase;final float first,second,score,limit;
        Score(int phrase,float first,float second,float limit){
            this.phrase=phrase;this.first=first;this.second=second;
            this.score=first*.65f+second*.35f;this.limit=limit;
        }
    }
    public CommandTemplateStore(Context ctx){prefs=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    private String key(int phrase,int take){return "p"+phrase+"_"+take;}
    public synchronized int count(int phrase){
        if(phrase<0||phrase>=CommandPhrases.count())return 0;
        int n=0;
        for(int i=0;i<MAX_TAKES;i++)if(prefs.contains(key(phrase,i)))n++;
        return n;
    }
    public synchronized boolean clear(int phrase){
        if(phrase<0||phrase>=CommandPhrases.count())return false;
        SharedPreferences.Editor e=prefs.edit();
        for(int i=0;i<MAX_TAKES;i++)e.remove(key(phrase,i));
        boolean done=e.commit(); if(done)cache.remove(phrase);
        return done;
    }
    public synchronized String add(int phrase,short[] pcm){
        if(phrase<0||phrase>=CommandPhrases.count())return "无效提示词";
        int n=count(phrase);
        // At five takes, a new supervised recording replaces the oldest;
        // a single phrase can be re-recorded indefinitely without clearing.

        float[][] data=CommandAcoustics.features(pcm);
        if(data==null)return "音频过短、过长、噪声过大或太安静，请重新朗读";
        try{
            if(n>=2){
                Trained trained=load(phrase);
                float closest=Float.POSITIVE_INFINITY;
                float among=0;
                for(int a=0;a<trained.takes.size();a++){
                    closest=Math.min(closest,CommandAcoustics.distance(data,trained.takes.get(a)));
                    for(int b=a+1;b<trained.takes.size();b++)
                        among=Math.max(among,CommandAcoustics.distance(trained.takes.get(a),trained.takes.get(b)));
                }
                // Reject obvious mislabeled clips, not legitimate variations in distance.
                float maxAllowed=Math.max(.72f,Math.min(1.45f,among*1.7f+.20f));
                if(closest>maxAllowed)return "与本条已录样本差异过大（可能录错词或有噪声），请重录";
            }
            SharedPreferences.Editor editor=prefs.edit();
            if(n>=MAX_TAKES){
                for(int i=1;i<MAX_TAKES;i++)
                    editor.putString(key(phrase,i-1),prefs.getString(key(phrase,i),""));
                editor.putString(key(phrase,MAX_TAKES-1),encode(data));
            }else editor.putString(key(phrase,n),encode(data));
            if(!editor.commit())return "本地保存失败";
            cache.remove(phrase);
            return null;
        }catch(Exception ex){return "声学特征无法写入，请重录";}
    }
    private Trained load(int phrase){
        Trained existing=cache.get(phrase);if(existing!=null)return existing;
        List<float[][]> takes=new ArrayList<>();
        for(int i=0;i<MAX_TAKES;i++){
            float[][] clip=decode(prefs.getString(key(phrase,i),""));
            if(clip!=null)takes.add(clip);
        }
        float largestNearest=0;
        for(int a=0;a<takes.size();a++){
            float nearest=Float.POSITIVE_INFINITY;
            for(int b=0;b<takes.size();b++)if(a!=b)
                nearest=Math.min(nearest,CommandAcoustics.distance(takes.get(a),takes.get(b)));
            if(Float.isFinite(nearest))largestNearest=Math.max(largestNearest,nearest);
        }
        // Individual leave-one-out radius, bounded against mislabeled enrolments.
        float radius=Math.max(.22f,Math.min(.82f,largestNearest*1.22f+.16f));
        Trained trained=new Trained(takes,radius);cache.put(phrase,trained);return trained;
    }
    public synchronized Result match(short[] pcm){
        float[][] candidate=CommandAcoustics.features(pcm);
        if(candidate==null)return new Result(-1,-1,-1,Float.POSITIVE_INFINITY,0,
            Float.POSITIVE_INFINITY,"语音片段不够清晰，未做指令猜测");
        return matchFeatures(candidate);
    }
    public synchronized Result matchFeatures(float[][] candidate){
        if(candidate==null)return new Result(-1,-1,-1,Float.POSITIVE_INFINITY,0,
            Float.POSITIVE_INFINITY,"缺少音频特征");
        List<Score> ranked=new ArrayList<>();
        for(int p=0;p<CommandPhrases.count();p++){
            if(count(p)<MIN_TAKES)continue;
            Trained t=load(p);
            if(t.takes.size()<MIN_TAKES)continue;
            float first=Float.POSITIVE_INFINITY,second=Float.POSITIVE_INFINITY;
            for(float[][] saved:t.takes){
                float d=CommandAcoustics.distance(candidate,saved);
                if(d<first){second=first;first=d;}
                else if(d<second)second=d;
            }
            if(Float.isFinite(second))ranked.add(new Score(p,first,second,t.radius));
        }
        if(ranked.isEmpty())return new Result(-1,-1,-1,Float.POSITIVE_INFINITY,0,
            Float.POSITIVE_INFINITY,"没有至少完成三次采集的有效口令");
        ranked.sort((a,b)->Float.compare(a.score,b.score));
        Score winner=ranked.get(0),other=null;
        for(Score score:ranked){
            if(CommandPhrases.command(score.phrase)!=CommandPhrases.command(winner.phrase)){
                other=score;break;
            }
        }
        int otherIndex=other==null?-1:other.phrase;
        float otherScore=other==null?Float.POSITIVE_INFINITY:other.score;
        if(winner.first>winner.limit*.96f || winner.second>winner.limit*1.24f ||
            winner.score>winner.limit)
            return new Result(-1,winner.phrase,otherIndex,winner.score,winner.limit,otherScore,
                "个人阈值未通过，不能自动认作该口令");
        if(other!=null && otherScore-winner.score<Math.max(.10f,winner.score*.20f))
            return new Result(-1,winner.phrase,otherIndex,winner.score,winner.limit,otherScore,
                "两个不同动作的口令过于相似，拒绝猜测");
        return new Result(winner.phrase,winner.phrase,otherIndex,winner.score,winner.limit,otherScore,
            "个人样本辅助命中");
    }
    private static String encode(float[][] data)throws IOException{
        if(data.length>350)throw new IOException("too many frames");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)){
            out.writeShort(data.length);out.writeByte(CommandAcoustics.FEATURES);
            for(float[] frame:data)for(float v:frame){
                int q=Math.max(-127,Math.min(127,Math.round(v*24f)));
                out.writeByte(q);
            }
        }
        return Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP);
    }
    private static float[][] decode(String raw){
        if(raw==null||raw.isEmpty())return null;
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(
                Base64.decode(raw,Base64.DEFAULT)))){
            int frames=in.readUnsignedShort(),cols=in.readUnsignedByte();
            if(frames<12||frames>350||cols!=CommandAcoustics.FEATURES)return null;
            float[][] data=new float[frames][cols];
            for(int i=0;i<frames;i++)for(int j=0;j<cols;j++)data[i][j]=in.readByte()/24f;
            return data;
        }catch(Exception e){return null;}
    }
}
