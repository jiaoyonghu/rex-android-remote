package com.rex.remote;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores ONLY speaker embeddings in the application's private preferences.
 * No raw microphone recordings are written. Enrollment and verification are
 * uncalibrated prototypes: replay and synthesized speech can spoof them.
 */
public final class SpeakerProfile {
    private static final String PREF="rex_owner_voice_private_v1";
    private static final String VECTOR="owner_embedding";
    public static final int MIN_SAMPLES=3;
    public static final int TARGET_SAMPLES=5;
    // Prototype-only heuristic, deliberately NEVER used as motor authorization.
    private static final float TEST_THRESHOLD=.72f;
    private final SharedPreferences prefs;
    private final List<float[]> samples=new ArrayList<>();
    private boolean enrolling;

    public SpeakerProfile(Context ctx){prefs=ctx.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    public synchronized boolean hasProfile(){return load()!=null;}
    public synchronized void begin(){samples.clear();enrolling=true;}
    public synchronized boolean isEnrolling(){return enrolling;}
    public synchronized int sampleCount(){return samples.size();}
    public synchronized boolean add(float[] v) {
        if(!enrolling || samples.size()>=8) return false;
        float[] u=SpeakerMath.unit(v);
        if(u==null) return false;
        if(!samples.isEmpty() && u.length!=samples.get(0).length) return false;
        samples.add(u); return true;
    }
    public synchronized boolean finish() {
        if(!enrolling || samples.size()<MIN_SAMPLES) return false;
        float[] avg=SpeakerMath.centroid(samples);
        if(avg==null)return false;
        JSONArray js=new JSONArray();
        try {
            for (float x : avg) js.put((double) x);
        } catch (org.json.JSONException e) {
            return false;
        }
        boolean saved=prefs.edit().putString(VECTOR,js.toString()).commit();
        if(saved){samples.clear();enrolling=false;}
        return saved;
    }
    public synchronized void clear() {
        samples.clear(); enrolling=false; prefs.edit().remove(VECTOR).commit();
    }
    public synchronized void abandon() {samples.clear();enrolling=false;}
    public synchronized float score(float[] candidate) {
        float[] enrolled=load();
        if(enrolled==null)return -1;
        return SpeakerMath.cosine(enrolled,candidate);
    }
    public synchronized boolean matches(float[] candidate) {
        return score(candidate)>=TEST_THRESHOLD;
    }
    private float[] load() {
        String text=prefs.getString(VECTOR,"");
        if(text==null || text.isEmpty()) return null;
        try {
            JSONArray a=new JSONArray(text);
            if(a.length()<64 || a.length()>512)return null;
            float[] v=new float[a.length()];
            for(int i=0;i<v.length;i++)v[i]=(float)a.getDouble(i);
            return SpeakerMath.unit(v);
        }catch(Exception ignored){return null;}
    }
}
