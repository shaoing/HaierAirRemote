package com.haier.remote;

import android.app.Activity;
import android.content.Context;
import android.hardware.ConsumerIrManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Vibrator;
import android.text.method.ScrollingMovementMethod;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private boolean isPowerOn = false;
    private int temperature = 26, mode = 1, fanSpeed = 0;
    private boolean healthMode = false, swingOn = false, sleepOn = false;
    private int timerHours = 0;            // 滑条选的小时数
    private int timerFireMin = -1;         // 空调内置定时器的关机时刻(当天分钟数), -1=未设

    private TextView tvStatus, tvTemp, tvMode, tvFanSpeed, tvTimer;
    private TextView tvHealthStatus, tvHealthIndicator;
    private TextView tvTimerDisplay, tvTimerStatus, tvDebug, tvProto;
    private boolean protocol9 = true;      // true=海尔A(9字节) false=海尔B(14字节)
    private SeekBar seekTimer;
    private ImageButton btnPower;

    private ConsumerIrManager irManager;
    private Object irService = null;
    private Method irTransmit = null;
    private StringBuilder logBuf = new StringBuilder();
    private SimpleDateFormat ts = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private static final String[] MODE_NAMES = {"自动","制冷","送风","除湿"};
    private static final String[] FAN_NAMES  = {"自动","低速","中速","高速"};
    private static final int[] MODE_BTN_IDS = {R.id.btnModeAuto,R.id.btnModeCool,R.id.btnModeFan,R.id.btnModeDry};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        irManager = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        initViews();
        initListeners();
        updateDisplay();
        deepIRDiag();
    }

    // ================= 日志 =================
    // 最新日志在底部, 自动滚动到最新; 环形缓冲保留120行
    private void log(String msg) {
        runOnUiThread(() -> {
            logBuf.append(msg).append('\n');
            int nl = 0, cut = -1;
            for (int i = 0; i < logBuf.length() && nl <= 120; i++)
                if (logBuf.charAt(i) == '\n') { nl++; if (nl > 120) cut = i; }
            if (cut >= 0) logBuf.delete(0, cut + 1);
            tvDebug.setText(logBuf.toString());
            tvDebug.post(() -> {
                int h = tvDebug.getLineCount() * tvDebug.getLineHeight();
                if (h > tvDebug.getHeight()) tvDebug.scrollTo(0, h - tvDebug.getHeight());
            });
        });
    }

    private void beep() {
        try {
            final ToneGenerator tg = new ToneGenerator(ToneGenerator.TONE_PROP_BEEP, 80);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
            tvDebug.postDelayed(tg::release, 300);
        } catch (Exception e) {}
    }

    // ================= IR 诊断 =================
    private void deepIRDiag() {
        log("===== IR诊断 v13 (空调原生定时) =====");
        log("设备: " + android.os.Build.MODEL);
        log("厂商: " + android.os.Build.MANUFACTURER + "  安卓: " + android.os.Build.VERSION.RELEASE);
        log("--- 标准IR API ---");
        if (irManager != null) {
            log("hasIrEmitter: " + irManager.hasIrEmitter());
            try {
                ConsumerIrManager.CarrierFrequencyRange[] freqs = irManager.getCarrierFrequencies();
                if (freqs != null) for (ConsumerIrManager.CarrierFrequencyRange f : freqs)
                    log("  频率: " + f.getMinFrequency() + "-" + f.getMaxFrequency());
            } catch (Exception e) { log("  频率查询异常: " + e.getMessage()); }
        } else log("ConsumerIrManager: NULL");
        log("--- IR内部对象 ---");
        try {
            java.lang.reflect.Field f = ConsumerIrManager.class.getDeclaredField("mService");
            f.setAccessible(true);
            Object svc = f.get(irManager);
            if (svc != null) {
                log("mService类: " + svc.getClass().getName());
                for (Method m : svc.getClass().getMethods()) {
                    String name = m.getName();
                    if (name.contains("transmit") || name.contains("Ir") || name.contains("ir")) {
                        log("  方法: " + name + "(" + m.getParameterCount() + "参数)");
                        irService = svc;
                        if (name.equals("transmit")) irTransmit = m;
                    }
                }
            }
        } catch (Exception e) { log("反射mService: " + e.getMessage()); }
        log("===== 诊断完成, 长按[清空日志]可重新诊断 =====");
    }

    // ================= 视图 =================
    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvTemp = findViewById(R.id.tvTemp);
        tvMode = findViewById(R.id.tvMode);
        tvFanSpeed = findViewById(R.id.tvFanSpeed);
        tvTimer = findViewById(R.id.tvTimer);
        tvHealthStatus = findViewById(R.id.tvHealthStatus);
        tvHealthIndicator = findViewById(R.id.tvHealthIndicator);
        tvTimerDisplay = findViewById(R.id.tvTimerDisplay);
        tvTimerStatus = findViewById(R.id.tvTimerStatus);
        seekTimer = findViewById(R.id.seekTimer);
        tvDebug = findViewById(R.id.tvDebug);
        tvProto = findViewById(R.id.tvProto);
        btnPower = findViewById(R.id.btnPower);
        updateProtoText();
        tvDebug.setMovementMethod(new ScrollingMovementMethod());
        // 日志区可独立上下滑动(不被整页ScrollView拦截)
        tvDebug.setOnTouchListener((v, e) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            int a = e.getAction() & MotionEvent.ACTION_MASK;
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)
                v.getParent().requestDisallowInterceptTouchEvent(false);
            return false;
        });
    }

    private void initListeners() {
        btnPower.setOnClickListener(v -> {
            vibrate(50);
            isPowerOn = !isPowerOn;
            if (!isPowerOn) { healthMode=false; swingOn=false; sleepOn=false; }
            sendAndLog(isPowerOn?"开机":"关机", isPowerOn?HaierAC.CMD_ON:HaierAC.CMD_OFF);
            updateDisplay();
        });

        findViewById(R.id.btnTempUp).setOnClickListener(v -> {
            if(!checkPower())return; vibrate(30);
            if(temperature<30){temperature++; sendAndLog("温度"+temperature,HaierAC.CMD_TEMP_UP); updateDisplay();}
        });
        findViewById(R.id.btnTempDown).setOnClickListener(v -> {
            if(!checkPower())return; vibrate(30);
            if(temperature>16){temperature--; sendAndLog("温度"+temperature,HaierAC.CMD_TEMP_DOWN); updateDisplay();}
        });

        for(int i=0;i<MODE_BTN_IDS.length;i++){final int m=i;
            findViewById(MODE_BTN_IDS[i]).setOnClickListener(v->{if(!checkPower())return;vibrate(30);mode=m;sendAndLog("模式:"+MODE_NAMES[m],HaierAC.CMD_MODE);updateDisplay();});
        }
        // 风速循环键: 自动→低速→中速→高速→自动
        findViewById(R.id.btnFanCycle).setOnClickListener(v->{
            if(!checkPower())return; vibrate(30);
            fanSpeed=(fanSpeed+1)%4;
            sendAndLog("风速:"+FAN_NAMES[fanSpeed],HaierAC.CMD_FAN); updateDisplay();
        });

        findViewById(R.id.btnHealth).setOnClickListener(v->{if(!checkPower())return;vibrate(30);healthMode=!healthMode;sendAndLog("健康"+(healthMode?"开":"关"),HaierAC.CMD_HEALTH);updateDisplay();});
        findViewById(R.id.btnSwing).setOnClickListener(v->{if(!checkPower())return;vibrate(30);swingOn=!swingOn;sendAndLog("扫风"+(swingOn?"开":"关"),HaierAC.CMD_SWING);updateDisplay();});
        findViewById(R.id.btnSleep).setOnClickListener(v->{if(!checkPower())return;vibrate(30);sleepOn=!sleepOn;sendAndLog("睡眠"+(sleepOn?"开":"关"),HaierAC.CMD_SLEEP);updateDisplay();});

        seekTimer.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int p,boolean f){timerHours=p;updateTimerDisplay();}
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });

        // 定时关机: 写入空调内置定时器(发一条TimerSet帧, 空调自己执行, 手机可关机离开)
        findViewById(R.id.btnTimerOff).setOnClickListener(v->{
            if(timerHours==0){showToast("请先用滑条设置小时数");return;}
            int now=nowMin(), fire=(now+timerHours*60)%1440;
            vibrate(80); beep();
            sendTimer(fire, true);
        });
        findViewById(R.id.btnTimerCancel).setOnClickListener(v->{
            if(timerFireMin<0){showToast("当前没有定时");return;}
            vibrate(50);
            sendTimer(-1, false);
        });

        findViewById(R.id.btnTestIR).setOnClickListener(v->{vibrate(30);logBuf.setLength(0);tvDebug.setText("");tvDebug.scrollTo(0,0);log("日志已清空 (长按此按钮=重新诊断)");});
        findViewById(R.id.btnTestIR).setOnLongClickListener(v->{vibrate(50);logBuf.setLength(0);tvDebug.setText("");deepIRDiag();return true;});
        tvProto.setOnClickListener(v->{vibrate(20);protocol9=!protocol9;updateProtoText();log("协议切换 → "+(protocol9?"海尔A(9字节)":"海尔B(14字节)"));});
    }

    private int getHaierMode(){switch(mode){case 0:return HaierAC.M_AUTO;case 1:return HaierAC.M_COOL;case 2:return HaierAC.M_FAN;case 3:return HaierAC.M_DRY;default:return HaierAC.M_COOL;}}

    private void updateProtoText(){tvProto.setText("红外协议: "+(protocol9?"海尔A (9字节)":"海尔B (14字节)")+"　点击切换");}

    private static int nowMin(){Calendar c=Calendar.getInstance();return c.get(Calendar.HOUR_OF_DAY)*60+c.get(Calendar.MINUTE);}
    private static String fmtMin(int m){return String.format(Locale.CHINA,"%02d:%02d",m/60,m%60);}

    // ================= 发送 =================
    private void sendAndLog(String action, int command) {
        int btn;
        switch (command) {
            case HaierAC.CMD_TEMP_UP:   btn = HaierAC.BTN_TEMP_UP;   break;
            case HaierAC.CMD_TEMP_DOWN: btn = HaierAC.BTN_TEMP_DOWN; break;
            case HaierAC.CMD_MODE:      btn = HaierAC.BTN_MODE;      break;
            case HaierAC.CMD_FAN:       btn = HaierAC.BTN_FAN;       break;
            case HaierAC.CMD_SWING:     btn = HaierAC.BTN_SWING;     break;
            case HaierAC.CMD_HEALTH:    btn = HaierAC.BTN_HEALTH;    break;
            case HaierAC.CMD_SLEEP:     btn = HaierAC.BTN_SLEEP;     break;
            default: btn = HaierAC.BTN_POWER; break;
        }
        HaierAC ac = new HaierAC();
        ac.build(isPowerOn?1:0, getHaierMode(), fanSpeed, temperature, swingOn, healthMode,
                 sleepOn, command, btn, nowMin(), -1);
        transmitFrame(action, ac);
    }

    // 定时: set=true写入关机时刻, false=取消
    private void sendTimer(int fireMin, boolean set) {
        HaierAC ac = new HaierAC();
        ac.build(isPowerOn?1:0, getHaierMode(), fanSpeed, temperature, swingOn, healthMode,
                 sleepOn, set?HaierAC.CMD_TIMER:HaierAC.CMD_TIMER_CANCEL, HaierAC.BTN_TIMER,
                 nowMin(), set?fireMin:-1);
        if (set) {
            transmitFrame("设置定时关机 "+fmtMin(fireMin), ac);
            timerFireMin = fireMin;
            log("  空调内置定时器已设置: "+fmtMin(fireMin)+" 关机 (由空调自己执行, 手机可离开)");
            showToast("空调定时: "+fmtMin(fireMin)+" 关机");
        } else {
            transmitFrame("取消定时", ac);
            timerFireMin = -1;
            log("  空调定时已取消");
            showToast("定时已取消");
        }
        updateTimerDisplay();
    }

    private void transmitFrame(String action, HaierAC ac) {
        byte[] frame = protocol9 ? ac.getFrame9() : ac.getFrame14();
        int[] pat = HaierAC.toPattern(frame);
        log("[" + ts.format(new Date()) + "] " + action);
        log((protocol9 ? "  [A 9字节] " : "  [B 14字节] ") + HaierAC.hex(frame));
        boolean sent = false;
        if (irManager != null && irManager.hasIrEmitter()) {
            try { irManager.transmit(HaierAC.CARRIER_FREQ, pat); sent = true; log("  [发送] OK"); }
            catch (Exception e) { log("  [发送] 失败: " + e.getMessage()); }
        } else { log("  [发送] hasIrEmitter=false"); }
        if (!sent && irService != null && irTransmit != null) {
            try { irTransmit.invoke(irService, HaierAC.CARRIER_FREQ, pat); log("  [反射] OK"); }
            catch (Exception e) { log("  [反射] 失败: " + e.getMessage()); }
        }
    }

    // ================= 显示 =================
    private void updateTimerDisplay() {
        if (timerFireMin >= 0) { tvTimerDisplay.setText(fmtMin(timerFireMin)); tvTimerStatus.setText("空调定时关机 "+fmtMin(timerFireMin)+" (内置定时器)"); }
        else if (timerHours > 0) { tvTimerDisplay.setText("+"+timerHours+"h"); tvTimerStatus.setText(""); }
        else { tvTimerDisplay.setText("未设置"); tvTimerStatus.setText(""); }
    }

    private void updateDisplay() {
        if (isPowerOn) {
            tvStatus.setText(R.string.status_on);
            tvStatus.setTextColor(getResources().getColor(R.color.btn_green));
            tvTemp.setText(String.valueOf(temperature));
            tvTemp.setTextColor(getResources().getColor(R.color.text_yellow));
            tvMode.setText("模式: " + MODE_NAMES[mode]);
            tvMode.setTextColor(getResources().getColor(R.color.haier_light_blue));
            tvFanSpeed.setText("风速: " + FAN_NAMES[fanSpeed]);
            tvFanSpeed.setTextColor(getResources().getColor(R.color.haier_light_blue));
            ((android.widget.Button)findViewById(R.id.btnFanCycle)).setText("风速: " + FAN_NAMES[fanSpeed]);
            findViewById(R.id.btnFanCycle).setBackgroundResource(fanSpeed==0?R.drawable.btn_round:R.drawable.btn_active);
            btnPower.setBackgroundColor(getResources().getColor(R.color.btn_green));
            if (healthMode) {
                tvHealthStatus.setVisibility(View.VISIBLE); tvHealthStatus.setText(" HEALTH");
                tvHealthIndicator.setText("●"); tvHealthIndicator.setTextColor(getResources().getColor(R.color.health_green));
                findViewById(R.id.btnHealth).setBackgroundResource(R.drawable.btn_active);
            } else {
                tvHealthStatus.setVisibility(View.GONE); tvHealthIndicator.setText("●");
                tvHealthIndicator.setTextColor(getResources().getColor(R.color.text_gray));
                findViewById(R.id.btnHealth).setBackgroundResource(R.drawable.btn_health);
            }
            findViewById(R.id.btnSwing).setBackgroundResource(swingOn ? R.drawable.btn_active : R.drawable.btn_round);
            findViewById(R.id.btnSleep).setBackgroundResource(sleepOn ? R.drawable.btn_active : R.drawable.btn_round);
        } else {
            tvStatus.setText(R.string.status_off);
            tvStatus.setTextColor(getResources().getColor(R.color.text_gray));
            tvTemp.setText("--"); tvTemp.setTextColor(getResources().getColor(R.color.text_gray));
            tvMode.setText("模式: --"); tvMode.setTextColor(getResources().getColor(R.color.text_gray));
            tvFanSpeed.setText("风速: --"); tvFanSpeed.setTextColor(getResources().getColor(R.color.text_gray));
            ((android.widget.Button)findViewById(R.id.btnFanCycle)).setText("风速: --");
            findViewById(R.id.btnFanCycle).setBackgroundResource(R.drawable.btn_round);
            btnPower.setBackgroundColor(getResources().getColor(R.color.btn_red));
            tvHealthStatus.setVisibility(View.GONE); tvHealthIndicator.setText("●");
            tvHealthIndicator.setTextColor(getResources().getColor(R.color.text_gray));
            findViewById(R.id.btnHealth).setBackgroundResource(R.drawable.btn_health);
            findViewById(R.id.btnSwing).setBackgroundResource(R.drawable.btn_round);
            findViewById(R.id.btnSleep).setBackgroundResource(R.drawable.btn_round);
        }
    }

    private boolean checkPower(){if(!isPowerOn){showToast("请先开机");return false;}return true;}
    private void vibrate(int ms){try{Vibrator v=(Vibrator)getSystemService(Context.VIBRATOR_SERVICE);if(v!=null&&v.hasVibrator())v.vibrate(ms);}catch(Exception e){}}
    private void showToast(String msg){runOnUiThread(()->Toast.makeText(this,msg,Toast.LENGTH_SHORT).show());}
    @Override protected void onDestroy(){super.onDestroy();}
}
