import com.rex.remote.RexCommand;
import com.rex.remote.BilingualCommandRouter;

public final class CommandTest {
    private static void check(String s,RexCommand expected){
        RexCommand got=RexCommand.parse(s);
        if(got!=expected)throw new AssertionError("'"+s+"' got "+got+" expected "+expected);
    }
    private static void pair(String zh,String en,boolean awake,boolean woke,RexCommand cmd){
        BilingualCommandRouter.Match m=BilingualCommandRouter.resolve(zh,en,awake);
        if(m.woke!=woke||m.command!=cmd)throw new AssertionError(zh+" | "+en+" -> "+m.woke+"/"+m.command);
    }
    public static void main(String[]args){
        check("Rex",RexCommand.WAKE);check("Rex醒了吗",RexCommand.WAKE);check("灭建国",RexCommand.WAKE);
        check("睡觉",RexCommand.SLEEP);
        check("站立",RexCommand.STAND);check("立正",RexCommand.STAND);
        check("前进",RexCommand.FORWARD);check("疯狂水世界，启动",RexCommand.FORWARD);
        check("左移",RexCommand.LEFT);check("右移",RexCommand.RIGHT);
        check("后退",RexCommand.BACKWARD);check("退",RexCommand.BACKWARD);
        check("左转圈",RexCommand.TURN_LEFT);check("右转圈",RexCommand.TURN_RIGHT);
        check("好，停",RexCommand.STOP);check("停",RexCommand.STOP);
        check("闪身步",RexCommand.DODGE);check("转个圈",RexCommand.TURN_AROUND);
        check("turn around",RexCommand.TURN_AROUND);
        check("挂机",RexCommand.DISABLE);check("挂壁",RexCommand.DISABLE);
        check("disable",RexCommand.DISABLE);check("失能",RexCommand.DISABLE);
        check("激活",RexCommand.ACTIVE);check("active",RexCommand.ACTIVE);
        for(String forbidden:new String[]{"forward","move left","move right","stop","go back","turn left","turn right",
              "activate","attention","hold","dodge","stand","stand up","shift left","瑞克斯","雷克斯","停止","rexy","sleep"}) check(forbidden,null);
        if(!"前进".equals(RexCommand.stripWakePrefix("Rex，前进")))throw new AssertionError();
        if(RexCommand.stripWakePrefix("rexterity")!=null)throw new AssertionError();
        pair("前进","rex",false,true,RexCommand.FORWARD);
        pair("灭建国，站立","",false,true,RexCommand.STAND);
        pair("","rex turn around",false,true,RexCommand.TURN_AROUND);
        pair("左移","",false,false,null);
        pair("左移","",true,false,RexCommand.LEFT);
        pair("睡觉","",false,false,null);
        pair("睡觉","",true,false,RexCommand.SLEEP);
        pair("前进","forward",true,false,RexCommand.FORWARD);
        pair("今天天气好","turn left",true,false,null);
        pair("帮我前进一下","please move",true,false,null);
        System.out.println("PASS exact user vocabulary, permanent awake routing, bilingual approved aliases");
    }
}
