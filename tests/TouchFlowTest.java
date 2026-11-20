import com.rex.remote.*;
import android.content.Context;
import android.view.MotionEvent;
class TouchFlowTest {
 static class Mock implements RexView.Actions {
   int openOwner,capture,complete,cancel,ir,trainOpen,trainSelect,trainCapture,trainClear,trainExit;
   RexCommand last; int index=-1;
   public void onSend(RexCommand c){ir++;last=c;}
   public void onStartVoice(){}
   public void onStopVoice(){}
   public void onEnrollVoice(){openOwner++;}
   public void onCaptureVoice(int step){capture++;index=step;}
   public void onCompleteVoice(int step){complete++;index=step;}
   public void onCancelVoiceEnrollment(){cancel++;}
   public void onOpenCommandTraining(){trainOpen++;}
   public void onSelectCommandPhrase(int i){trainSelect++;}
   public void onCaptureCommandPhrase(int i){trainCapture++;}
   public void onClearCommandPhrase(int i){trainClear++;}
   public void onFinishCommandTraining(){trainExit++;}
 }
 static void tap(RexView v,int x,int y){v.onTouchEvent(new MotionEvent(x,y,MotionEvent.ACTION_DOWN));v.onTouchEvent(new MotionEvent(x,y,MotionEvent.ACTION_UP));}
 static void trip(RexView v,int x){tap(v,x,550);tap(v,x,550);tap(v,x,550);}
 static void check(boolean x){if(!x)throw new AssertionError();}
 public static void main(String[]args){
   Mock a=new Mock();RexView v=new RexView(new Context(),a);
   trip(v,275);check(a.openOwner==1);
   // In owner page, open the distinct command-training page.
   tap(v,180,620);check(a.trainOpen==1);
   tap(v,180,420);check(a.trainCapture==1);
   tap(v,263,335);check(a.trainSelect==1);
   tap(v,270,488);check(a.trainExit==1&&a.openOwner==2);
   tap(v,95,407);check(a.capture==1&&a.index==0);
   v.onVoice("今天我要录入自己的声音",null,null,false,false,0f,"采集成功",false,"已验证",1,0,true,false);
   tap(v,275,406);check(a.complete==1&&a.index==0);
   trip(v,80);check(a.cancel==2);
   tap(v,180,542);check(a.ir==1&&a.last==RexCommand.TURN_AROUND);
   trip(v,275);check(a.openOwner==3);
   trip(v,275);check(a.cancel==3);
   trip(v,80); // switch from VOICE to MANUAL
   tap(v,180,542);check(a.ir==2&&a.last==RexCommand.TURN_AROUND);
   check(a.openOwner==3);
   System.out.println("PASS 4-page hotspots, command training, guided capture, manual buttons");
 }
}
