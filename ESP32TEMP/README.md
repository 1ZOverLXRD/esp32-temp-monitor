# ESP32TEMP

ESP32 温度监测固件：DS18B20 采集温度，OLED 显示，三色 LED 按阈值分级告警，通过 WiFi AP + TCP 与 Android 客户端通讯。

## 功能

- DS18B20 温度采集，分辨率 9/10/11/12 bit 可配置（Android 下发）
- 0.96 寸 SSD1306 OLED 显示（u8g2），温度小数位跟随分辨率（9bit → 1 位、12bit → 4 位）
- 绿 / 黄 / 红三色 LED 分级告警：≤T1 绿灯、T1~T2 黄灯、>T2 红灯
- WiFi AP 模式（SSID: `ESP32-TEMP`）+ TCP Server（端口 8080）
- 阈值 T1/T2 与分辨率写入 NVS，断电不丢
- 串口日志：级别切换、分辨率切换、set_config 接收均输出

## 硬件需求

| 模块 | 型号/参数 |
|------|-----------|
| 主控 | ESP32-WROOM-32（DevKit V1，30 针） |
| 温度传感器 | DS18B20（1-Wire） |
| 显示屏 | 0.96 寸 SSD1306 128×64，I2C 四针 |
| LED | 绿 / 黄 / 红 共阳灯珠 ×3（负极串 330Ω 电阻） |

## 接线

| 模块 | 引脚 | 接 ESP32 |
|------|------|---------|
| DS18B20 | VCC / GND / DQ | 3V3 / GND / GPIO4 |
| OLED | VCC / GND / SCL / SDA | 3V3 / GND / GPIO22 / GPIO21 |
| LED 绿 / 黄 / 红 | 阳极共接 3V3，阴极串电阻 | GPIO16 / GPIO17 / GPIO18 |

DS18B20 使用芯片内部上拉；读数不稳时在 DQ 与 3V3 间补 4.7kΩ。LED 为开漏输出，低电平点亮。

## 环境要求

- ESP-IDF v5.5.2（`C:\Espressif\frameworks\esp-idf-v5.5.2`）
- 组件（component manager 自动拉取）：`espressif/ds18b20`、`espressif/onewire_bus`、`nixy4/u8g2`

## 构建与烧录

```bash
# Windows 下使用 IDF Python 虚拟环境（不要用系统 Python）
cd ESP32TEMP
C:\Espressif\python_env\idf5.5_py3.11_env\Scripts\python.exe C:\Espressif\frameworks\esp-idf-v5.5.2\tools\idf.py build
C:\Espressif\python_env\idf5.5_py3.11_env\Scripts\python.exe C:\Espressif\frameworks\esp-idf-v5.5.2\tools\idf.py -p COM3 -b 1152000 flash
```

- 烧录波特率 1152000（v5.x 由 `-b` 参数控制，`CONFIG_ESPTOOLPY_BAUD` 已失效）
- 编译配置（`sdkconfig.defaults`）：4MB flash、`-O2` release 优化、ccache 加速

## 使用

1. 上电后 ESP32 开启热点 `ESP32-TEMP`（密码 `12345678`）
2. 手机连接热点，打开 Android 客户端（见 `../AndroidRevicer/README.md`）
3. 客户端实时显示温度；在配置页修改 T1/T2 阈值与分辨率后下发，设备写 NVS 并生效

## 通讯协议

TCP 8080，每行一条 JSON（`\n` 分隔）：

| 方向 | 消息 |
|------|------|
| 设备 → 客户端（每秒） | `{"type":"status","temp":26.25,"level":0,"t1":30.0,"t2":40.0,"res":10}` |
| 客户端 → 设备 | `{"type":"set_config","t1":28.0,"t2":45.0,"res":12}` |
| 设备 → 客户端（回执） | `{"type":"ack","ok":true,"t1":28.0,"t2":45.0,"res":12}` |

`level`：0=绿（≤T1）、1=黄（T1~T2）、2=红（>T2）。

## 项目结构

```
ESP32TEMP/
├── main/                 # 主程序：初始化、temp_task（采样/判级/LED）、ui_task（OLED）
├── components/
│   ├── ds18b20_sensor/   # DS18B20 读取（官方组件封装，分辨率可设）
│   ├── led_indicator/    # 三色 LED 开漏驱动
│   ├── threshold_store/  # 阈值/分辨率 NVS 存储
│   ├── tcp_server/       # WiFi AP + TCP Server + JSON 收发
│   └── u8g2_ui/          # OLED 纯显示（u8g2）
└── sdkconfig.defaults    # 构建配置（4MB flash / -O2 / ccache）
```
