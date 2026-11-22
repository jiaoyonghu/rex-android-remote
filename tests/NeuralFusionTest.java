package com.rex.remote;

/** No KWS: independent template/MLP fallback, exact phrase voting and safety. */
public final class NeuralFusionTest {
    private static void ok(boolean x,String message){if(!x)throw new AssertionError(message);}
    private static PersonalNeuralStore.Result template(int p,float score,boolean accepted){
        return new PersonalNeuralStore.Result(p,-1,score,0,.75f,12,accepted,"test");
    }
    private static PersonalWakeHead.Result head(int p,float score,boolean accepted){
        return new PersonalWakeHead.Result(p,-1,12,score,0,accepted,"test");
    }
    public static void main(String[] args){
        int wake=0; // CommandPhrases index 0 is Rex
        int forward=6;
        NeuralFusion.Config c=NeuralFusion.defaults();
        ok(NeuralFusion.evaluate(false,template(wake,.81f,true),null,c).wake,
            "template-only wake must work without KWS");
        ok(NeuralFusion.evaluate(false,null,head(wake,.72f,true),c).wake,
            "MLP-only wake must rescue missed template");
        ok(!NeuralFusion.evaluate(false,template(forward,.95f,true),null,c).pass,
            "no motion before wake");
        ok(NeuralFusion.evaluate(true,template(forward,.80f,true),head(forward,.85f,true),c).pass,
            "same-phrase evidence combines");
        ok(!NeuralFusion.evaluate(true,template(forward,.95f,true),head(wake,.96f,true),c).pass,
            "conflicting phrases cannot be weighted together");
        ok(!NeuralFusion.evaluate(true,null,null,c).pass,"no evidence cannot win");
        NeuralFusion.Config zero=new NeuralFusion.Config(.44f,.56f,.9f,.9f,.35f,
            NeuralFusion.Config.MODE_ZERO_BELOW);
        ok(!NeuralFusion.evaluate(false,template(wake,.80f,true),head(wake,.75f,true),zero).pass,
            "zero below configured thresholds");
        ok(NeuralFusion.evaluate(false,template(wake,.80f,true),head(wake,.75f,true),c).pass,
            "continuous mode uses valid evidence");
        ok(!NeuralFusion.evaluate(false,template(wake,.90f,false),null,c).pass,
            "internal template validity never bypassed");
        ok(!NeuralFusion.evaluate(false,null,head(wake,.90f,false),c).pass,
            "internal head validity never bypassed");
        NeuralFusion.Config round=NeuralFusion.Config.decode(c.encode());
        ok(round.mode==c.mode&&round.totalThreshold==c.totalThreshold,"saved config round trip");
        ok(NeuralFusion.Config.decode("bad").mode==NeuralFusion.Config.MODE_CONTINUOUS,
            "bad config reverts to defaults");
        System.out.println("PASS: v1.5 neural fusion / safety / prefs");
    }
}
