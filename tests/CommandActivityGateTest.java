import com.rex.remote.CommandActivityGate;
import java.util.Random;
public final class CommandActivityGateTest {
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
 public static void main(String[] args){
  CommandActivityGate g=new CommandActivityGate();
  short[] samples=new short[800];
  Random random=new Random(42);
  for(int i=0;i<60;i++){
      for(int j=0;j<800;j++)samples[j]=(short)(random.nextGaussian()*6200);
      check(!g.accept(samples,800,false),"loud noise started recording");
  }
  for(int i=0;i<8;i++){
      for(int j=0;j<800;j++)samples[j]=(short)(4100*Math.sin(2*Math.PI*145*j/16000.));
      g.accept(samples,800,true);
  }
  check(g.accept(samples,800,true),"real voice detector must open gate");
  for(int i=0;i<8;i++)g.accept(samples,800,false);
  check(!g.accept(samples,800,false),"gate stays open on non-voice audio");
  System.out.println("PASS: sound energy alone does not create command clips");
 }
}
