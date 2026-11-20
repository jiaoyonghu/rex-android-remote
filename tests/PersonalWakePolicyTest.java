package com.rex.remote;
public final class PersonalWakePolicyTest {
 static void ok(boolean b,String msg){if(!b)throw new AssertionError(msg);}
 static CommandTemplateStore.Result match(int index,float d,float limit,float competing){
  return new CommandTemplateStore.Result(index,index,4,d,limit,competing,"test");
 }
 public static void main(String[] a){
  PersonalWakePolicy p=new PersonalWakePolicy();
  var strongRex=match(0,.10f,.40f,.60f);
  var first=p.evaluate(strongRex,5,8500,1000);
  ok(!first.found()&&first.awaitSecond,"first short wake requires second confirmation");
  var d=p.evaluate(strongRex,5,8500,2200);
  ok(d.found()&&d.twoShort,"two verified templates may wake preview UI");
  ok(!p.evaluate(strongRex,5,8500,10000).found(),"first after timeout not enough");
  ok(!p.evaluate(strongRex,5,8500,20000).found(),"expired candidate cannot trigger");
  d=p.evaluate(match(1,.12f,.45f,.50f),4,21000,21000);
  ok(d.found()&&!d.twoShort,"long wake candidate needs external speaker verification");
  ok(!p.evaluate(match(6,.07f,.4f,.5f),5,23000,23000).found(),"action cannot become wake");
  ok(!p.evaluate(strongRex,3,7000,23001).found(),"short rescue requires 4 or more takes");
  ok(!p.evaluate(match(0,.22f,.40f,.5f),5,7000,24000).found(),"weak match blocked");
  System.out.println("PASS long/safe two-hit short personalized wake recovery");
 }
}
