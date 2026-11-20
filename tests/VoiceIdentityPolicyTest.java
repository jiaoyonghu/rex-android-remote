package com.rex.remote;
public final class VoiceIdentityPolicyTest {
 static void yes(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
 static SherpaCommandPolicy.Decision choose(String kw,boolean awake){
  return SherpaCommandPolicy.decide(kw,awake,null,false);
 }
 public static void main(String[] args){
  VoiceIdentityPolicy.Decision d=VoiceIdentityPolicy.decide(choose("Rex醒了吗",false),VoiceIdentityPolicy.Speaker.MATCH,false,false);
  yes(d.wake&&d.refreshSession,"long wake verifies session");
  d=VoiceIdentityPolicy.decide(choose("Rex",false),VoiceIdentityPolicy.Speaker.TOO_SHORT,false,false);
  yes(d.wake&&!d.refreshSession,"short wake does not verify owner");
  d=VoiceIdentityPolicy.decide(choose("前进",true),VoiceIdentityPolicy.Speaker.TOO_SHORT,false,false);
  yes(d.command==null,"no session blocks unverifiable action");
  d=VoiceIdentityPolicy.decide(choose("前进",true),VoiceIdentityPolicy.Speaker.TOO_SHORT,true,false);
  yes(d.command==RexCommand.FORWARD,"recent verified session supports short command preview");
  d=VoiceIdentityPolicy.decide(choose("前进",true),VoiceIdentityPolicy.Speaker.MISMATCH,true,false);
  yes(d.command==null,"mismatched live speaker vetoes session");
  d=VoiceIdentityPolicy.decide(choose("Rex醒了吗",false),VoiceIdentityPolicy.Speaker.TOO_SHORT,false,true);
  yes(!d.wake,"template-only rescue needs verified speaker");
  d=VoiceIdentityPolicy.decide(choose("Rex醒了吗",false),VoiceIdentityPolicy.Speaker.MATCH,false,true);
  yes(d.wake&&d.refreshSession,"trained wake rescue can open verified session");
  d=VoiceIdentityPolicy.decide(choose("睡觉",true),VoiceIdentityPolicy.Speaker.MISMATCH,false,false);
  yes(d.command==RexCommand.SLEEP,"UI-only sleep must remain accessible");
  d=VoiceIdentityPolicy.decide(choose("前进",true),VoiceIdentityPolicy.Speaker.NO_PROFILE,false,false);
  yes(d.command==RexCommand.FORWARD&&!d.refreshSession,"no enrolled profile stays preview-only");
  System.out.println("PASS speaker-aware wake and preview command session policy");
 }
}
