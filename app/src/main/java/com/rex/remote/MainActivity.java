package com.rex.remote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;
import android.widget.EditText;
import android.os.SystemClock;

/** Local speech recognition and foreground WiFi command transport. */
public final class MainActivity extends Activity implements RexView.Actions {
    private static final int MIC_REQUEST=21;
    private WifiLink wifi;
    private boolean foreground, wifiConnected, voiceAllowed;
    private long voiceAllowedAt;
    private int linkGeneration;
    private String linkState="未连接";
    private boolean bootWaiting;
    private boolean receiptPending; private long receiptId;
    private AlertDialog voiceConfirm;
    private String wifiKey(){return getSharedPreferences("rex_wifi",MODE_PRIVATE).getString("key","");}
    private void refreshWifi(){if(view!=null)view.setIrStatus("WiFi "+linkState+" · 语音"+(voiceAllowed?"ON":"OFF")+" · 点击设置");}
    private void disarmVoice(){voiceAllowed=false; if(voiceConfirm!=null){voiceConfirm.dismiss();voiceConfirm=null;} refreshWifi();}
    private void disconnectWifi(){++linkGeneration;wifiConnected=false;bootWaiting=false;if(receiptPending&&view!=null)view.setWifiReceipt("未收到回执：连接中断，执行结果未知");receiptPending=false;disarmVoice();if(wifi!=null){wifi.close();wifi=null;}linkState="未连接";refreshWifi();}
    private void connectWifi(){
        disconnectWifi();String key=wifiKey();
        if(!foreground||key.isEmpty()){linkState="点击配对";refreshWifi();return;}
        final int gen=linkGeneration;linkState="连接中";refreshWifi();
        wifi=new WifiLink(this,key,new WifiLink.Listener(){
            public void status(boolean connected,String state){runOnUiThread(()->{
                if(gen!=linkGeneration||!foreground)return;
                wifiConnected=connected;bootWaiting=connected&&state.equals("BOOT_WAIT");
                if(!connected&&receiptPending){view.setWifiReceipt("未收到回执：连接中断，执行结果未知");receiptPending=false;}
                linkState=bootWaiting?"等待现场确认":state.equals("READY")?"PPO就绪":state.equals("CONNECTED")?"已连接":state.equals("FAULT")?"故障锁存":state.equals("WAIT_GRAPH")?"等待控制器":state;
                if(!connected||state.equals("FAULT")||bootWaiting)disarmVoice();refreshWifi();
            });}
            public void reply(RexCommand cmd,String state){runOnUiThread(()->{
                if(gen!=linkGeneration||!foreground)return;
                receiptPending=false;
                String result=receiptLabel(state);
                view.setWifiReceipt((state.equals("BOOT_APPROVAL_EXPIRED")?"本机：":"NanoPC：")+cmd.label+" · "+result);
                view.setMessage(cmd.label+" · "+result);
            });}
        });
    }
    @Override public void onWifiSettings(){
        String[] items={"输入 / 更新配对密钥","开启本次语音控制","关闭语音控制并停止","重新连接"};
        new AlertDialog.Builder(this).setTitle("REx WiFi · 10.42.0.1").setItems(items,(d,i)->{
            if(i==0){
                EditText e=new EditText(this);e.setSingleLine(true);e.setHint("64位配对密钥");
                new AlertDialog.Builder(this).setTitle("机器人配对密钥").setView(e).setNegativeButton("取消",null).setPositiveButton("保存",(a,b)->{
                    String k=e.getText().toString().trim();try{WifiLink.decode(k);getSharedPreferences("rex_wifi",MODE_PRIVATE).edit().putString("key",k).apply();connectWifi();}
                    catch(IllegalArgumentException ex){view.setMessage("配对密钥格式错误");}
                }).show();
            }else if(i==1){
                if(!wifiConnected||bootWaiting){view.setMessage("先完成现场确认并启动控制器");return;}
                new AlertDialog.Builder(this).setTitle("开启本次语音控制？").setMessage("个人口令模型不是身份认证，可能误识别。仅在现场试验条件满足、App前台时允许方向及动作口令。激活、站立、失能仍需确认。物理急停必须可用。")
                .setNegativeButton("取消",null).setPositiveButton("开启",(a,b)->{if(foreground&&wifiConnected){voiceAllowed=true;voiceAllowedAt=SystemClock.elapsedRealtime();refreshWifi();}}).show();
            }else if(i==2){disarmVoice();sendWifi(RexCommand.STOP);}else connectWifi();
        }).show();
    }
    @Override protected void onResume(){super.onResume();foreground=true;connectWifi();}
    @Override protected void onPause(){foreground=false;disconnectWifi();super.onPause();}

    private RexView view;
    private boolean registered;
    private boolean enrollmentAfterPermission;
    private boolean trainingAfterPermission;
    private final BroadcastReceiver voiceEvents=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent i) {
            if (view==null) return;
            view.onVoice(i.getStringExtra(VoiceService.EXTRA_TEXT),
                i.getStringExtra(VoiceService.EXTRA_PARTIAL),
                i.getStringExtra(VoiceService.EXTRA_COMMAND),
                i.getBooleanExtra(VoiceService.EXTRA_WAKE,false),
                i.getBooleanExtra(VoiceService.EXTRA_AWAKE,false),
                i.getFloatExtra(VoiceService.EXTRA_LEVEL,0f),
                i.getStringExtra(VoiceService.EXTRA_STATUS),
                i.getBooleanExtra(VoiceService.EXTRA_SPEAKING,false),
                i.getStringExtra(VoiceService.EXTRA_OWNER),
                i.getIntExtra(VoiceService.EXTRA_ENROLL_COUNT,0),
                i.getIntExtra(VoiceService.EXTRA_ENROLL_STEP,0),
                i.getBooleanExtra(VoiceService.EXTRA_ENROLL_READY,false),
                i.getBooleanExtra(VoiceService.EXTRA_ENROLL_CAPTURING,false));
            view.onDiagnostics(
                i.getStringExtra(VoiceService.EXTRA_DIAG_ENGINE),
                i.getFloatExtra(VoiceService.EXTRA_DIAG_DBFS,-90f),
                i.getBooleanExtra(VoiceService.EXTRA_DIAG_ACTIVITY,false),
                i.getLongExtra(VoiceService.EXTRA_DIAG_FRAMES,0),
                i.getLongExtra(VoiceService.EXTRA_DIAG_DROPS,0),
                i.getStringExtra(VoiceService.EXTRA_DIAG_KWS),
                i.getStringExtra(VoiceService.EXTRA_DIAG_VAD),
                i.getStringExtra(VoiceService.EXTRA_DIAG_PERSONAL),
                i.getStringExtra(VoiceService.EXTRA_DIAG_VERDICT),
                i.getStringExtra(VoiceService.EXTRA_DIAG_CAUSE),
                i.getStringExtra(VoiceService.EXTRA_DIAG_TIMING),
                i.getLongExtra(VoiceService.EXTRA_DIAG_KWS_AT,0),
                i.getLongExtra(VoiceService.EXTRA_DIAG_VAD_AT,0),
                i.getLongExtra(VoiceService.EXTRA_DIAG_VERDICT_AT,0));
            String finalCommand=i.getStringExtra(VoiceService.EXTRA_COMMAND);
            long eventAt=i.getLongExtra("wifi_final_elapsed_ms",0);
            long utteranceAt=i.getLongExtra("wifi_utterance_elapsed_ms",0);
            if(finalCommand!=null && foreground && wifiConnected && voiceAllowed && utteranceAt>=voiceAllowedAt && eventAt>=utteranceAt && SystemClock.elapsedRealtime()-utteranceAt<=1500 && SystemClock.elapsedRealtime()-eventAt<=500 && SystemClock.elapsedRealtime()>=eventAt){
                try{
                    RexCommand c=RexCommand.valueOf(finalCommand);
                    if(c==RexCommand.SLEEP){disarmVoice();sendWifi(RexCommand.STOP);}
                    else if(RexCommand.isAction(c))onSend(c);
                }catch(IllegalArgumentException ignored){}
            }
            String trainEvent=i.getStringExtra(VoiceService.EXTRA_TRAIN_EVENT);
            view.onTraining(i.getIntExtra(VoiceService.EXTRA_TRAIN_INDEX,0),
                i.getIntExtra(VoiceService.EXTRA_TRAIN_COUNT,0),
                i.getStringExtra(VoiceService.EXTRA_TRAIN_INFO),trainEvent);
            if("TRAIN_FAIL".equals(trainEvent)){
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("指令采集失败")
                    .setMessage(i.getStringExtra(VoiceService.EXTRA_TRAIN_INFO))
                    .setPositiveButton("重新采集",null).show();
            }
            String enrollmentEvent=i.getStringExtra(VoiceService.EXTRA_ENROLL_EVENT);
            if("CAPTURE_FAIL".equals(enrollmentEvent)){
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("声纹采集失败")
                    .setMessage(i.getStringExtra(VoiceService.EXTRA_STATUS)
                        + "\n\n请点击『采集』重新朗读当前提示词。")
                    .setPositiveButton("明白，重试",null).show();
            }else if("ENROLL_DONE".equals(enrollmentEvent)){
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("声纹录入结束")
                    .setMessage("所有 "+EnrollmentPrompts.count()
                        +" 条提示句都已取得有效的 CAM++ 声纹特征，并通过内部一致性检查，已保存到本机。\n\n"
                        +"这是实验性声纹匹配，尚不能防录音重放，也不会授权机器人运动。")
                    .setPositiveButton("完成",null).show();
            }
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(0xFF08080D);
        getWindow().setNavigationBarColor(0xFF08080D);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view=new RexView(this,this);
        refreshWifi();
        setContentView(view);
    }
    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter=new IntentFilter(VoiceService.ACTION_EVENT);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(voiceEvents,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(voiceEvents,filter);
        registered=true;
    }
    @Override protected void onStop() {
        if(registered){ unregisterReceiver(voiceEvents);registered=false; }
        super.onStop();
    }
    @Override public void onStartVoice() {
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_REQUEST);
        } else startVoice();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=MIC_REQUEST) return;
        if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED){
            if(trainingAfterPermission){trainingAfterPermission=false;openTraining();}
            else if(enrollmentAfterPermission) {
                enrollmentAfterPermission=false;beginEnrollment();
            }else startVoice();
        }else{
            enrollmentAfterPermission=false;trainingAfterPermission=false;
            view.setMessage("麦克风权限未授权，语音监听未启动");
        }
    }
    private void startVoice() {
        try {
            startForegroundService(new Intent(this,VoiceService.class));
            view.setMessage("REx VAD + 神经匹配 + MLP 启动中 · 已关闭Zipformer");
        } catch(Exception e) {
            view.setMessage("语音启动失败："+e.getClass().getSimpleName());
        }
    }
    @Override public void onStopVoice() {
        if(voiceAllowed){disarmVoice();sendWifi(RexCommand.STOP);}
        stopService(new Intent(this,VoiceService.class));
        view.setVoiceStopped();
    }
    @Override public void onEnrollVoice(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            enrollmentAfterPermission=true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_REQUEST);
            return;
        }
        beginEnrollment();
    }
    private void beginEnrollment(){
        if(voiceAllowed){disarmVoice();sendWifi(RexCommand.STOP);}
        // Persisted owner data remains intact until ALL prompted phrases pass.
        sendEnrollAction(VoiceService.ACTION_ENROLL_BEGIN,0);
    }
    private void sendEnrollAction(String action,int step){
        try {
            Intent intent=new Intent(this,VoiceService.class).setAction(action);
            intent.putExtra(VoiceService.EXTRA_ENROLL_STEP,step);
            if(VoiceService.ACTION_ENROLL_BEGIN.equals(action))startForegroundService(intent);
            else startService(intent);
        }catch(Exception e){
            view.setMessage("声纹操作失败："+e.getClass().getSimpleName());
        }
    }
    @Override public void onCaptureVoice(int step){
        sendEnrollAction(VoiceService.ACTION_ENROLL_CAPTURE,step);
    }
    @Override public void onCompleteVoice(int step){
        sendEnrollAction(VoiceService.ACTION_ENROLL_NEXT,step);
    }
    @Override public void onCancelVoiceEnrollment(){
        sendEnrollAction(VoiceService.ACTION_ENROLL_CANCEL,0);
    }
    @Override public void onOpenCommandTraining(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            trainingAfterPermission=true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_REQUEST);
        }else openTraining();
    }
    private void openTraining(){if(voiceAllowed){disarmVoice();sendWifi(RexCommand.STOP);}sendTrainingAction(VoiceService.ACTION_TRAIN_BEGIN,0,true);}
    private void sendTrainingAction(String action,int phrase,boolean mayStart){
        try{
            Intent intent=new Intent(this,VoiceService.class).setAction(action);
            intent.putExtra(VoiceService.EXTRA_TRAIN_INDEX,phrase);
            if(mayStart)startForegroundService(intent);else startService(intent);
        }catch(Exception e){view.setMessage("指令训练操作失败："+e.getClass().getSimpleName());}
    }
    @Override public void onSelectCommandPhrase(int index){
        sendTrainingAction(VoiceService.ACTION_TRAIN_SELECT,index,false);
    }
    @Override public void onCaptureCommandPhrase(int index){
        sendTrainingAction(VoiceService.ACTION_TRAIN_CAPTURE,index,false);
    }
    @Override public void onClearCommandPhrase(int index){
        new AlertDialog.Builder(this).setTitle("清除这条口令的训练样本？")
            .setMessage("将删除『"+(index==PersonalNeuralStore.NON_COMMAND?"非口令/相似词":CommandPhrases.at(index))+"』的神经训练样本，不影响其他口令。")
            .setNegativeButton("取消",null)
            .setPositiveButton("清除",(d,w)->sendTrainingAction(VoiceService.ACTION_TRAIN_CLEAR,index,false))
            .show();
    }
    @Override public void onFinishCommandTraining(){
        sendTrainingAction(VoiceService.ACTION_TRAIN_END,0,false);
    }
    @Override public void onCopyDiagnostics(String report){
        try {
            ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard!=null){
                clipboard.setPrimaryClip(ClipData.newPlainText("REx语音诊断",report));
                Toast.makeText(this,"诊断已复制，可直接粘贴到聊天",Toast.LENGTH_SHORT).show();
            }
        }catch(Exception ex){
            view.setMessage("复制诊断失败："+ex.getClass().getSimpleName());
        }
    }
    @Override public void onResetDiagnostics(){
        if(!view.isVoiceStarted()){
            view.resetDiagnosticDisplay();
            return;
        }
        try {
            startService(new Intent(this,VoiceService.class)
                .setAction(VoiceService.ACTION_DIAG_RESET));
        }catch(Exception e){
            view.setMessage("诊断记录清理失败："+e.getClass().getSimpleName());
        }
    }
    @Override public boolean onSaveFusionSettings(NeuralFusion.Config settings){
        try{
            boolean ok=NeuralFusion.save(this,settings);
            if(ok){
                Toast.makeText(this,"参数已保存；下一段语音开始使用新权重",Toast.LENGTH_SHORT).show();
                view.setMessage("v1.6 · 已保存模板/MLP综合评分参数");
            }else{
                Toast.makeText(this,"保存失败：参数未生效",Toast.LENGTH_LONG).show();
            }
            return ok;
        }catch(RuntimeException ex){
            Toast.makeText(this,"参数无效，已取消保存",Toast.LENGTH_LONG).show();
            return false;
        }
    }
    @Override public void onSend(RexCommand cmd) {
        if(!RexCommand.isAction(cmd))return;
        if(!foreground||!wifiConnected){view.setMessage("WiFi 未连接 · 指令未发送");return;}
        if(cmd==RexCommand.STOP){if(voiceConfirm!=null){voiceConfirm.dismiss();voiceConfirm=null;}sendWifi(cmd);return;}
        if(voiceConfirm!=null)return;
        if(cmd==RexCommand.DISABLE||cmd==RexCommand.ACTIVE||cmd==RexCommand.STAND) {
            final int gen=linkGeneration;
            final boolean boot=bootWaiting&&cmd==RexCommand.ACTIVE;
            final long confirmAt=SystemClock.elapsedRealtime();
            voiceConfirm=new AlertDialog.Builder(this).setTitle(boot?"现场启动许可（不激活电机）":"确认 WiFi 指令")
                .setMessage(boot?"仅限机器人现场操作。确认防坠支架已固定、物理急停可用、人员远离运动区域、实机增益已验收。确认仅启动原控制服务，不会自动激活或站立；服务就绪后须重新发激活指令。":"即将请求「"+cmd.label+"」。确认现场试验条件；失能可能导致倒下。")
                .setNegativeButton("取消",null)
                .setPositiveButton("发送",(dialog,which)->{if(gen==linkGeneration&&foreground&&wifiConnected&&SystemClock.elapsedRealtime()-confirmAt<=10000&&boot== (bootWaiting&&cmd==RexCommand.ACTIVE))sendWifi(cmd,boot);else view.setMessage("确认已过期，请重新发送");})
                .create();
            voiceConfirm.setOnDismissListener(d->voiceConfirm=null);voiceConfirm.show();
        } else sendWifi(cmd);
    }
    private void sendWifi(RexCommand cmd){sendWifi(cmd,false);}
    private void sendWifi(RexCommand cmd,boolean boot){
        if(foreground&&wifi!=null&&wifi.submit(cmd,boot)){
            receiptPending=true;view.setWifiReceipt("等待 NanoPC 回执："+cmd.label);
            view.setMessage("WiFi 请求："+cmd.label);
            final int gen=linkGeneration;final long id=++receiptId;
            view.postDelayed(()->{if(gen==linkGeneration&&id==receiptId&&receiptPending){receiptPending=false;view.setWifiReceipt("未收到回执：执行结果未知");}},1500);
        }else view.setMessage("未发送：未连接或正在处理指令");
    }
    private static String receiptLabel(String s){
        switch(s){
            case "BOOT_START_REQUESTED":return "已收到 · 启动控制服务";
            case "BOOT_WAIT_NO_MOTION":return "已收到 · 尚未现场确认";
            case "BOOT_APPROVAL_EXPIRED":return "未发送 · 许可已过期";
            case "STATE_QUERY_SENT":return "已收到 · 状态请求已发";
            case "ZERO_REQUEST_SENT":return "已收到 · 零速请求已发";
            case "DISABLE_REQUEST_SENT_NOT_ACK":return "已收到 · 失能请求已发";
            case "WALK_PERMISSION_REQUESTED":return "已收到 · 等待行走许可";
            case "STARTED":return "已收到 · 动作调度开始";
            case "WAITING_FOR_GRAPH":return "已收到 · 控制器未就绪";
            case "REJECTED_NOT_PPO_RUN_OR_HEALTHY":return "已收到 · 未就绪，拒绝移动";
            case "BUSY_NO_QUEUE":return "已收到 · 忙，未排队";
            default:return "已收到 · "+s;
        }
    }
}
