package com.rex.remote;
import java.io.File;
import java.nio.file.Files;
import java.util.Random;
public final class PersonalWakeHeadTest {
    static void check(boolean a,String s){if(!a)throw new AssertionError(s);}
    static float[] vec(int seed){
        Random r=new Random(seed);float[] x=new float[OpenWakeWordFeatures.DIM];
        for(int i=0;i<x.length;i++)x[i]=r.nextFloat()-.5f;
        return PersonalNeuralStore.unit(x);
    }
    static float[] jitter(float[] v,int seed,float scale){
        Random r=new Random(seed);float[] x=v.clone();
        for(int i=0;i<x.length;i++)x[i]+=(r.nextFloat()-.5f)*scale;
        return PersonalNeuralStore.unit(x);
    }
    public static void main(String[] args)throws Exception {
        File root=Files.createTempDirectory("rex-heads").toFile();
        PersonalNeuralStore store=new PersonalNeuralStore(new File(root,"features"));
        PersonalWakeHead heads=new PersonalWakeHead(new File(root,"weights"),store);
        float[] wake=vec(500),forward=vec(700),ambient=vec(900);
        for(int i=0;i<16;i++){
            check(store.add(0,jitter(wake,i,.12f))==null,"positive add");
            check(store.add(6,jitter(forward,i+30,.12f))==null,"other phrase as negative");
            check(store.add(PersonalNeuralStore.NON_COMMAND,jitter(ambient,i+60,.12f))==null,"ambient negative");
        }
        check(!heads.hasModel(0),"no fake training before training");
        String report=heads.train(0);
        System.out.println(report);
        check(report.startsWith("训练成功"),"learned model must converge");
        check(heads.hasModel(0) && heads.trainedCount()==1,"trained head persisted");
        var positive=heads.evaluate(jitter(wake,987,.17f));
        var negative=heads.evaluate(jitter(ambient,988,.17f));
        check(positive!=null && positive.accepted && positive.phrase==0,
            "held-out positive detected: "+(positive==null?"null":positive.score));
        check(negative!=null && !negative.accepted,
            "held-out noncommand rejected: "+(negative==null?"null":negative.score));
        PersonalWakeHead reload=new PersonalWakeHead(new File(root,"weights"),store);
        check(reload.hasModel(0) && reload.evaluate(jitter(wake,987,.17f)).accepted,
            "reloaded trained weights work");
        heads.forget(0);
        check(!heads.hasModel(0),"clear learned weights");
        check(store.count(PersonalNeuralStore.NON_COMMAND)==16,"negative bank intact");
        System.out.println("PASS v1.4 trained MLP: supervised learning, negatives, persistence, fallback");
    }
}
