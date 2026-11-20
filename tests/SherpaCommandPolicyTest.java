package com.rex.remote;

/** No native library needed: pure-Java acceptance, wake, conflict checks. */
public final class SherpaCommandPolicyTest {
    static void check(boolean b,String name){if(!b)throw new AssertionError(name);}
    static CommandTemplateStore.Result accepted(int i){
        return new CommandTemplateStore.Result(i,i,-1,.15f,.4f,
            Float.POSITIVE_INFINITY,"trained take");
    }
    static SherpaCommandPolicy.Decision decide(String p,boolean awake,
                                                CommandTemplateStore.Result r){
        return SherpaCommandPolicy.decide(p,awake,r,false);
    }
    public static void main(String[] args){
        check(decide("",true,accepted(6)).command==null,"template-only must never trigger");
        check(decide("Rex",false,null).wake,"wake KWS");
        check(decide("前进",false,accepted(6)).command==null,"must wake first");
        check(decide("Rex,前进",false,accepted(6)).command==RexCommand.FORWARD,
            "combined wake and action should not regress");
        check(decide("前进",true,null).command==RexCommand.FORWARD,"independent KWS");
        check(decide("前进",true,accepted(6)).command==RexCommand.FORWARD,"trained agreement");
        check(decide("前进",true,accepted(10)).command==null,"different action conflict");
        check(decide("退",true,accepted(11)).command==null,"one syllable reject");
        check(decide("停",true,accepted(15)).command==null,"one syllable reject");
        check(decide("好，停",true,null).command==RexCommand.STOP,"full stop phrase");
        check(decide("失能",true,null).command==null,"manual-only disable");
        check(SherpaCommandPolicy.decide("前进",true,null,true).command==null,
            "two KWS hits conflict");
        check(decide("turn around",true,null).command==RexCommand.TURN_AROUND,"English keyword");
        System.out.println("PASS: KWS requires explicit keyword, wake, anti-conflict and short-word protection");
    }
}
