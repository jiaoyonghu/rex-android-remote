package com.rex.remote;
import java.util.Random;
/** Pure DSP sanity: attenuation and mild room-like noise are not a field WER claim. */
public final class FarFieldAcousticsTest {
 static short[] phrase(double gain,double noise){
  short[] s=new short[16000];Random r=new Random(44);
  for(int i=0;i<s.length;i++){
   double t=i/16000.;double tone=Math.sin(2*Math.PI*220*t)*(.6+.3*Math.cos(2*Math.PI*2.7*t));
   s[i]=(short)Math.max(-32000,Math.min(32000,(tone*gain+r.nextGaussian()*noise)*16000));
  }return s;
 }
 static void require(boolean x,String msg){if(!x)throw new AssertionError(msg);}
 public static void main(String[]a){
  float[][] near=CommandAcoustics.features(phrase(1,0));
  float[][] far=CommandAcoustics.features(phrase(.12,.006));
  float[][] unrelated=CommandAcoustics.features(CommandAcousticsTest.synthetic(510,.99,.7));
  require(near!=null&&far!=null,"distant quieter PCM must have acoustic features");
  float same=CommandAcoustics.distance(near,far),other=CommandAcoustics.distance(near,unrelated);
  require(same<other,"distance must favor same attenuated phrase: "+same+" vs "+other);
  System.out.println("PASS controlled attenuation and mild-noise acoustic comparison: near="+same+" other="+other);
 }
}
