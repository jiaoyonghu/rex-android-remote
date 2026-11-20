package com.rex.remote;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;

/** JVM-only fake preference store: sample persistence, 3-take activation, reject unrelated audio. */
public final class CommandTemplateStoreTest {
    static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    static class FakePrefs implements SharedPreferences {
        final Map<String,String> store=new HashMap<>();
        public String getString(String k,String d){return store.getOrDefault(k,d);}
        public boolean contains(String k){return store.containsKey(k);}
        public Editor edit(){return new FakeEditor();}
        class FakeEditor implements Editor{
            final Map<String,String> writes=new HashMap<>();
            final java.util.Set<String> deletes=new java.util.HashSet<>();
            public Editor putString(String k,String v){writes.put(k,v);return this;}
            public Editor remove(String k){deletes.add(k);return this;}
            public boolean commit(){for(String k:deletes)store.remove(k);store.putAll(writes);return true;}
        }
    }
    static class FakeContext extends Context{
        final FakePrefs prefs=new FakePrefs();
        @Override public SharedPreferences getSharedPreferences(String n,int mode){return prefs;}
    }
    static short[] tone(int hz,int length,float gain){
        short[] s=new short[length];
        for(int i=0;i<s.length;i++)s[i]=(short)(gain*14000*Math.sin(2*Math.PI*hz*i/16000.));
        return s;
    }
    public static void main(String[]args){
        CommandTemplateStore bank=new CommandTemplateStore(new FakeContext());
        check(bank.count(0)==0,"empty store");
        for(int i=0;i<3;i++){
            String err=bank.add(0,tone(210,16500,1f-i*.05f));
            check(err==null,"take "+i+" rejected: "+err);
            check(bank.count(0)==i+1,"take lost");
        }
        CommandTemplateStore.Result good=bank.match(tone(210,16500,.72f));
        check(good.index==0,"identical phrase not recognized: "+good.reason+" cost "+good.distance);
        CommandTemplateStore.Result bad=bank.match(tone(750,16500,.75f));
        check(!bad.accepted(),"unrelated speech-like tone falsely accepted: "+bad.distance);
        // Repeating the SAME phrase beyond 5 recordings must roll the oldest
        // rather than forcing the owner to erase all learned pronunciation.
        for(int i=0;i<3;i++){
            String error=bank.add(0,tone(210,16500,.85f-i*.02f));
            check(error==null,"repeated take rejected: "+error);
        }
        check(bank.count(0)==5,"five take limit changed");
        String renewed=bank.add(0,tone(210,16500,.83f));
        check(renewed==null,"sixth take should replace oldest: "+renewed);
        check(bank.count(0)==5,"rolling takes must remain five");
        check(bank.clear(0),"clear failed");
        check(bank.count(0)==0,"clear didn't delete");
        System.out.println("PASS: three-take acoustic enrollment, persistent template match, out-of-set reject, delete");
    }
}
