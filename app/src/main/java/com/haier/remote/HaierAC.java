package com.haier.remote;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 海尔空调红外编码 —— 对照 IRremoteESP8266 官方 ir_Haier 实现。
 *
 *  协议A: HAIER_AC 9字节 (老遥控 HSU07-HEA03) —— 默认, 已验证有效
 *  协议B: HAIER_AC_YRW02 14字节
 *
 * 定时原理(重要): 空调内置定时器。每帧携带"当前时钟"(byte2低5位=时,
 * byte3低6位=分)用于同步空调时钟; 设定时只需发一条 TimerSet(0x9) 命令帧,
 * 帧内带关机时刻 h:mm; 到点由空调自己执行, 手机无需在场。
 * 取消定时发 TimerCancel(0xA)。
 *
 * 时序: 前导3000+3000, 帧头3000+4300, 位 520+1650(1)/650(0), 帧尾520, 38kHz。
 */
public class HaierAC {

    public static final int CARRIER_FREQ = 38000;

    private static final int PRE_MARK = 3000, PRE_SPACE = 3000;
    private static final int HDR_MARK = 3000, HDR_SPACE = 4300;
    private static final int BIT_MARK = 520, ONE_SPACE = 1650, ZERO_SPACE = 650;

    public static final int CMD_OFF = 0x0, CMD_ON = 0x1, CMD_MODE = 0x2, CMD_FAN = 0x3,
            CMD_TEMP_UP = 0x6, CMD_TEMP_DOWN = 0x7, CMD_SLEEP = 0x8,
            CMD_TIMER = 0x9, CMD_TIMER_CANCEL = 0xA, CMD_HEALTH = 0xC, CMD_SWING = 0xD;

    public static final int BTN_TEMP_UP = 0x00, BTN_TEMP_DOWN = 0x01, BTN_SWING = 0x02,
            BTN_FAN = 0x04, BTN_POWER = 0x05, BTN_MODE = 0x06, BTN_HEALTH = 0x07,
            BTN_SLEEP = 0x0B, BTN_TIMER = 0x10;

    public static final int M_AUTO = 0, M_COOL = 1, M_HEAT = 2, M_FAN = 3, M_DRY = 4;

    private final byte[] f9 = new byte[9];
    private final byte[] f14 = new byte[14];

    public HaierAC() { reset(); }

    private void reset() {
        Arrays.fill(f9, (byte) 0);
        f9[0] = (byte) 0xA5;   // Prefix
        f9[2] = 0x20;          // bit5 固定为1
        f9[4] = 12;            // OffHours 默认12 (库 stateReset)

        Arrays.fill(f14, (byte) 0);
        f14[0] = (byte) 0xA6;  // YRW02 Model A
    }

    private static int mode9(int m) {
        switch (m) { case M_COOL: return 1; case M_HEAT: return 3; case M_FAN: return 4; case M_DRY: return 2; default: return 0; }
    }
    private static int mode14(int m) {
        switch (m) { case M_COOL: return 1; case M_HEAT: return 4; case M_FAN: return 5; case M_DRY: return 2; default: return 0; }
    }
    private static int fan9(int f) {
        switch (f) { case 1: return 3; case 2: return 2; case 3: return 1; default: return 0; }
    }
    private static int fan14(int f) {
        switch (f) { case 1: return 3; case 2: return 2; case 3: return 1; default: return 5; }
    }

    /**
     * @param currMins     当前时钟(当天分钟数), 每帧携带, 用于同步空调时钟
     * @param timerOffMins 关机时刻(当天分钟数); -1=不动定时字段
     *                     (配合 cmd9=CMD_TIMER 写入定时, cmd9=CMD_TIMER_CANCEL 清除)
     */
    public void build(int power, int mode, int fan, int temp, boolean swing, boolean health,
                      boolean sleep, int cmd9, int btn14, int currMins, int timerOffMins) {
        reset();
        int t = Math.max(16, Math.min(30, temp)) - 16;

        // ---------- 9字节 HAIER_AC ----------
        f9[1] = (byte) ((t << 4) | (cmd9 & 0xF));            // Temp<<4 | Command
        if (swing) f9[2] |= (byte) 0xC0;                     // SwingV bit6-7
        if (currMins >= 0) {                                 // 当前时钟(低5位时 + 低6位分)
            f9[2] = (byte) ((f9[2] & 0xE0) | ((currMins / 60) & 0x1F));
            f9[3] |= (byte) ((currMins % 60) & 0x3F);
        }
        if (timerOffMins >= 0) {                             // 定时关: flag + 关机时刻
            f9[3] |= (byte) 0x40;                            // OffTimer bit6
            f9[4] = (byte) ((f9[4] & 0xE0) | ((timerOffMins / 60) & 0x1F));  // OffHours 低5位
            f9[5] = (byte) ((f9[5] & 0xC0) | (timerOffMins % 60 & 0x3F));    // OffMins 低6位
        }
        if (health) f9[4] |= (byte) 0x20;                    // Health bit5
        f9[5] = (byte) ((f9[5] & 0x3F) | (fan9(fan) << 6));  // Fan bit6-7
        f9[6] |= (byte) (mode9(mode) << 5);                  // Mode bit5-7
        if (sleep) f9[7] |= (byte) 0x40;                     // Sleep bit6
        f9[8] = (byte) sum(f9, 8);

        // ---------- 14字节 HAIER_AC_YRW02 ----------
        f14[1] = (byte) ((t << 4) | (swing ? 0x0C : 0x00));
        if (health) f14[3] |= (byte) 0x02;
        if (power == 1) f14[4] |= (byte) 0x40;
        f14[5] = (byte) ((f14[5] & 0xE0) | (fan14(fan) << 5));
        f14[7] |= (byte) (mode14(mode) << 5);
        if (sleep) f14[8] |= (byte) 0x80;
        if (timerOffMins >= 0) {
            f14[3] = (byte) ((f14[3] & 0x1F) | (1 << 5));                    // TimerMode=OffTimer
            f14[5] = (byte) ((f14[5] & 0xE0) | ((timerOffMins / 60) & 0x1F)); // OffTimerHrs 低5位
            f14[6] |= (byte) (timerOffMins % 60 & 0x3F);                      // OffTimerMins 低6位
        }
        f14[12] = (byte) (btn14 & 0x1F);
        f14[13] = (byte) sum(f14, 13);
    }

    private static int sum(byte[] b, int n) {
        int s = 0;
        for (int i = 0; i < n; i++) s += (b[i] & 0xFF);
        return s & 0xFF;
    }

    public byte[] getFrame9()  { return f9.clone(); }
    public byte[] getFrame14() { return f14.clone(); }

    public static int[] toPattern(byte[] frame) {
        List<Integer> p = new ArrayList<>();
        p.add(PRE_MARK); p.add(PRE_SPACE);
        p.add(HDR_MARK); p.add(HDR_SPACE);
        for (byte b : frame) {
            int v = b & 0xFF;
            for (int bit = 7; bit >= 0; bit--) {
                p.add(BIT_MARK);
                p.add(((v >> bit) & 1) == 1 ? ONE_SPACE : ZERO_SPACE);
            }
        }
        p.add(BIT_MARK);
        int[] r = new int[p.size()];
        for (int i = 0; i < r.length; i++) r[i] = p.get(i);
        return r;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            sb.append(String.format("%02X", b[i] & 0xFF));
            if (i < b.length - 1) sb.append(' ');
        }
        return sb.toString();
    }
}
