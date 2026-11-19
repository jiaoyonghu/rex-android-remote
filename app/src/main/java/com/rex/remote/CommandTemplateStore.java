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
import java.util.List;

/**
 * Locally learned speech-command acoustic templates. Records NO RAW AUDIO.
 * At least three isolated, self-labelled examples are required per phrase.
 * Conservative rejection: nearest template and supporting 2nd template must
 * agree, and an unrelated trained phrase must not have a close acoustic score.
 * These scores are uncalibrated and NEVER authorize a motor command.
 */
public final class CommandTemplateStore {
    public static final int MIN_TAKES=3,MAX_TAKES=5;
    private static final String PREF="rex_phrase_templates_v7";
    private final SharedPreferences prefs;
    public static final class Result {
        public final int index;
        public final float distance;
        public final float competitor;
        public final String reason;
        Result(int idx,float d,float other,String why){index=idx;distance=d;competitor=other;reason=why;}
        public boolean accepted(){return index>=0;}
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
        return e.commit();
    }
    /** One training take; never require ASR transcription to match the prompt. */
    public synchronized String add(int phrase,short[] pcm){
        if(phrase<0||phrase>=CommandPhrases.count())return "无效提示词";
        int n=count(phrase);
        if(n>=MAX_TAKES)return "本条已采满 5 次，可清除后重录";
        float[][] data=CommandAcoustics.features(pcm);
        if(data==null)return "录音过短、过长或声音太小，请靠近手机再说一次";
        try{
            // Reject only egregious mismatches; language/phrase is supplied by the
            // user, so an acoustic model cannot truthfully verify it on its own.
            if(n>=2){
                float nearest=Float.POSITIVE_INFINITY;
                for(int i=0;i<n;i++)nearest=Math.min(nearest,
                    CommandAcoustics.distance(data,decode(prefs.getString(key(phrase,i),""))));
                if(nearest>1.7f)return "这次声音与前两次差异过大，请重读同一句";
            }
            String encoded=encode(data);
            if(!prefs.edit().putString(key(phrase,n),encoded).commit())return "本地保存失败，请重试";
            return null;
        }catch(Exception e){return "声学特征写入失败，请重新采集";}
    }
    public synchronized Result match(short[] pcm){
        float[][] candidate=CommandAcoustics.features(pcm);
        if(candidate==null)return new Result(-1,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,"无有效语音片段");
        return matchFeatures(candidate);
    }
    public synchronized Result matchFeatures(float[][] candidate){
        int bestPhrase=-1;
        float best=Float.POSITIVE_INFINITY,backing=Float.POSITIVE_INFINITY;
        float nearestOther=Float.POSITIVE_INFINITY;
        float winnerAllowed=.25f;
        for(int i=0;i<CommandPhrases.count();i++){
            if(count(i)<MIN_TAKES)continue;
            float first=Float.POSITIVE_INFINITY,second=Float.POSITIVE_INFINITY;
            List<float[][]> samples=new ArrayList<>();
            for(int j=0;j<MAX_TAKES;j++){
                String raw=prefs.getString(key(i,j),"");if(raw==null||raw.isEmpty())continue;
                float[][] saved=decode(raw);if(saved==null)continue;
                samples.add(saved);
                float d=CommandAcoustics.distance(candidate,saved);
                if(d<first){second=first;first=d;}
                else if(d<second)second=d;
            }
            if(samples.size()<MIN_TAKES)continue;
            // Each phrase learns a small acceptance radius from its own takes.
            // Quiet/consistent recordings get a tighter bound; variable takes
            // cannot widen it without limit.
            float spread=0;
            for(int a=0;a<samples.size();a++)for(int b=a+1;b<samples.size();b++){
                float d=CommandAcoustics.distance(samples.get(a),samples.get(b));
                if(Float.isFinite(d))spread=Math.max(spread,d);
            }
            float allowed=Math.max(.24f,Math.min(.78f,spread*1.22f+.14f));
            float support=Math.max(first,second);
            if(support<best){
                nearestOther=Math.min(nearestOther,best);
                best=support;backing=first;bestPhrase=i;winnerAllowed=allowed;
            }else nearestOther=Math.min(nearestOther,support);
        }
        if(bestPhrase<0)return new Result(-1,best,nearestOther,"尚无完成至少三次训练的口令");
        // Empirically conservative initial cutoff, to be calibrated with a user's
        // positive/negative recordings. Never silently lower it just to force a hit.
        if(best>winnerAllowed || backing>winnerAllowed*.90f)
            return new Result(-1,best,nearestOther,"声学相似度不足，使用原离线识别结果");
        if(Float.isFinite(nearestOther) && nearestOther-best<.20f)
            return new Result(-1,best,nearestOther,"与另一条口令太相似，拒绝猜测");
        return new Result(bestPhrase,best,nearestOther,"个性化模板高置信命中（仅界面预览）");
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
