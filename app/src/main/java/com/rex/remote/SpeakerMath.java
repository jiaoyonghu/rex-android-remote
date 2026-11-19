package com.rex.remote;

/** Small, testable embedding math. Not spoof-resistant biometric security. */
public final class SpeakerMath {
    private SpeakerMath() {}
    public static float[] unit(float[] input) {
        if(input==null || input.length<64 || input.length>512) return null;
        double norm=0;
        for(float n:input) {
            if(!Float.isFinite(n)) return null;
            norm+=(double)n*n;
        }
        if(norm<.000001) return null;
        float[] out=new float[input.length];
        double scale=1.0/Math.sqrt(norm);
        for(int i=0;i<input.length;i++) out[i]=(float)(input[i]*scale);
        return out;
    }
    public static float cosine(float[] a,float[] b) {
        if(a==null || b==null || a.length!=b.length) return -1;
        double dot=0, aa=0, bb=0;
        for(int i=0;i<a.length;i++) {
            dot+=(double)a[i]*b[i]; aa+=(double)a[i]*a[i]; bb+=(double)b[i]*b[i];
        }
        if(aa<.000001 || bb<.000001) return -1;
        return (float)(dot/Math.sqrt(aa*bb));
    }
    public static float[] centroid(java.util.List<float[]> samples) {
        if(samples==null || samples.size()<3) return null;
        float[] first=unit(samples.get(0));
        if(first==null) return null;
        float[] mean=new float[first.length];
        for(float[] sample:samples) {
            float[] u=unit(sample);
            if(u==null || u.length!=mean.length) return null;
            for(int i=0;i<mean.length;i++)mean[i]+=u[i];
        }
        return unit(mean);
    }
}
