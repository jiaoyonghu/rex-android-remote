package com.rex.remote;

import android.content.Context;
import android.hardware.ConsumerIrManager;

/**
 * REx test frames: NEC extended address 0x3B4C, 8-bit command and inverse.
 * Bits transmitted LSB first. Can only report local transmit success, not IR receipt.
 * Does not interact with ROS or motors.
 */
public final class NecIr {
    public static final int CARRIER = 38000;
    public static final int ADDRESS = 0x3B4C;
    private final ConsumerIrManager manager;

    public NecIr(Context context) {
        manager = (ConsumerIrManager) context.getSystemService(Context.CONSUMER_IR_SERVICE);
    }

    public boolean available() {
        if (manager == null || !manager.hasIrEmitter()) return false;
        ConsumerIrManager.CarrierFrequencyRange[] ranges = manager.getCarrierFrequencies();
        if (ranges == null) return false;
        for (ConsumerIrManager.CarrierFrequencyRange r : ranges) {
            if (r.getMinFrequency() <= CARRIER && CARRIER <= r.getMaxFrequency()) return true;
        }
        return false;
    }

    public String status() {
        if (manager == null) return "ColorOS 未开放 ConsumerIR 系统服务";
        if (!manager.hasIrEmitter()) return "第三方应用未检测到红外发射器";
        ConsumerIrManager.CarrierFrequencyRange[] ranges = manager.getCarrierFrequencies();
        if (ranges == null) return "无法获取红外频率范围";
        if (!available()) return "不支持 38 kHz 载波";
        return "38kHz 可用（仅证明本地接口可调用）";
    }

    /** Entire NEC waveform in microseconds: mark/space alternation. */
    public static int[] pattern(RexCommand command) {
        int word = ((ADDRESS & 0xFF) | (((ADDRESS >>> 8) & 0xFF) << 8)
            | ((command.code & 0xFF) << 16) | (((~command.code) & 0xFF) << 24));
        int[] p = new int[2 + 32 * 2 + 1];
        p[0] = 9000;
        p[1] = 4500;
        for (int i = 0; i < 32; ++i) {
            p[2 + i * 2] = 560;
            p[3 + i * 2] = (((word >>> i) & 1) != 0) ? 1690 : 560;
        }
        p[p.length - 1] = 560;
        return p;
    }

    public synchronized void send(RexCommand command) {
        if (!available()) throw new IllegalStateException(status());
        manager.transmit(CARRIER, pattern(command));
    }
}
