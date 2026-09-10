# ESP32 温度监测 + Android 客户端

ESP32 端做 DS18B20 温度采集、OLED 显示、三色 LED 分级告警，通过 WiFi 热点 + TCP 与 Android 客户端通讯；阈值与分辨率可在客户端下发，设备写入 NVS 持久化。

## 项目导航

| 目录 | 说明 |
|------|------|
| [ESP32TEMP](ESP32TEMP/README.md) | ESP32 固件：DS18B20 采集（9~12bit 可配）、u8g2 OLED 显示、绿/黄/红 LED 分级（T1/T2 阈值）、AP 热点 + TCP Server 8080、NVS 存储 |
| [AndroidRevicer](AndroidRevicer/README.md) | Android 客户端：实时温度显示（小数位随分辨率）、T1/T2 阈值与分辨率下发、WiFi 热点状态检测、断线自动重连 |

## 快速开始

1. **固件**：按 `ESP32TEMP/README.md` 构建烧录（ESP-IDF v5.5.2）
2. **客户端**：按 `AndroidRevicer/README.md` 构建安装（Gradle）
3. **使用**：手机连 `ESP32-TEMP` 热点 → 打开 App → 查看实时温度，配置页修改阈值/分辨率后下发

双端通讯协议见 `ESP32TEMP/README.md`「通讯协议」一节。