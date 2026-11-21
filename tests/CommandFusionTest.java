package com.rex.remote;
public final class CommandFusionTest {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static PersonalNeuralStore.Result match(int phrase,float score,boolean accept){
        return new PersonalNeuralStore.Result(phrase,-1,score,0f,.72f,12,accept,"test");
    }
    public static void main(String[] args){
        CommandFusion.Config c=CommandFusion.defaults();
        check(Math.abs(c.neuralThreshold-.92f)<.0001,"neural default 0.92");
        check(Math.abs(c.kwsWeight+c.neuralWeight-1f)<.001,"weight normalization");
        check(Math.abs(CommandFusion.Config.decode(c.encode()).totalThreshold-c.totalThreshold)<.001,
                "save/load round trip");
        check(CommandFusion.Config.decode("NaN,1,1,1").encode().equals(c.encode()),
                "invalid saved value fail-safe");
        check(CommandFusion.evaluate("Rex",false,false,null,c).wake,"KWS-only wake");
        check(!CommandFusion.evaluate("",false,false,null,c).pass,"noise must fail");
        check(CommandFusion.evaluate("",false,false,match(1,.96f,true),c).wake,
                "trained neural wake can support KWS miss");
        check(!CommandFusion.evaluate("",false,false,match(1,.91f,true),c).pass,
                "neural below .92 yields zero");
        check(CommandFusion.evaluate("前进",true,false,match(6,.95f,true),c).pass,
                "aligned dual signal passes");
        check(!CommandFusion.evaluate("前进",true,false,match(10,.99f,true),c).pass,
                "conflicting motions never add");
        check(CommandFusion.evaluate("",true,false,match(6,.96f,true),c).pass,
                "personal can recover missing KWS after VAD");
        check(!CommandFusion.evaluate("退",true,false,null,c).pass,
                "one syllable remains blocked");
        check(!CommandFusion.evaluate("失能",true,false,match(22,.99f,true),c).pass,
                "voice disable still blocked");
        check(!CommandFusion.evaluate("前进",false,false,null,c).pass,"must wake first");
        check(!CommandFusion.evaluate("前进",true,true,null,c).pass,"KWS conflict blocked");
        CommandFusion.Config strict=new CommandFusion.Config(.15f,.85f,.92f,.8f);
        check(!CommandFusion.evaluate("前进",true,false,null,strict).pass,
                "changed weighted threshold affects result");
        check(CommandFusion.evaluate("前进",true,false,match(6,.99f,true),strict).pass,
                "saved weight decision correct");
        System.out.println("PASS weighted fusion scoring, mismatches, safety, defaults, persistence parse");
    }
}
