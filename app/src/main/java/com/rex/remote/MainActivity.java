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

/** Android UI: voice commands are displayed but NOT authorized to move robot. */
public final class MainActivity extends Activity implements RexView.Actions {
    private static final int MIC_REQUEST=21;
    private NecIr ir;
    private RexView view;
    private boolean registered;
    private final BroadcastReceiver voiceEvents=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent i) {
            if (view==null) return;
            view.onVoice(i.getStringExtra(VoiceService.EXTRA_TEXT),
                i.getStringExtra(VoiceService.EXTRA_COMMAND),
                i.getBooleanExtra(VoiceService.EXTRA_WAKE,false),
                i.getFloatExtra(VoiceService.EXTRA_LEVEL,0f),
                i.getStringExtra(VoiceService.EXTRA_STATUS),
                i.getBooleanExtra(VoiceService.EXTRA_SPEAKING,false));
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
        if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED) startVoice();
        else view.setMessage("麦克风权限未授权，语音监听未启动");
    }
    private void startVoice() {
        try {
            startForegroundService(new Intent(this,VoiceService.class));
            view.setMessage("REx 本地 Vosk 引擎启动中 · 首次需解压内置模型");
        } catch(Exception e) {
            view.setMessage("语音启动失败："+e.getClass().getSimpleName());
        }
    }
    @Override public void onStopVoice() {
        stopService(new Intent(this,VoiceService.class));
        view.setVoiceStopped();
    }
    @Override public void onSend(RexCommand cmd) {
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
