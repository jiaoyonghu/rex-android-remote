package com.rex.remote;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

/** Voice preview, local speaker enrollment, and separate manual IR controls. */
public final class MainActivity extends Activity implements RexView.Actions {
    private static final int MIC_REQUEST=21;
    private NecIr ir;
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
                        +" 条提示词都已通过文本核对和声纹特征检查，已保存到本机。\n\n"
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
        ir=new NecIr(this);
        view=new RexView(this,this);
        view.setIrStatus(ir.status());
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
            view.setMessage("REx 双语 + 声纹模型启动中 · 首次解压三套内置模型");
        } catch(Exception e) {
            view.setMessage("语音启动失败："+e.getClass().getSimpleName());
        }
    }
    @Override public void onStopVoice() {
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
    private void openTraining(){sendTrainingAction(VoiceService.ACTION_TRAIN_BEGIN,0,true);}
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
            .setMessage("将删除『"+CommandPhrases.at(index)+"』的所有本地声学模板，不影响其他口令。")
            .setNegativeButton("取消",null)
            .setPositiveButton("清除",(d,w)->sendTrainingAction(VoiceService.ACTION_TRAIN_CLEAR,index,false))
            .show();
    }
    @Override public void onFinishCommandTraining(){
        sendTrainingAction(VoiceService.ACTION_TRAIN_END,0,false);
    }
    @Override public void onSend(RexCommand cmd) {
        if(cmd==RexCommand.SLEEP||cmd==RexCommand.WAKE)return;
        if(cmd==RexCommand.DISABLE||cmd==RexCommand.ACTIVE||cmd==RexCommand.STAND) {
            new AlertDialog.Builder(this).setTitle("确认一次红外发射")
                .setMessage("即将发射「"+cmd.label+"」。红外只能确认手机发射，无法确认机器人收到；失能时机器人可能倒下。")
                .setNegativeButton("取消",null)
                .setPositiveButton("发射",(dialog,which)->sendIr(cmd))
                .show();
        } else sendIr(cmd);
    }
    private void sendIr(RexCommand cmd) {
        try {
            ir.send(cmd);
            view.setMessage("手机已发射："+cmd.label+" · 0x"+Integer.toHexString(cmd.code).toUpperCase()
                +" · 不代表机器人已收到");
        } catch(Exception ex) {
            view.setMessage("未发射红外："+ex.getMessage());
            Toast.makeText(this,"红外不可用，检查 ColorOS 接口",Toast.LENGTH_LONG).show();
        }
    }
}
