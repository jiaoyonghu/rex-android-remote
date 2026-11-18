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

/** Standalone infrared transmitter UI; robot does not reply via infrared. */
public final class MainActivity extends Activity implements RexView.Actions {
    private static final int MIC_REQUEST = 21;
    private NecIr ir;
    private RexView view;
    private boolean receiverRegistered;
    private final BroadcastReceiver voiceEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (view == null) return;
            String text = intent.getStringExtra(VoiceService.EXTRA_TEXT);
            String command = intent.getStringExtra(VoiceService.EXTRA_COMMAND);
            boolean wake = intent.getBooleanExtra(VoiceService.EXTRA_WAKE, false);
            float level = intent.getFloatExtra(VoiceService.EXTRA_LEVEL, 0f);
            view.onVoice(text, command, wake, level);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(0xFF050A15);
        getWindow().setNavigationBarColor(0xFF050A15);
        getWindow().getDecorView().setSystemUiVisibility(0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ir = new NecIr(this);
        view = new RexView(this, this);
        view.setIrStatus(ir.status());
        setContentView(view);
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(VoiceService.ACTION_EVENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(voiceEvents, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(voiceEvents, filter);
        receiverRegistered = true;
    }
    @Override protected void onStop() {
        if (receiverRegistered) { unregisterReceiver(voiceEvents); receiverRegistered = false; }
        super.onStop();
    }

    @Override public void onStartVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
        } else startVoice();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == MIC_REQUEST && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startVoice();
        else if (requestCode == MIC_REQUEST) view.setMessage("麦克风权限未授权；语音监听未启动");
    }
    private void startVoice() {
        try {
            startForegroundService(new Intent(this, VoiceService.class));
            view.setMessage("语音预览启动：暂不具备本人声纹认证");
        } catch (Exception e) {
            view.setMessage("无法启动语音服务：" + e.getClass().getSimpleName());
        }
    }
    @Override public void onStopVoice() {
        stopService(new Intent(this, VoiceService.class));
        view.setMessage("语音监听已关闭");
    }

    @Override public void onSend(RexCommand cmd) {
        if (cmd == RexCommand.DISABLE || cmd == RexCommand.ACTIVE || cmd == RexCommand.STAND) {
            new AlertDialog.Builder(this)
                .setTitle("确认红外指令")
                .setMessage("即将发送一次「" + cmd.label + "」。红外单向，无法确认机器人接收；失能可能导致跌倒。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认发射", (dialog, which) -> sendIr(cmd))
                .show();
        } else sendIr(cmd);
    }
    private void sendIr(RexCommand cmd) {
        try {
            ir.send(cmd);
            view.setMessage("已发射 IR: " + cmd.label + " · 0x" + Integer.toHexString(cmd.code).toUpperCase());
        } catch (Exception ex) {
            view.setMessage("IR 未发射：" + ex.getMessage());
            Toast.makeText(this, "红外发射失败，请检查手机系统权限", Toast.LENGTH_LONG).show();
        }
    }
}
