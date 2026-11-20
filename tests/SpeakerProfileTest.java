package com.rex.remote;
import android.content.Context;
/** Checks personalized score and persistent calibrated threshold. */
public final class SpeakerProfileTest {
 static void ok(boolean c,String msg){if(!c)throw new AssertionError(msg);}
 static float[] vec(float shift){float[] v=new float[128];for(int i=0;i<v.length;i++)v[i]=(float)(Math.sin(i*.13)+Math.cos(i*.08)*.4+shift*Math.sin(i*.09));return v;}
 public static void main(String[]args){
  CommandTemplateStoreTest.FakeContext c=new CommandTemplateStoreTest.FakeContext();
  SpeakerProfile p=new SpeakerProfile(c);p.begin();
  for(int i=0;i<5;i++)ok(p.add(vec(.02f*i)),"add same voice take "+i);
  ok(p.finish(),"persist profile");
  SpeakerProfile.Match check=p.verify(vec(.06f));
  ok(check.calibrated&&check.verified,"calibrated verification");
  ok(check.threshold>=.54f&&check.threshold<=.83f,"threshold bounds");
  float[] other=new float[128];for(int i=0;i<other.length;i++)other[i]=-vec(0)[i];
  ok(!p.verify(other).verified,"different voice rejected");
  p.clear();ok(!p.hasProfile(),"clear");
  System.out.println("PASS personalized speaker threshold and dissimilar rejection");
 }
}
