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
import android.speech.SpeechRecognizer;
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
        } else chooseSpeechEngine();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=MIC_REQUEST) return;
        if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED) chooseSpeechEngine();
        else view.setMessage("麦克风权限未授权，语音监听未启动");
    }
    private void chooseSpeechEngine() {
        boolean local=Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
        boolean system=SpeechRecognizer.isRecognitionAvailable(this);
        if(!local&&!system) {
            new AlertDialog.Builder(this).setTitle("未找到语音识别引擎")
                .setMessage("此手机当前没有可用的 Android 语音识别服务，可能需要在系统设置中安装中文语音服务。")
                .setPositiveButton("知道了",null).show();
            view.setMessage("无法唤醒：没有安装语音引擎");
            return;
        }
        if(local&&system) {
            new AlertDialog.Builder(this).setTitle("选择 REx 识别引擎")
                .setMessage("如果之前说 Rex 没反应，可以改用系统识别。系统语音服务可能联网；两个模式都只显示命令，不授权机器人运动。")
                .setItems(new String[]{"本机离线识别（优先）","系统默认识别（可能联网）"},
                    (dialog,which)->selectEngine(which==1)).show();
        } else if(local) {
            selectEngine(false);
        } else {
            new AlertDialog.Builder(this).setTitle("离线识别不可用")
                .setMessage("可以尝试系统默认语音识别服务，但该服务可能将语音发给网络提供商。仅用于显示和界面切换。是否允许？")
                .setNegativeButton("取消",null)
                .setPositiveButton("允许系统识别",(dialog,which)->selectEngine(true))
                .show();
        }
    }
    private void selectEngine(boolean system) {
        getSharedPreferences("rex_settings",MODE_PRIVATE).edit()
            .putBoolean(VoiceService.PREF_ALLOW_SYSTEM,system)
            .putBoolean(VoiceService.PREF_FORCE_SYSTEM,system)
            .apply();
        // A new engine choice must be used by a newly created VoiceService.
        // If the service was already running, stop it first.
        stopService(new Intent(this,VoiceService.class));
        getWindow().getDecorView().postDelayed(this::startVoice,250);
    }
    private void startVoice() {
        try {
            startForegroundService(new Intent(this,VoiceService.class));
            view.setMessage("已请求启动语音服务 · 看下方实时识别状态");
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
