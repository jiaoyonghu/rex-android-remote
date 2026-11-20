package com.rex.remote;
import java.io.File;
import java.nio.file.Files;
import java.util.Random;
public final class PersonalNeuralStoreTest {
    static void check(boolean yes,String msg){if(!yes)throw new AssertionError(msg);}
    static float[] vector(int seed){
        float[] v=new float[OpenWakeWordFeatures.DIM];Random r=new Random(seed);
        for(int i=0;i<v.length;i++)v[i]=r.nextFloat()-.5f;
        return PersonalNeuralStore.unit(v);
    }
    static float[] addNoise(float[] base,int seed,float noise){
        float[] out=base.clone();Random r=new Random(seed);
        for(int i=0;i<out.length;i++)out[i]+=(r.nextFloat()-.5f)*noise;
        return PersonalNeuralStore.unit(out);
    }
    public static void main(String[] args)throws Exception{
        File dir=Files.createTempDirectory("rex-neural-test").toFile();
        PersonalNeuralStore store=new PersonalNeuralStore(dir);
        float[] wake=vector(123),forward=vector(456);
        for(int i=0;i<12;i++)check(store.add(1,addNoise(wake,i,.015f))==null,"wake sample store");
        for(int i=0;i<10;i++)check(store.add(6,addNoise(forward,i,.015f))==null,"action sample store");
        check(store.count(1)==12,"all wake takes preserved");
        check(store.count(6)==10,"action counts independent");
        var result=store.evaluate(addNoise(wake,90,.018f));
        check(result.accepted && result.phrase==1,"trained wake recognised");
        var wrong=store.evaluate(addNoise(forward,190,.018f));
        check(wrong.accepted && wrong.phrase==6,"trained action recognised");
        check(!store.evaluate(vector(999)).accepted,"unseen noise must not match");
        for(int i=12;i<23;i++)check(store.add(1,addNoise(wake,i,.015f))==null,"more training");
        check(store.count(1)==20,"rolling cap at 20 samples");
        PersonalNeuralStore again=new PersonalNeuralStore(dir);
        check(again.count(1)==20 && again.count(6)==10,"app-private persistence");
        check(again.clear(1) && again.count(1)==0 && again.count(6)==10,"per-phrase clear");
        check(again.add(0,new float[1536])!=null,"zero vector rejected");
        check(again.add(0,new float[1])!=null,"wrong neural shape rejected");
        NeuralWakePolicy policy=new NeuralWakePolicy();
        check(!policy.accept(1,100),"first wake not enough");
        check(policy.accept(1,250),"second wake confirms");
        check(!policy.accept(1,10000) && !policy.accept(1,15001),"expired wake denied");
        System.out.println("PASS personal deep-feature classifier, rolling 20 takes, storage & wake anti-noise");
    }
}
