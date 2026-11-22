package com.rex.remote;

/** User thresholds control candidate routing and score handling; no hidden hard reject. */
public final class NeuralFusionTest {
    private static void ok(boolean x,String message){if(!x)throw new AssertionError(message);}
    private static void near(float got,float want,String why){
        ok(Math.abs(got-want)<.0002f,why+" got="+got+" expected="+want);
    }
    private static PersonalNeuralStore.Result template(int p,float score,boolean accepted){
        return new PersonalNeuralStore.Result(p,-1,score,0,.75f,12,accepted,"test");
    }
    private static PersonalWakeHead.Result head(int p,float score,boolean accepted){
        return new PersonalWakeHead.Result(p,-1,12,score,0,accepted,"test");
    }
    public static void main(String[] args){
        int wake=0,forward=6,sleep=3;
        NeuralFusion.Config c=NeuralFusion.defaults();
        ok(NeuralFusion.useTemplateFastPath(template(wake,.90f,false),c),
            "the USER gate, not the old dynamic/margin check, routes to one head");
        ok(!NeuralFusion.useTemplateFastPath(template(wake,.84f,true),c),
            "below USER gate must route to all trained heads");
        ok(!NeuralFusion.useTemplateFastPath(null,c),"no template routes to all heads");
        ok(!NeuralFusion.useTemplateFastPath(template(-1,.99f,true),c),
            "invalid phrase never routes to a head");
        ok(NeuralFusion.templateForHead(null,null,template(wake,.95f,false),null,true).phrase==wake,
            "high user-match retains template-only fallback when head is not trained");
        ok(NeuralFusion.templateForHead(null,null,template(wake,.84f,true),null,false)==null,
            "below user-match threshold with no heads yields no false fallback");
        ok(NeuralFusion.templateForHead(null,null,template(wake,.95f,false),
            head(wake,.70f,false),true).phrase==wake,
            "same phrase retains raw similarity despite internal accepted=false");
        ok(NeuralFusion.templateForHead(null,null,template(wake,.95f,true),
            head(forward,.70f,true),false)==null,
            "different phrase is NOT allowed to inherit another phrase template");
        ok(NeuralFusion.evaluate(false,template(wake,.90f,false),null,c).wake,
            "template-only fallback preserves wake for untrained head");
        ok(NeuralFusion.evaluate(false,null,head(wake,.72f,false),c).wake,
            "continuous mode can use head evidence despite the old .64 / runner gate");
        ok(!NeuralFusion.evaluate(false,template(forward,.95f,true),null,c).pass,
            "no action before wake");
        ok(NeuralFusion.evaluate(true,template(forward,.80f,false),head(forward,.85f,true),c).pass,
            "same-phrase raw evidence combines regardless of old accepted flags");
        ok(!NeuralFusion.evaluate(true,template(forward,.95f,true),head(wake,.96f,true),c).pass,
            "MUST NOT combine different phrase evidence");
        ok(!NeuralFusion.evaluate(true,null,null,c).pass,"no evidence cannot win");
        NeuralFusion.Config zero=new NeuralFusion.Config(.44f,.56f,.9f,.9f,.35f,
            NeuralFusion.Config.MODE_ZERO_BELOW);
        ok(!NeuralFusion.evaluate(false,template(wake,.80f,true),head(wake,.75f,true),zero).pass,
            "mode 2 zeros raw scores below USER thresholds");
        ok(NeuralFusion.evaluate(false,template(wake,.80f,false),head(wake,.75f,false),c).pass,
            "mode 1 weights both scores even if old internal booleans reject");
        // User's real screenshot: 0.954 rejected by hidden .07 runner gap.
        NeuralFusion.Config userCfg=new NeuralFusion.Config(.46f,.54f,.86f,.64f,.30f,
            NeuralFusion.Config.MODE_CONTINUOUS);
        PersonalNeuralStore.Result ambiguous=new PersonalNeuralStore.Result(
            forward,wake,.954f,.930f,.85f,20,false,"与另一条口令过于相似");
        ok(NeuralFusion.useTemplateFastPath(ambiguous,userCfg),
            ".954 >= .86 must use matching head even with tiny runner gap");
        NeuralFusion.Decision rescued=NeuralFusion.evaluate(true,ambiguous,
            head(forward,.789f,true),userCfg);
        ok(rescued.pass,"above screenshot thresholds should pass");
        near(rescued.templateScore,.954f,"never erase raw template .954");
        near(rescued.headScore,.789f,"raw MLP .789");
        near(rescued.total,.954f*.46f+.789f*.54f,"exact weighted scoring");
        // Below the template's OLD dynamic gate is not a hidden veto either.
        PersonalNeuralStore.Result low=new PersonalNeuralStore.Result(
            forward,wake,.70f,.69f,.94f,20,false,"个人神经相似度不足");
        ok(!NeuralFusion.useTemplateFastPath(low,userCfg),
            "below .86 must fall back to ALL-head search");
        NeuralFusion.Decision lowResult=NeuralFusion.evaluate(true,low,
            head(forward,.789f,false),userCfg);
        near(lowResult.templateScore,.70f,"continuous mode retains subthreshold raw template score");
        near(lowResult.headScore,.789f,"continuous mode retains raw head score");
        near(lowResult.total,.70f*.46f+.789f*.54f,"fallback composite calculation");
        NeuralFusion.Config zeroUser=new NeuralFusion.Config(.46f,.54f,.86f,.80f,.30f,
            NeuralFusion.Config.MODE_ZERO_BELOW);
        NeuralFusion.Decision z=NeuralFusion.evaluate(true,low,head(forward,.789f,false),zeroUser);
        near(z.templateScore,0f,"mode 2 zeros below .86 template");
        near(z.headScore,0f,"mode 2 zeros below .80 head");
        ok(!z.pass,"two zeroed scores cannot pass");
        ok(!NeuralFusion.evaluate(true,template(forward,.95f,true),head(sleep,.96f,true),c).pass,
            "cross-phrase blending remains prohibited");
        ok(!NeuralFusion.evaluate(true,template(22,.99f,true),head(22,.99f,true),c).pass,
            "spoken disable still manual-only");
        NeuralFusion.Config round=NeuralFusion.Config.decode(c.encode());
        ok(round.mode==c.mode&&round.totalThreshold==c.totalThreshold,"saved config round trip");
        ok(NeuralFusion.Config.decode("bad").mode==NeuralFusion.Config.MODE_CONTINUOUS,
            "bad config defaults");
        System.out.println("PASS: raw template -> user-gated routing -> same-phrase head -> weighted fusion");
    }
}
