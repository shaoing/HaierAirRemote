# HaierAirRemote 海尔空调手机红外遥控器

用手机自带红外发射头（Android `ConsumerIrManager`）控制海尔空调的 APP。
无广告、无网络权限、单文件协议编码可直接复用到其他海尔机型。

**已在海尔 KF-35G/NGA12（单冷 1.5 匹挂机）+ 荣耀手机实机调通。**

![version](https://img.shields.io/badge/version-13-green) ![protocol](https://img.shields.io/badge/protocol-HAIER%20AC%209B%20%2F%20YRW02%2014B-blue)

## 功能

- 开/关机、温度 16-30℃、模式（自动/制冷/送风/除湿）
- 风速循环键（自动→低速→中速→高速）
- 健康 / 扫风 / 睡眠
- **定时关机（空调内置定时器）**：发一条 TimerSet 帧把关机时刻写进空调，手机随后可以关机带走，到点空调自行关闭
- 协议 A/B 在线切换（9 字节老款 / 14 字节 YRW02）
- 调试日志：显示每帧 HEX、发射结果，可滑动查看

## 快速使用

直接安装 [apk/HaierAirRemote-v13.apk](apk/HaierAirRemote-v13.apk)（已签名，Android 5.0+）。

## 从源码构建

纯命令行构建，无需 Android Studio（也可直接用 AS 打开 `app/` 目录）：

```bash
BT=/path/to/android-sdk/build-tools/28.0.3
ANDROID_JAR=/path/to/android-sdk/platforms/android-28/android.jar
./build.sh
```

依赖：JDK 8+、Android SDK（build-tools 28.0.3、platform android-28）。

## 目录结构

```
├── apk/HaierAirRemote-v13.apk      # 已签名成品
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/haier/remote/
│   │   ├── HaierAC.java            # ★ 协议编码: 帧构建+时序数组生成, 可单独复用
│   │   └── MainActivity.java       # UI + 发射 + 日志
│   └── res/                        # 布局/颜色/图标
├── docs/开发指南.md                 # ★ 协议逆向文档: 时序/帧结构/定时原理/踩坑记录
└── build.sh                        # 命令行构建脚本
```

## 移植到其他海尔机型

见 **[docs/开发指南.md](docs/开发指南.md)**，包含：

- 38kHz 物理层时序（双前导 3000+3000 / 3000+4300，位宽 520+1650/650）
- 9 字节 / 14 字节协议逐位帧结构、命令码表、按键码表
- 空调内置定时器原理（TimerSet/TimerCancel + 时钟同步）
- 已验证的样例帧 HEX（单元测试基准）
- HAIER_AC176/160 等新机型的适配路线

## 关键经验（v1~v13 踩坑总结）

1. 网上流传的"3000+6250 单前导 / 1560 位宽"参数是错的，空调完全无反应；
2. 一次按键只发一帧，温度±是增量命令，连发 N 帧跳 N 度；
3. 定时必须用空调内置定时器（帧带绝对时刻），手机倒计时方案是死路；
4. 华为/荣耀部分机型 `transmit()` 静默失败，需反射 `mService.transmit` 兜底。

## License

MIT
