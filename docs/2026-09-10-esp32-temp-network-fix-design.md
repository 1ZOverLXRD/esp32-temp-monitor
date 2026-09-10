# ESP32 温度监测 — 网络通讯健壮性修复 + 双端日志（设计文档）

- 日期：2026-09-10
- 前置设计：`docs/2026-09-10-esp32-temp-android-design.md`
- 状态：设计定稿（待用户 review）

---

## 1. 问题背景

联调发现：手机连上 ESP32-TEMP 热点（ESP32 日志确认 station join + DHCP 192.168.4.2），打开 App 后温度一直显示 `--.-`（「等待连接」）。

**根因**（已通过代码审查 + logcat 抓取确认）：
- Android `TcpClient.connect()` 是一次性连接，失败抛 `IOException` 后被 `MainActivity` 的 `catch (e: Exception) {}` **静默吞掉**；
- **无自动重连机制**（代码注释明确"不要求自动重连"），连接失败后永不重试 → statusFlow 永远为空 → UI 永远空态；
- logcat 抓取证实 App 无任何网络异常输出（异常全被吞），无法定位失败原因。

**用户补充需求**：网络通讯部分（双端）增加日志，便于定位与演示。

## 2. 变更范围

| 端 | 文件 | 变更 |
|----|------|------|
| Android | `net/TcpClient.kt` | 自动重连守护循环 + 连接状态流 + 日志 |
| Android | `MainActivity.kt` | 启动守护循环（onCreate）、停止（onDestroy） |
| Android | `ui/HomeScreen.kt` | 连接状态显示（连接中/重连中/已连接） |
| ESP32 | `components/tcp_server/tcp_server.c` | handle_line 收到 set_config 与写入结果日志 |

不涉及：协议格式、阈值逻辑、LED/OLED 逻辑、AP 配置（保持密码 `12345678`，用户已确认无所谓）。

## 3. Android 连接层设计（TcpClient.kt）

### 3.1 连接状态机

```
DISCONNECTED ──connect()──> CONNECTING ──成功──> CONNECTED
     ▲                          │                 │
     │       失败/超时/断开        │                 │ 读循环异常退出
     └────<──────────────────────┴────<───────────┘
                （3s 后自动回到 CONNECTING）
```

- 新增 `enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }`
- 新增 `val connectionState: StateFlow<ConnectionState>`（初始 `DISCONNECTED`，由 Repository 转发或 UI 直接消费）

### 3.2 守护循环（connect 改造）

```kotlin
@Volatile private var active = false   // disconnect 置 false 停止循环

suspend fun connect() {
    disconnect()                        // 幂等清理旧连接
    active = true
    while (active) {
        _connectionState.value = CONNECTING
        Log.i(TAG, "尝试连接 $host:$port")
        try {
            val s = withContext(Dispatchers.IO) {
                Socket().apply {
                    connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)  // 10s
                    soTimeout = READ_TIMEOUT_MS                                  // 5s
                }
            }
            socket = s
            writer = s.getOutputStream().bufferedWriter(Charsets.UTF_8)
            _connectionState.value = CONNECTED
            Log.i(TAG, "连接成功 $host:$port")
            // 阻塞读循环；断开/异常时抛异常退出 → 进入重连
            withContext(Dispatchers.IO) {
                s.getInputStream().bufferedReader(Charsets.UTF_8).use { readLoop(it) }
            }
        } catch (e: Exception) {
            _connectionState.value = DISCONNECTED
            Log.w(TAG, "连接断开/失败: ${e.message ?: e.javaClass.simpleName}，3s 后重试")
            cleanup()
            delay(RETRY_DELAY_MS)   // 固定 3s（YAGNI：暂不做指数退避）
        }
    }
}
```

- `readLoop`：保留按行解析 status 并 emit，**新增每收到一条 status 打一条 `Log.d`**（temp/level）；
- `sendConfig`：**新增发送前 `Log.i("发送 set_config: t1=.. t2=.. res=..")`**；
- `disconnect()`：置 `active=false` + `scope.cancel()` + `cleanup()` + `_connectionState.value = DISCONNECTED`（幂等，可多次调用，重复进入无副作用）；
- 常量：`CONNECT_TIMEOUT_MS = 10_000`、`READ_TIMEOUT_MS = 5_000`、`RETRY_DELAY_MS = 3_000`（均已有/新增）。

### 3.3 日志标签与级别

| 事件 | 级别 | 内容 |
|------|------|------|
| 尝试连接 | I | `尝试连接 192.168.4.1:8080` |
| 连接成功 | I | `连接成功 192.168.4.1:8080` |
| 连接失败/断开 | W | `连接断开/失败: <原因>，3s 后重试` |
| 收到 status | D | `收到 status: temp=26.5 level=0 t1=30.0 t2=40.0 res=10` |
| 发送配置 | I | `发送 set_config: t1=30.0 t2=40.0 res=10` |

TAG 统一为 `"TcpClient"`。status 每 1s 一条 D 级（debug 级别，正式包默认不输出，不刷屏）。

## 4. Android UI 层（MainActivity + HomeScreen）

- `MainActivity.onCreate`：`lifecycleScope.launch { client.connect() }`（守护循环常驻，自动重连）；`onDestroy`：`client.disconnect()`（停止重连，防泄漏）。
- `HomeScreen`：消费 `connectionState`（经 Repository 转发或直接持有 client 引用——按现有架构经 Repository 增加 `connectionState` 转发）：
  - `CONNECTED` → 显示温度（现有逻辑）
  - `CONNECTING` → 灰色「连接中…」
  - `DISCONNECTED` → 灰色「未连接，自动重连中…」
  - 替换现有固定「等待连接」空态文案。

## 5. ESP32 网络日志（tcp_server.c）

`handle_line` 中，解析出 t1/t2/res 并组装 cfg 后、调用 `thr_set` 前后各加一条日志：

```c
ESP_LOGI(TAG, "收到 set_config: t1=%.1f t2=%.1f res=%u", (double)cfg.t1, (double)cfg.t2, cfg.res);
esp_err_t ret = thr_set(&cfg);
if (ret == ESP_OK) {
    ESP_LOGI(TAG, "配置已写入 NVS 并生效");
} else {
    ESP_LOGW(TAG, "配置非法，拒绝写入");
}
```

已有日志保留：客户端连接/断开/recv 错误/send 失败/JSON 解析失败。

## 6. 错误处理

| 场景 | 处理 |
|------|------|
| TCP 连接失败/超时（10s） | 日志 W + 3s 后自动重试（无限循环直到成功或 disconnect） |
| 读超时（5s 无数据） | readLine 抛异常 → 断开 → 重连 |
| 对端关闭（ESP32 重启/断网） | readLine 返回 null → 断开 → 重连 |
| 重复进入 App / 旋转屏幕 | 旧 client disconnect（幂等）→ 新 client connect，无并发双连接 |
| 配置下发时未连接 | sendConfig 抛 IOException → ConfigScreen 已捕获显示失败态 |

## 7. 测试方案

1. **连热点后开 App**：logcat 见 `连接成功`，HomeScreen 显示实时温度；
2. **断 WiFi / 关 ESP32**：logcat 见 `连接断开/失败...3s 后重试`，UI 显示「未连接，自动重连中…」；恢复后自动连上出数据；
3. **配置下发**：ESP32 串口出现 `收到 set_config` + `配置已写入 NVS 并生效` 两条日志，App 显示已生效；
4. **反复进出 App**：无崩溃、无重复连接泄漏；
5. **未连热点直接开 App**：UI 显示「连接中…」→「未连接，自动重连中…」，连上热点后自动恢复。

## 8. 验证命令

```bash
# Android 构建（JVM 12g 常驻）
cd /d/Project/BYSJ/ESP32TEMPWITHANDROID/AndroidRevicer
JAVA_HOME="D:/Program Files/Javas/dragonwell-17.0.16" GRADLE_OPTS="-Xmx12g -XX:MaxMetaspaceSize=1g" ./gradlew.bat assembleDebug

# 安装到设备
adb -s NJMNEUHEHI9PUGAI install -r app/build/outputs/apk/debug/app-debug.apk

# 抓 App 日志
adb -s NJMNEUHEHI9PUGAI logcat -d -s TcpClient

# ESP32 构建（ccache 已启用）
python F:/Cache/Hermes/scripts/esp32_build.py
```
