import com.rex.remote.EnrollmentPrompts;
public final class EnrollmentTest {
  static void ok(boolean c){if(!c)throw new AssertionError();}
  public static void main(String[] args){
    ok(EnrollmentPrompts.count()==5);
    for(int i=0;i<EnrollmentPrompts.count();i++){
      ok(EnrollmentPrompts.matches(i,EnrollmentPrompts.at(i),""));
      ok(!EnrollmentPrompts.matches(i,"今天我想让机器人站起来", ""));
    }
    ok(EnrollmentPrompts.matches(4,"","rex turn around active"));
    ok(!EnrollmentPrompts.matches(4,"","rex stop"));
    ok(!EnrollmentPrompts.matches(0,"左移",""));
    System.out.println("PASS all 5 phrase gates; mismatch refused");
  }
}
