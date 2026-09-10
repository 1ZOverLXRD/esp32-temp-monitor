# ESP32 温度监测 + Android 联动系统 — 设计文档

- 日期：2026-09-10
- 项目根目录：`D:\Project\BYSJ\ESP32TEMPWITHANDROID\`
- 状态：设计定稿（待用户 review）

---

## 1. 概述

### 1.1 目标

ESP32 采集 DS18B20 温度，通过 u8g2 驱动 0.96 寸 OLED 显示，并按三级温度阈值驱动绿/黄/红三色 LED。ESP32 以 AP 模式开热点，Android 端直连后通过 TCP 获取实时温度并远程下发阈值/分辨率配置，配置持久化到 NVS（非易失 flash），掉电不丢。

### 1.2 范围

- ESP32 端：DS18B20 采集、OLED 纯显示、三色 LED 分级、AP+TCP 服务、NVS 存储。
- Android 端：温度实时显示、阈值/分辨率配置下发、WiFi 热点连接引导。
- 交付物：双端源码 + 可视化接线图（HTML）+ OLED 点阵模拟 + Android 界面原型。

### 1.3 非目标（YAGNI）

- 不做 OLED 本地菜单交互（无摇杆，配置全在 Android 端）。
- 不做 MQTT / HTTP 协议，不做 TLS 加密（本地局域网直连）。
- 不做多路 DS18B20、不做温度历史曲线、不做云端。

---

## 2. 系统架构

```
┌──────────────┐  AP热点(SSID:ESP32-TEMP)  ┌──────────────┐
│   ESP32      │  ←────── TCP:8080 ──────→ │   Android    │
│ 采集+显示+灯  │       JSON 双向通讯        │ 显示+设配置    │
└──────────────┘                          └──────────────┘
    │ DS18B20(GPIO4)                           │
    │ OLED I2C(GPIO21/22)                      │ WiFi 连热点
    │ LED×3 共阳(GPIO16/17/18)                 └ 改阈值→TCP下发
    └ 阈值存 NVS
```

- ESP32 上电 → 启 AP 热点 → 启 TCP Server（8080）→ 并行任务：采样、OLED 刷新、LED 驱动、网络收发。
- Android 连热点后主动连 TCP，接收 status 推送，需要时下发 set_config。
- 双端解耦：Android 断开不影响 ESP32 本地工作（OLED + LED 照常）。

---

## 3. 通讯协议（TCP + JSON）

每行一条 JSON，`\n` 分隔。ESP32 用 `cJSON`（IDF 自带）解析。

| 方向 | 消息 | 说明 |
|------|------|------|
| ESP32→Android（每 1s） | `{"type":"status","temp":26.5,"level":0,"t1":30.0,"t2":40.0,"res":10}` | 温度+级别+当前配置 |
| Android→ESP32（改配置） | `{"type":"set_config","t1":30.0,"t2":40.0,"res":10}` | 全量下发配置 |
| ESP32→Android（回执） | `{"type":"ack","ok":true,"t1":30.0,"t2":40.0,"res":10}` | 校验结果+回显生效值 |

字段说明：

- `temp`：温度，float，单位 °C，1 位小数。
- `level`：int，`0`=绿（正常，temp≤T1）、`1`=黄（警告，T1<temp≤T2）、`2`=红（报警，temp>T2）。
- `t1` / `t2`：阈值，float，单位 °C，默认 `30.0` / `40.0`，合法范围 `0 ≤ t1 < t2 ≤ 125`。
- `res`：DS18B20 分辨率，int，`9/10/11/12`，默认 `10`。
- `ok`：bool，配置校验结果。`false` 时不写 NVS。

AP 参数：SSID=`ESP32-TEMP`，密码=`12345678`，IP=`192.168.4.1`，信道 1，TCP 端口 `8080`。

---

## 4. Pin 分配与接线

开发板：ESP32-WROOM-32（DevKit V1，30 针）。已避开所有 strapping（GPIO0/2/5/12/15）、flash（GPIO6–11）、UART0（GPIO1/3）冲突脚。

| 模块 | 引脚 | 方向/模式 | 说明 |
|------|------|-----------|------|
| DS18B20 DQ | **GPIO4** | 输入，内部上拉 | 1-Wire 数据线 |
| OLED SDA | **GPIO21** | I2C 数据 | ESP32 默认 I2C0 |
| OLED SCL | **GPIO22** | I2C 时钟 | ESP32 默认 I2C0 |
| 绿灯 | **GPIO16** | 开漏输出 | 共阳，低电平点亮 |
| 黄灯 | **GPIO17** | 开漏输出 | 共阳，低电平点亮 |
| 红灯 | **GPIO18** | 开漏输出 | 共阳，低电平点亮 |

### 4.1 供电

- 所有模块 VCC → 3.3V，GND → GND。

### 4.2 LED 接法（共阳 + 开漏）

```
VCC(3.3V) ──► LED 正极           LED 负极 ──[限流电阻]── GPIO(16/17/18)
```

- GPIO 配 `GPIO_MODE_OUTPUT_OD`（开漏）：输出 `0` 拉低 = 点亮，输出 `1` 释放 = 熄灭。
- 逻辑反转：level 0 → GPIO16 拉低（绿亮）；level 1 → GPIO17 拉低（黄亮）；level 2 → GPIO18 拉低（红亮）。同一时刻仅一路拉低。
- 限流电阻建议 330Ω（3.3V/330Ω≈10mA），按亮度自行 220–470Ω。

### 4.3 电阻策略（最小外部电阻）

| 模块 | 方案 |
|------|------|
| DS18B20 | 用 GPIO4 内部上拉（约 45kΩ）。**兜底**：读数偶发 85°C 或 CRC 错时，补 4.7kΩ 上拉到 3.3V |
| 摇杆 SW | （已移除摇杆，无此需求） |
| OLED | 0.96 寸 SSD1306 模块板载 I2C 上拉电阻，无需外部 |
| LED | 仅限流电阻（用户自串），无上下拉 |

> 除 LED 限流电阻外，默认零外部电阻。DS18B20 的 4.7k 仅作不稳定时的可选兜底。

---

## 5. ESP32 端设计

### 5.1 组件划分（`components/`）

| 组件 | 职责 | 依赖 |
|------|------|------|
| `ds18b20_sensor` | 1-Wire 温度读取 | 官方 `espressif/ds18b20` + `espressif/onewire_bus`（component manager 下载） |
| `u8g2_ui` | OLED 纯显示 | `u8g2` 组件 |
| `led_indicator` | 三级灯驱动（开漏共阳） | 温度值 |
| `threshold_store` | NVS 读写（T1/T2/res） | `nvs_flash` |
| `tcp_server` | AP + TCP Server + JSON 收发 | `lwip` socket + `cJSON` |

### 5.2 任务结构（FreeRTOS，双核）

| 任务 | 核心 | 周期 | 职责 |
|------|------|------|------|
| `temp_task` | Core 1 | 分辨率决定（10bit≈200ms） | 读 DS18B20 → 更新温度 + 判级 → 驱动 LED |
| `ui_task` | Core 1 | 500ms | OLED 全缓冲刷帧 |
| `net_task` | Core 0 | 事件驱动 | 启 AP + TCP Server，1s 推 status，解析 set_config |

### 5.3 DS18B20 采样

- 分辨率默认 **10bit**（转换 187.5ms，精度 0.25°C），可配置 9/10/11/12bit。
- 采样循环：发转换命令 → 等待转换完成 → 读 scratchpad → 立即下一次，跑满 DS18B20 极限。
- 分辨率/转换时间/精度对照：

| 分辨率 | 转换时间 | 精度 |
|--------|---------|------|
| 9bit | 93.75ms | 0.5°C |
| 10bit | 187.5ms | 0.25°C |
| 11bit | 375ms | 0.125°C |
| 12bit | 750ms | 0.0625°C |

### 5.4 OLED UI（u8g2，SSD1306 128×64 单色，纯显示）

单屏无菜单。分三区：

| 区域 | 像素范围 | 内容 | 字体 |
|------|---------|------|------|
| 顶部状态栏 | y:0–14 | 左：WiFi 状态图标，右：标题「温度监测」 | 文泉驿 `wqy12_t_gb2312` |
| 中部主显示 | y:16–48 | 大字号温度（如 `26.5`）+ 右侧小号 `°C` | 数字 `logisoso32`（32px 等宽） |
| 底部信息行 | y:50–63 | 状态字（正常/警告/报警）+ `T1:30.0 T2:40.0` | `wqy12_t_gb2312` |

- 级别状态用图标位图（单色屏无法用颜色，改用实心圆/空心圆/火焰位图区分），不堆文字。
- 中文用 u8g2 内置文泉驿裁剪字体（`wqy12_t_gb2312`，约 100–200KB flash），menuconfig 启用中文字库。
- 全缓冲模式，500ms 刷帧，I2C 400kHz 下单帧约 25ms。

### 5.5 NVS 存储

- namespace：`temp_cfg`
- keys：`t1`（float）、`t2`（float）、`res`（uint8）
- 首次启动无记录时写入默认值（T1=30.0、T2=40.0、res=10）。

---

## 6. Android 端设计

### 6.1 技术栈

- Kotlin + Jetpack Compose + Material3（沿用现有空壳工程 `com.coldfish.myapplication`，只加业务）。
- 网络：Kotlin 协程 + 原生 `Socket`，后台循环读 status JSON → 更新 StateFlow。
- 配置下发：按钮触发 → 发 set_config → 等 ack → 回显。

### 6.2 权限

`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`。
（`ACCESS_FINE_LOCATION` 仅在扫描周边 WiFi 时才需要，直连已知热点**不需要**，不盲加。）

### 6.3 WiFi 连接

Android 10+ 禁止第三方 App 静默连热点。采用：引导用户系统设置手动连 `ESP32-TEMP` + App 内用 `ACCESS_WIFI_STATE` 读当前 SSID 判断是否已连上，显示连接状态。

### 6.4 UI 设计（亮色主题）

**美学方向：「极简温控仪表」** —— 纯白底 + 炭黑字 + 大字号温度 + 绿/黄/红状态色点缀 + 细分割线。

**配色 tokens：**

| token | 值 | 用途 |
|-------|-----|------|
| 背景 | `#FFFFFF` | 全局底 |
| 主文字 | `#1A1A1A` | 标题/温度 |
| 次级文字 | `#8A8A8E` | 标签/单位 |
| 状态绿 | `#34C759` | 正常 level0 |
| 状态黄 | `#FFCC00` | 警告 level1 |
| 状态红 | `#FF3B30` | 报警 level2 |
| 分割线 | `#E5E5EA` | 卡片/分隔 |

**字号（偏大）：** 温度主数字 72sp、单位 24sp、标题 20sp、状态/阈值 16sp、标签 15sp。

**图标：** Material Icons（`material-icons-extended`：`Thermostat`/`Wifi`/`Tune`/`Warning`），禁手绘 SVG。

**两个界面：**

1. 主屏（监测）：顶栏（标题 + WiFi 状态）→ 大字号温度 + 级别圆点 → 底部 T1/T2 + 分辨率。
2. 配置屏：T1/T2 两组 stepper（±0.5°C）→ 分辨率分段选择（9/10/11/12bit）→「下发到设备」按钮，收 ack 回显。

### 6.5 安全边界

本地局域网 TCP 明文 JSON，无 TLS。设备直连场景可接受，如实标注。

---

## 7. 数据流

**温度上报链路：** DS18B20 → `temp_task` 读值 → 更新温度 + 判级 → LED 亮对应灯 → `ui_task` 刷 OLED → `net_task` 每 1s 推 status → Android 显示。

**配置下发链路：** Android 发 set_config → ESP32 `net_task` 校验（0≤t1<t2≤125，res∈{9,10,11,12}）→ 写 NVS → 更新内存 → 回 ack → OLED 下次刷新显示新值 → 后续 status 同步。

**上电恢复：** ESP32 启动读 NVS → 无记录写默认 → 用内存配置运行。

---

## 8. 错误处理

| 场景 | 处理 |
|------|------|
| DS18B20 读取失败（CRC 错/未接） | 温度显示 `--.-`，保持上次 level，不误报警，日志报错 |
| 传感器异常值（>100°C，DS18B20 接触不良典型故障） | 丢弃，保持上次 |
| Android 未连 / TCP 断开 | ESP32 本地照常，OLED 显示「未连接」，继续监听重连 |
| 配置非法（t1≥t2 或越界、res 非法） | 拒绝，回 `ack ok:false`，不写 NVS |
| NVS 读写失败 | 回退默认值（30/40/10），日志报错 |

---

## 9. 测试方案

1. **ESP32 独立**：串口打印温度+配置；OLED 三区显示目测；吹风机升温验证绿→黄→红三级跳变。
2. **通讯联调**：PC 网络调试助手模拟 Android 连 `192.168.4.1:8080`，收 status、发 set_config，验证协议正确。
3. **Android 独立**：PC 模拟 TCP Server，验证 UI 显示与下发逻辑。
4. **端到端**：真机连热点，改配置 → OLED 与 Android 同步 → 断电重启 → 配置从 NVS 恢复。

---

## 10. 烧录配置

- 烧录波特率：`CONFIG_ESPTOOLPY_BAUD = 1152000`（1.152Mbps）。
- 说明：主流 USB-Serial（CP2102/CH9102）在 1.152M 稳定，比默认 460800 快 2.5 倍。
- 降级：若板子用 CH340 且在 1.152M 烧录失败，降 `921600`。
- 烧录命令：`idf.py -b 1152000 flash`（或 menuconfig → Serial flasher config → baud rate 固化）。

---

## 11. 交付物清单

| 交付物 | 位置 |
|--------|------|
| ESP32 源码 | `D:\Project\BYSJ\ESP32TEMPWITHANDROID\ESP32TEMP\` |
| Android 源码 | `D:\Project\BYSJ\ESP32TEMPWITHANDROID\AndroidRevicer\` |
| 可视化接线图（HTML） | `F:\Cache\Hermes\scripts\esp32-temp-wiring.html` |
| OLED 点阵模拟（HTML） | `F:\Cache\Hermes\scripts\esp32-temp-oled.html` |
| Android 界面原型（HTML） | `F:\Cache\Hermes\scripts\esp32-temp-android-ui.html` |
| 本设计文档 | `D:\Project\BYSJ\ESP32TEMPWITHANDROID\docs\2026-09-10-esp32-temp-android-design.md` |

> 视觉参考源：三个 HTML 原型保存后，实施阶段 AI 打开浏览器逐像素对照还原，避免纯文字描述歧义。
