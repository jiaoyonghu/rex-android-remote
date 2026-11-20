package com.rex.remote;
/** v0.9: adversarial false-positive and evidence-consensus scenarios. */
public final class SpeechFusionTest {
 static void require(boolean x,String why){if(!x)throw new AssertionError(why);}
 static CommandTemplateStore.Result take(int i){
    return new CommandTemplateStore.Result(i,i,-1,.16f,.42f,
        Float.POSITIVE_INFINITY,"mock accepted template");
 }
 static SpeechFusion.Decision d(String z,String e,String gz,String ge,
                                CommandTemplateStore.Result t,boolean awake){
    return SpeechFusion.decide(z,e,gz,ge,t,awake);
 }
 public static void main(String[] args){
  require(d("前进","","前进","",null,true).command==RexCommand.FORWARD,
      "agreed regular and constrained Chinese should pass");
  require(d("","turn around","","turn around",null,true).command==RexCommand.TURN_AROUND,
      "English model is still used");
  require(d("后退","","退","",null,true).command==RexCommand.BACKWARD,
      "two aliases of SAME action are not conflicting");
  require(d("前进","","后退","",null,true).conflict,
      "conflicting actions must fail closed");
  require(d("前进","","前进","",null,false).command==null,
      "can't act without wake");
  require(d("Rex","","rex","",null,false).woke,
      "two source wake accepted");
  require(!d("","","灭建国","",null,false).woke,
      "grammar-only wake is not proof");
  require(d("Rex，前进","","rex","",take(6),false).command==RexCommand.FORWARD,
      "wake+action ASR with confirming template in one utterance");
  require(d("我喜欢前进这两个字","","前进","",take(6),true).command==null,
      "natural sentence must veto forced grammar/template classification");
  require(d("我现在要去超市","","前进","",take(6),true).command==null,
      "ordinary conversation must not act");
  require(d("","","前进","",take(6),true).command==RexCommand.FORWARD,
      "strong grammar and template can rescue blank ASR for longer commands");
  CommandTemplateStore.Result weak=new CommandTemplateStore.Result(
      6,6,-1,.49f,.78f,Float.POSITIVE_INFINITY,"weak but accepted by legacy threshold");
  require(d("","","前进","",weak,true).command==null,
      "weak legacy template must NOT rescue empty unrestricted ASR");
  require(d("未识别到口令","","","",take(6),true).command==null,
      "standalone acoustic template cannot act");
  require(d("","","","",take(6),true).command==null,
      "template-only clip must be rejected");
  require(d("","","后退","",null,true).command==null,
      "grammar-only clip must be rejected");
  require(d("","","后退","",take(6),true).conflict,
      "grammar-template conflict must be rejected");
  require(d("","","退","",take(11),true).command==null,
      "single syllable command cannot use grammar/template alone");
  require(d("退","","退","",take(11),true).command==RexCommand.BACKWARD,
      "threefold verification passes single syllable");
  require(d("退","","退","",null,true).command==null,
      "single syllable without personal training is denied");
  require(d("好停","","好停","",null,true).command==RexCommand.STOP,
      "long stop synonym remains possible");
  require(d("停","","停","",take(15),true).command==RexCommand.STOP,
      "threefold short stop works");
  require(d("前进","","rex","",null,true).command==null,
      "spurious wake-only grammar doesn't corroborate an action");
  require(d("前进","","rex","",take(6),true).command==RexCommand.FORWARD,
      "wake-only grammar neither blocks nor confirms an independently corroborated action");
  require(d("睡觉","","睡觉","",null,true).command==RexCommand.SLEEP,
      "sleep still works once awake");
  require(!SpeechFusion.hasAsrHypothesis("我想吃饭","","[unk]","",true),
      "normal speech should skip template matching completely");
  require(SpeechFusion.hasAsrHypothesis("","","前进","",true),
      "grammar hypothesis should allow optional template verification");
  require(VoskGrammar.chinese().contains("[unk]"),"unknown phrase escape still required");
  System.out.println("PASS: conservative fusion, stranger speech, noisy template, conflicts, wake, bilingual, short-risk commands");
 }
}
