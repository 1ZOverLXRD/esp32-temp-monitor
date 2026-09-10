# ESP32 温度监测 + Android 联动 — 实施计划

- 日期：2026-09-10
- 设计文档：`D:\Project\BYSJ\ESP32TEMPWITHANDROID\docs\2026-09-10-esp32-temp-android-design.md`
- 视觉参考源（实施时浏览器打开对照）：
  - `F:\Cache\Hermes\scripts\esp32-temp-wiring.html`（接线图）
  - `F:\Cache\Hermes\scripts\esp32-temp-oled.html`（OLED 点阵模拟）
  - `F:\Cache\Hermes\scripts\esp32-temp-android-ui.html`（Android 界面）

## 环境

- ESP32：IDF `C:\Espressif\frameworks\esp-idf-v5.5.2`，编译前激活 `export.sh`；组件下载走 7890 代理。
- Android：JDK `D:\Program Files\Javas\dragonwell-17.0.16`，Gradle + Compose 空壳工程 `com.coldfish.myapplication`。

## 子系统边界

| 子系统 | 语言 | 独立可跑 | 目录 |
|--------|------|---------|------|
| ESP32 端 | C | 是 | `ESP32TEMP\` |
| Android 端 | Kotlin | 是 | `AndroidRevicer\` |

两端零耦合（仅靠 TCP JSON 协议约定），可完全并行开发。

---

## ESP32 端任务

### Task 0：脚手架 + 依赖声明

**Files:**
- Create: `ESP32TEMP\main\idf_component.yml`（声明 `espressif/ds18b20`、`espressif/onewire_bus`、`u8g2` 依赖）
- Create: `ESP32TEMP\components\`（空目录 + 各组件 CMakeLists）
- Create: `ESP32TEMP\sdkconfig.defaults`（`CONFIG_ESPTOOLPY_BAUD=1152000`、启用 u8g2 中文字库）

**验证：** `idf.py reconfigure` 能拉取组件（走代理）。

### Task E1：threshold_store（NVS 配置存储）

**Files:** `components/threshold_store/threshold_store.h` + `.c` + `CMakeLists.txt`

**Interfaces:**
```c
typedef struct { float t1; float t2; uint8_t res; } threshold_cfg_t;  // res=9/10/11/12
esp_err_t thr_init(void);                 // 读 NVS，无记录写默认 30.0/40.0/10
void thr_get(threshold_cfg_t *cfg);       // 读内存
esp_err_t thr_set(const threshold_cfg_t *cfg); // 校验 0≤t1<t2≤125、res∈{9,10,11,12}，写 NVS+内存
```
**规格：** namespace `temp_cfg`，keys `t1`/`t2`/`res`；校验失败返回非 OK 且不写。

### Task E2：led_indicator（三色 LED 开漏共阳）

**Files:** `components/led_indicator/led_indicator.h` + `.c` + `CMakeLists.txt`

**Interfaces:**
```c
esp_err_t led_init(void);     // GPIO16/17/18 配 GPIO_MODE_OUTPUT_OD
void led_set_level(int level); // 0=绿(16拉低) 1=黄(17拉低) 2=红(18拉低)，其余释放(输出1)
```
**规格：** 共阳开漏，低电平点亮；同一时刻仅一路拉低。

### Task E3：ds18b20_sensor（温度读取）

**Files:** `components/ds18b20_sensor/ds18b20_sensor.h` + `.c` + `CMakeLists.txt`

**Interfaces:**
```c
esp_err_t temp_init(void);                 // GPIO4 内部上拉，onewire 总线初始化
esp_err_t temp_set_resolution(uint8_t res); // 9/10/11/12bit
esp_err_t temp_read(float *out);           // 读一次，CRC 错/失败返回非 OK
```
**规格：** 用官方 `onewire_bus` + `ds18b20` 组件；采样循环=转换完即读即下一次；异常值（>100°C）丢弃。

### Task E4：u8g2_ui（OLED 纯显示）

**Files:** `components/u8g2_ui/u8g2_ui.h` + `.c` + `CMakeLists.txt`

**Interfaces:**
```c
esp_err_t ui_init(void);  // I2C GPIO21/22，SSD1306 128x64，全缓冲
void ui_render(float temp, int level, const threshold_cfg_t *cfg, bool wifi_connected);
```
**规格：** 三区布局（顶部 WiFi+标题 / 中部大数字温度 / 底部状态+阈值）；数字 `logisoso32`、中文 `wqy12`；单色级别用图标区分。

### Task E5：tcp_server（AP + TCP + JSON）

**Files:** `components/tcp_server/tcp_server.h` + `.c` + `CMakeLists.txt`

**Interfaces:**
```c
esp_err_t net_init(void);  // 启 AP(ESP32-TEMP/12345678) + TCP Server:8080，内部建 net_task
```
**Consumes:** `thr_get`/`thr_set`、`temp_read`（通过 include 头文件调用）
**规格：** 每 1s 推 `{"type":"status","temp":..,"level":..,"t1":..,"t2":..,"res":..}`；收 `set_config` → `thr_set` → 回 `ack`；cJSON 解析，`\n` 分隔。

### Task E6：main.c 集成

**Files:** Modify `ESP32TEMP\main\main.c`、`main\CMakeLists.txt`（REQUIRES 各组件）

**规格：** `nvs_flash_init` → `thr_init` → `led_init` → `temp_init`+`temp_set_resolution` → `ui_init` → `net_init` → 建 `temp_task`（采样+判级+LED）、`ui_task`（500ms 渲染）。

---

## Android 端任务

### Task 0：权限 + 依赖

**Files:**
- Modify `AndroidManifest.xml`（加 `INTERNET`/`ACCESS_NETWORK_STATE`/`ACCESS_WIFI_STATE`/`CHANGE_WIFI_STATE`）
- Modify `app/build.gradle.kts`（加 `material-icons-extended` 依赖）

### Task A1：网络层

**Files:** `net/TcpClient.kt` + `net/JsonModels.kt`

**Interfaces:**
```kotlin
data class TempStatus(val temp: Float, val level: Int, val t1: Float, val t2: Float, val res: Int)
data class Config(val t1: Float, val t2: Float, val res: Int)
class TcpClient(host: String = "192.168.4.1", port: Int = 8080) {
    suspend fun connect(); fun disconnect()
    val statusFlow: Flow<TempStatus>
    suspend fun sendConfig(cfg: Config)
}
```
**规格：** 协程 + 原生 Socket，按行读 JSON（`\n` 分隔），`org.json` 解析；断线重连。

### Task A2：数据层 + WiFi 状态

**Files:** `data/TemperatureRepository.kt` + `data/WifiStatus.kt`

**Interfaces:**
```kotlin
class TemperatureRepository(private val client: TcpClient) {
    val status: StateFlow<TempStatus?>
    val wifiState: StateFlow<WifiState>  // CONNECTED_AP / WRONG_NETWORK / DISCONNECTED
    suspend fun sendConfig(cfg: Config)
}
```
**规格：** 读当前 SSID 判断是否连 `ESP32-TEMP`；`status` 由 `statusFlow` 映射。

### Task A3：主屏 UI

**Files:** `ui/HomeScreen.kt` + `ui/theme/`（配色 tokens）

**Consumes:** `TemperatureRepository.status`、`wifiState`
**规格：** 亮色主题，温度 72sp、状态色 `#34C759/#FFB800/#FF3B30`；大温度 + 级别圆点 + 阈值卡片。图标 Material Icons。

### Task A4：配置屏 UI

**Files:** `ui/ConfigScreen.kt`

**Consumes:** `TemperatureRepository.sendConfig`
**规格：** T1/T2 stepper（±0.5，约束 T1<T2）+ 分辨率 9/10/11/12 分段 +「下发」按钮，收 ack 回显。

### Task A5：MainActivity + 导航集成

**Files:** Modify `MainActivity.kt`（底部导航双屏 + Repository 装配）

---

## 依赖关系图

```
层级0: ESP32-Task0, Android-Task0            (脚手架)
层级1: E1,E2,E3,E4 + A1,A2                   (独立组件/层)
层级2: E5(依赖E1/E3) + A3,A4(依赖A2)         (组合)
层级3: E6(依赖E1~E5) + A5(依赖A1~A4)         (集成)
层级4: 双端编译验证
```

## 并行分批策略

| 批次 | 任务 | 内容 | 说明 |
|------|------|------|------|
| 批0 | ESP32-T0 + Android-T0 | 双端脚手架 | 无依赖 |
| 批1 | E1,E2,E3,E4 + A1,A2 | 6 任务并行 | 无依赖组件/层 |
| 批2 | E5 + A3,A4 | 3 任务并行 | 依赖批1 |
| 批3 | E6 + A5 | 2 任务并行 | 集成 |
| 批4 | 全量编译 | ESP32 `idf.py build` + Android `gradlew assembleDebug` | 验证 |

## 验证命令

```bash
# ESP32 编译（git-bash，先激活 IDF）
cd /d/Project/BYSJ/ESP32TEMPWITHANDROID/ESP32TEMP
source /c/Espressif/frameworks/esp-idf-v5.5.2/export.sh
idf.py build

# Android 编译（走代理）
cd /d/Project/BYSJ/ESP32TEMPWITHANDROID/AndroidRevicer
./gradlew assembleDebug
```

## 执行策略

双端完全独立，建议 delegate_task 并行：批0 起 2 个子 agent，批1 起 6 个，批2 起 3 个，批3 起 2 个，批4 编译验证。每批完成后核对接口签名一致性再派下一批。
