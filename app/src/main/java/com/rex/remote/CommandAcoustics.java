package com.rex.remote;

/**
 * Small, dependency-free log-mel / cepstral acoustic fingerprints and banded DTW.
 * This is personal template matching, NOT ASR-model training or speaker verification.
 * PCM: signed 16-bit, mono, 16 kHz; no audio is retained by this class.
 */
public final class CommandAcoustics {
    private CommandAcoustics(){}
    public static final int SAMPLE_RATE=16000;
    public static final int FEATURES=13;
    private static final int FFT=512, WINDOW=400, HOP=160, BANDS=24;
    private static final float[] HAMMING=new float[WINDOW];
    private static final float[][] MEL=new float[BANDS][FFT/2+1];
    static {
        for(int i=0;i<WINDOW;i++) HAMMING[i]=(float)(.54-.46*Math.cos(2*Math.PI*i/(WINDOW-1)));
        double low=2595.0*Math.log10(1+180.0/700),high=2595.0*Math.log10(1+7100.0/700);
        int[] bins=new int[BANDS+2];
        for(int j=0;j<bins.length;j++){
            double mel=low+(high-low)*j/(BANDS+1);
            double hz=700*(Math.pow(10,mel/2595.0)-1);
            bins[j]=Math.max(0,Math.min(FFT/2,(int)Math.floor((FFT+1)*hz/SAMPLE_RATE)));
        }
        for(int j=0;j<BANDS;j++){
            int a=bins[j],b=bins[j+1],c=bins[j+2];
            for(int k=a;k<b;k++)MEL[j][k]=(k-a)/(float)Math.max(1,b-a);
            for(int k=b;k<c;k++)MEL[j][k]=(c-k)/(float)Math.max(1,c-b);
        }
    }
    public static float[][] features(short[] pcm){
        if(pcm==null || pcm.length<3200 || pcm.length>SAMPLE_RATE*6)return null;
        // Trim leading/trailing silence without dropping consonants at the edges.
        int blocks=(pcm.length+159)/160;
        double[] energies=new double[blocks];double peak=0;
        for(int b=0;b<blocks;b++){
            int from=b*160,to=Math.min(pcm.length,from+160);double sum=0;
            for(int i=from;i<to;i++){double x=pcm[i]/32768.0;sum+=x*x;}
            energies[b]=Math.sqrt(sum/Math.max(1,to-from));
            peak=Math.max(peak,energies[b]);
        }
        if(peak<.0018)return null; // permit quieter distant speech; rejection still depends on phonetic match
        double boundary=Math.max(.0016,peak*.14);
        int first=0,last=blocks-1;
        while(first<blocks && energies[first]<boundary)first++;
        while(last>=first && energies[last]<boundary)last--;
        if(last<first)return null;
        int start=Math.max(0,(first-8)*160),end=Math.min(pcm.length,(last+9)*160);
        if(end-start<3600 || end-start>56000)return null;
        int count=(end-start-WINDOW)/HOP+1;
        if(count<12 || count>350)return null;
        float[][] result=new float[count][FEATURES];
        double[] re=new double[FFT],im=new double[FFT],filter=new double[BANDS];
        for(int f=0;f<count;f++){
            java.util.Arrays.fill(re,0);java.util.Arrays.fill(im,0);
            int offset=start+f*HOP;
            for(int j=0;j<WINDOW;j++){
                int idx=offset+j;
                float x=pcm[idx]/32768f;
                float prev=idx==0?0f:pcm[idx-1]/32768f;
                re[j]=(x-.95f*prev)*HAMMING[j];
            }
            fft(re,im);
            for(int m=0;m<BANDS;m++){
                double e=0;
                for(int k=0;k<=FFT/2;k++){
                    if(MEL[m][k]==0)continue;
                    e+=MEL[m][k]*(re[k]*re[k]+im[k]*im[k]);
                }
                filter[m]=Math.log(Math.max(1e-9,e));
            }
            for(int c=0;c<FEATURES;c++){
                double value=0;
                for(int m=0;m<BANDS;m++)
                    value+=filter[m]*Math.cos(Math.PI*(m+.5)*(c+1)/BANDS);
                result[f][c]=(float)(value/BANDS);
            }
        }
        // Partial cepstral mean subtraction reduces room coloration while
        // retaining enough spectral identity to reject different phonemes.
        for(int c=0;c<FEATURES;c++){
            double sum=0,variance=0;
            for(float[] frame:result)sum+=frame[c];
            double mean=sum/count;
            for(float[] frame:result){double v=frame[c]-mean;variance+=v*v;}
            float scale=(float)Math.max(.65,Math.sqrt(variance/count));
            for(float[] frame:result)frame[c]=(frame[c]-(float)(mean*.45))/scale;
        }
        return result;
    }
    private static void fft(double[] re,double[] im){
        int n=re.length;
        for(int i=1,j=0;i<n;i++){
            int bit=n>>1;for(; (j&bit)!=0;bit>>=1)j^=bit;j^=bit;
            if(i<j){double t=re[i];re[i]=re[j];re[j]=t;t=im[i];im[i]=im[j];im[j]=t;}
        }
        for(int len=2;len<=n;len<<=1){
            double angle=-2*Math.PI/len,wr0=Math.cos(angle),wi0=Math.sin(angle);
            for(int i=0;i<n;i+=len){
                double wr=1,wi=0;
                for(int j=0;j<len/2;j++){
                    int a=i+j,b=a+len/2;
                    double vr=re[b]*wr-im[b]*wi,vi=re[b]*wi+im[b]*wr;
                    re[b]=re[a]-vr;im[b]=im[a]-vi;
                    re[a]+=vr;im[a]+=vi;
                    double next=wr*wr0-wi*wi0;wi=wr*wi0+wi*wr0;wr=next;
                }
            }
        }
    }
    /** Cost is a dissimilarity: 0 is identical. */
    public static float distance(float[][] a,float[][] b){
        if(a==null||b==null||a.length<8||b.length<8)return Float.POSITIVE_INFINITY;
        int n=a.length,m=b.length;
        if(Math.max(n,m)>Math.min(n,m)*2.15f)return Float.POSITIVE_INFINITY;
        // Robust to phone distance/gain and room coloration, including legacy
        // v0.7 saved features: compensate most constant cepstral offset at
        // comparison time so old samples remain readable and useful.
        float[] bias=new float[FEATURES];
        for(int k=0;k<FEATURES;k++){
            double am=0,bm=0;
            for(float[] row:a)am+=row[k];
            for(float[] row:b)bm+=row[k];
            bias[k]=(float)((am/n-bm/m)*.30);
        }
        float[] previous=new float[m+1],next=new float[m+1];
        java.util.Arrays.fill(previous,Float.POSITIVE_INFINITY);previous[0]=0;
        for(int i=1;i<=n;i++){
            java.util.Arrays.fill(next,Float.POSITIVE_INFINITY);
            // Allow variable speaking rate, but reject impossible time alignment.
            int mid=Math.round(i*(m/(float)n));
            int radius=Math.max(12,(int)(Math.max(n,m)*.24));
            int lo=Math.max(1,mid-radius),hi=Math.min(m,mid+radius);
            for(int j=lo;j<=hi;j++){
                float d=0;
                for(int k=0;k<FEATURES;k++){
                    float diff=a[i-1][k]-b[j-1][k]-bias[k];d+=diff*diff;
                }
                d=(float)Math.sqrt(d/FEATURES);
                float prev=Math.min(previous[j-1],Math.min(previous[j],next[j-1]));
                next[j]=d+prev;
            }
            float[] tmp=previous;previous=next;next=tmp;
        }
        return previous[m]/Math.max(n,m);
    }
}
