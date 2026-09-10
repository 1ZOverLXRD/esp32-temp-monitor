# 温度监测（Android）

Android 客户端：连接 ESP32 温度监测设备的热点，实时显示温度，下发阈值与分辨率配置。

配套固件见 `../ESP32TEMP/README.md`。

## 功能

- 实时温度显示，小数位跟随设备分辨率（9bit → 1 位、12bit → 4 位）
- 绿 / 黄 / 红级别指示（正常 / 警告 / 报警）
- T1 / T2 阈值步进设置（±0.5℃，约束 T1<T2）、DS18B20 分辨率选择（9/10/11/12 bit）
- 一键下发配置，设备写 NVS 生效
- WiFi 连接状态实时检测（按默认网关 IP 判断，无需定位权限）
- TCP 断线自动重连（3s 间隔）

## 环境要求

- JDK 17（`D:\Program Files\Javas\dragonwell-17.0.16`）
- Android SDK（`F:\AndroidSDK`）
- Gradle 8.13 + AGP 8.13.2（项目自带 wrapper）

## 构建

```bash
# Debug
./gradlew.bat assembleDebug

# Release（使用 F:\Project\Android\JKS_COLD_FISH 签名，alias=coldfish）
./gradlew.bat assembleRelease
```

构建产物：

```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## 安装

```bash
adb -s <设备序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

Release 与 Debug 签名不同，切换安装前需先卸载旧包。

## 使用

1. 手机连接 ESP32 热点 `ESP32-TEMP`（密码 `12345678`）
2. 打开 App：顶部显示「已连接」，中部实时温度，底部阈值卡片
3. 「配置」页修改 T1/T2 与分辨率，点「下发到设备」；下发成功按钮变绿
4. 未连接热点时顶部显示提示条；连上后自动消失

## 项目结构

```
AndroidRevicer/
├── app/src/main/java/com/coldfish/myapplication/
│   ├── MainActivity.kt        # 装配 + 底部导航
│   ├── net/                   # TcpClient（自动重连 + JSON 行协议）、数据模型
│   ├── data/                  # TemperatureRepository（状态流 + WiFi 检测）
│   └── ui/                    # HomeScreen（监测）、ConfigScreen（配置）、theme
└── app/src/main/AndroidManifest.xml
```

## 通讯协议

与设备约定见 ESP32 README「通讯协议」一节。TCP 8080，JSON 行协议：

| 方向 | 消息 |
|------|------|
| 设备 → App（每秒） | `{"type":"status","temp":26.25,"level":0,"t1":30.0,"t2":40.0,"res":10}` |
| App → 设备 | `{"type":"set_config","t1":28.0,"t2":45.0,"res":12}` |
| 设备 → App（回执） | `{"type":"ack","ok":true,"t1":28.0,"t2":45.0,"res":12}` |

## 调试

```bash
# 查看网络层日志（连接/重连/收发）
adb logcat -s TcpClient
```
