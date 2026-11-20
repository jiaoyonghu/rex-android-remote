package com.rex.remote;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/** Experimental CAM++ embeddings (v1.1). No replay defense or IR authorization. */
public final class SpeakerProfile {
    private static final String PREF="rex_owner_sherpa_campplus_v11"; // separate from incompatible Vosk x-vectors
    private static final String VECTOR="owner_embedding";
    private static final String THRESHOLD="owner_personal_threshold_v11";
    public static final int MIN_SAMPLES=3,TARGET_SAMPLES=5;
    private static final float OLD_THRESHOLD=.72f;
    private final SharedPreferences prefs;
    private final List<float[]> samples=new ArrayList<>();
    private boolean enrolling;
    public static final class Match {
        public final float similarity,threshold;
        public final boolean verified,calibrated;
        Match(float s,float t,boolean c){similarity=s;threshold=t;verified=s>=t;calibrated=c;}
    }
    public SpeakerProfile(Context ctx){prefs=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    public synchronized boolean hasProfile(){return load()!=null;}
    public synchronized void begin(){samples.clear();enrolling=true;}
    public synchronized boolean isEnrolling(){return enrolling;}
    public synchronized int sampleCount(){return samples.size();}
    public synchronized boolean add(float[] v){
        if(!enrolling || samples.size()>=TARGET_SAMPLES)return false;
        float[] u=SpeakerMath.unit(v);
        if(u==null)return false;
        if(!samples.isEmpty()){
            if(u.length!=samples.get(0).length)return false;
            // Verify internal consistency before accepting the next take.
            // Loose enough to tolerate different prompted sentences.
            float[] center=unitMean(samples);
            if(center==null || SpeakerMath.cosine(u,center)<.45f)return false;
        }
        samples.add(u);return true;
    }
    private static float[] unitMean(List<float[]> vectors) {
        if(vectors.isEmpty())return null;
        float[] sum=new float[vectors.get(0).length];
        for(float[] u:vectors)for(int j=0;j<sum.length;j++)sum[j]+=u[j];
        return SpeakerMath.unit(sum);
    }
    public synchronized boolean finish(){
        if(!enrolling || samples.size()<MIN_SAMPLES)return false;
        float[] avg=unitMean(samples);
        if(avg==null)return false;
        float lowest=1f;
        for(int i=0;i<samples.size();i++){
            List<float[]> others=new ArrayList<>(samples);
            others.remove(i);
            float[] center=unitMean(others);
            if(center==null)return false;
            lowest=Math.min(lowest,SpeakerMath.cosine(samples.get(i),center));
        }
        if(lowest<.50f)return false; // suspect mixed speakers / badly corrupted recording
        // Personalized lower-bound on genuine examples; no population impostor set.
        float threshold=Math.max(.54f,Math.min(.83f,lowest*.80f-.045f));
        JSONArray js=new JSONArray();
        try{for(float x:avg)js.put((double)x);}
        catch(org.json.JSONException ex){return false;}
        boolean saved=prefs.edit().putString(VECTOR,js.toString())
            .putString(THRESHOLD,Float.toString(threshold)).commit();
        if(saved){samples.clear();enrolling=false;}
        return saved;
    }
    public synchronized void clear(){
        samples.clear();enrolling=false;
        prefs.edit().remove(VECTOR).remove(THRESHOLD).commit();
    }
    public synchronized void abandon(){samples.clear();enrolling=false;}
    public synchronized Match verify(float[] candidate){
        float[] registered=load();
        float similarity=registered==null?-1f:SpeakerMath.cosine(registered,candidate);
        String t=prefs.getString(THRESHOLD,"");
        boolean calibrated=false;
        float threshold=OLD_THRESHOLD;
        try {
            float f=Float.parseFloat(t);
            if(Float.isFinite(f)&&f>=.54f&&f<=.83f){threshold=f;calibrated=true;}
        }catch(Exception ignored){}
        return new Match(similarity,threshold,calibrated);
    }
    public synchronized float score(float[] candidate){return verify(candidate).similarity;}
    public synchronized boolean matches(float[] candidate){return verify(candidate).verified;}
    private float[] load(){
        String text=prefs.getString(VECTOR,"");
        if(text==null || text.isEmpty())return null;
        try{
            JSONArray a=new JSONArray(text);
            if(a.length()<64 || a.length()>512)return null;
            float[] v=new float[a.length()];
            for(int i=0;i<v.length;i++)v[i]=(float)a.getDouble(i);
            return SpeakerMath.unit(v);
        }catch(Exception ignored){return null;}
    }
}
