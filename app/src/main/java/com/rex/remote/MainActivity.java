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
                i.getIntExtra(VoiceService.EXTRA_ENROLL_COUNT,0));
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
            if(enrollmentAfterPermission) {
                enrollmentAfterPermission=false;showEnrollmentMenu();
            }else startVoice();
        }else{
            enrollmentAfterPermission=false;
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
        showEnrollmentMenu();
    }
    private void sendEnrollAction(String action){
        try {
            Intent intent=new Intent(this,VoiceService.class).setAction(action);
            if(VoiceService.ACTION_ENROLL_BEGIN.equals(action))startForegroundService(intent);
            else startService(intent);
        }catch(Exception e){view.setMessage("声纹操作失败："+e.getClass().getSimpleName());}
    }
    private void showEnrollmentMenu(){
        new AlertDialog.Builder(this)
            .setTitle("REx 本机声纹录入 · 实验版")
            .setMessage("请在安静处依次说3～5段完整的话，每段尽量2～4秒，中间停顿，让REx分段采集。\n\n"
                + "推荐：\n① Rex醒了吗，疯狂水世界启动\n② 灭建国，站立，左移，右移\n"
                + "③ 转个圈，左转圈，右转圈\n④ 好，停，挂机，激活\n"
                + "⑤ Rex，turn around，disable，active\n\n"
                + "看到3段以上成功采集后，点『完成录入』。只保存特征，不保存音频；声纹不具备防录音重放能力。")
            .setItems(new String[]{"开始录入（清空本次暂存）", "完成录入（至少3段）", "取消本次录入", "删除手机内已保存声纹"},
                (dialog,which)->{
                    switch(which){
                        case 0: sendEnrollAction(VoiceService.ACTION_ENROLL_BEGIN);break;
                        case 1: sendEnrollAction(VoiceService.ACTION_ENROLL_FINISH);break;
                        case 2: sendEnrollAction(VoiceService.ACTION_ENROLL_CANCEL);break;
                        case 3: new AlertDialog.Builder(this).setTitle("删除本机声纹？")
                            .setMessage("仅删除REx App私有存储中的本人声纹特征。")
                            .setNegativeButton("取消",null)
                            .setPositiveButton("确认删除",(d,w)->sendEnrollAction(VoiceService.ACTION_ENROLL_CLEAR))
                            .show();break;
                    }
                }).setNegativeButton("返回",null).show();
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
