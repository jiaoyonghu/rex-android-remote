package com.rex.remote;

/** Two independent matches for neural-only wake; not speaker authentication. */
public final class NeuralWakePolicy {
    private int pending=-1;
    private long time;
    public void clear(){pending=-1;time=0;}
    public boolean accept(int phrase,long now){
        if(phrase<0){clear();return false;}
        if(pending==phrase && now>=time && now-time<=4000){clear();return true;}
        pending=phrase;time=now;return false;
    }
}
