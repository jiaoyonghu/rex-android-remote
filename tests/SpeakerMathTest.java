import java.util.ArrayList;
import java.util.List;
import com.rex.remote.SpeakerMath;
public final class SpeakerMathTest {
 public static void main(String[]arg){
  float[] a=new float[128],b=new float[128],c=new float[128];
  for(int i=0;i<128;i++){a[i]=i-63;b[i]=i-63.1f;c[i]=64-i;}
  List<float[]> samples=new ArrayList<>();samples.add(a);samples.add(b);samples.add(a);
  float[] enrolled=SpeakerMath.centroid(samples);
  if(enrolled==null||SpeakerMath.cosine(enrolled,a)<.999f)throw new AssertionError("same speaker");
  if(SpeakerMath.cosine(enrolled,c)>-.95f)throw new AssertionError("other speaker");
  if(SpeakerMath.centroid(samples.subList(0,2))!=null)throw new AssertionError("enrollment minimum");
  System.out.println("PASS embedding normalization, centroid and cosine");
 }
}
