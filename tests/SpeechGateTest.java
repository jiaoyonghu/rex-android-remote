import com.rex.remote.SpeechActivityGate;
import java.util.Random;
public final class SpeechGateTest {
 static void check(boolean x){if(!x)throw new AssertionError();}
 public static void main(String[]args){
   SpeechActivityGate gate=new SpeechActivityGate();
   short[] frame=new short[800];
   for(int j=0;j<20;j++)check(gate.process(frame,800)==0);
   Random rand=new Random(12);
   for(int j=0;j<30;j++){
     for(int i=0;i<frame.length;i++)frame[i]=(short)(rand.nextGaussian()*600);
     gate.process(frame,800);
   }
   check(!gate.isActive());
   // Even sustained loud broadband noise should not pass pitch periodicity.
   for(int j=0;j<18;j++){
     for(int i=0;i<frame.length;i++)frame[i]=(short)(rand.nextGaussian()*3400);
     gate.process(frame,800);
   }
   check(!gate.isActive());
   for(int j=0;j<10;j++){
     for(int i=0;i<frame.length;i++)frame[i]=(short)(7000*Math.sin(2*Math.PI*160*(j*800+i)/16000.0));
     gate.process(frame,800);
   }
   check(gate.isActive());
   for(int j=0;j<20;j++){java.util.Arrays.fill(frame,(short)0);gate.process(frame,800);}
   check(!gate.isActive());
   System.out.println("PASS immediate speech-like gating and noise/silence suppression");
 }
}
