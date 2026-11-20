package com.rex.remote;

/** JVM-only deterministic sanity tests; not a substitute for spoken-word field testing. */
public final class CommandAcousticsTest {
    static void require(boolean yes,String why){if(!yes)throw new AssertionError(why);}
    static short[] synthetic(int frequency,double time,double strength){
        int n=(int)(time*16000);short[] x=new short[n];
        for(int i=0;i<n;i++){
            double t=i/16000.0;
            double mod=(.7+.3*Math.cos(2*Math.PI*2.2*t));
            double signal=Math.sin(2*Math.PI*frequency*t)*mod
                +.3*Math.sin(2*Math.PI*(frequency*2.03)*t);
            x[i]=(short)(signal*strength*15000);
        }
        return x;
    }
    public static void main(String[]args){
        require(CommandPhrases.count()==25,"exact phrase count changed");
        for(int i=0;i<CommandPhrases.count();i++)
            require(CommandPhrases.command(i)!=null,"unsupported phrase: "+CommandPhrases.at(i));
        require(RexCommand.parse("move forward")==null,"invented alias");
        require(RexCommand.parse("turn around")==RexCommand.TURN_AROUND,"English whitelisted command");
        require(RexCommand.parse("灭建国")==RexCommand.WAKE,"Chinese wake word");
        float[][] low=CommandAcoustics.features(synthetic(210,.95,1.0));
        float[][] same=CommandAcoustics.features(synthetic(210,.95,.65));
        float[][] other=CommandAcoustics.features(synthetic(550,.95,1.0));
        require(low!=null && same!=null && other!=null,"no feature vectors");
        float near=CommandAcoustics.distance(low,same);
        float far=CommandAcoustics.distance(low,other);
        require(Float.isFinite(near)&&Float.isFinite(far),"nonfinite distance");
        require(near<far,"distance does not distinguish synthetic sounds "+near+" vs "+far);
        require(CommandAcoustics.features(new short[7000])==null,"silent audio accepted");
        CommandAudioSegmenter seg=new CommandAudioSegmenter();
        short[] frame=new short[800];short[] done=null;
        for(int j=0;j<6;j++)seg.accept(frame,frame.length,false);
        for(int j=0;j<8;j++)seg.accept(frame,frame.length,true);
        for(int j=0;j<9;j++){short[] r=seg.accept(frame,frame.length,false);if(r!=null)done=r;}
        require(done!=null && done.length>5000,"segmentation not completed");
        System.out.println("PASS: 25 whitelist phrases; MFCC/DTW; noise rejection; speech segmentation; near="+near+" far="+far);
    }
}
