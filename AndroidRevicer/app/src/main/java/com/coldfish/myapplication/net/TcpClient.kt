package com.coldfish.myapplication.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 连接状态枚举（顶层定义，供外部以 net.ConnectionState 方式 import 引用）：
 * - DISCONNECTED：未连接 / 已断开
 * - CONNECTING：正在尝试建立连接
 * - CONNECTED：已建立连接
 */
enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED
}

/**
 * TCP 网络层：与 ESP32 设备建立原生 Socket 长连接，收发 JSON 行协议。
 *
 * 协议说明：
 * - 上行（设备 → App）：设备每 1 秒推送一行 JSON，如
 *   {"type":"status","temp":26.5,"level":0,"t1":30.0,"t2":40.0,"res":10}
 * - 下行（App → 设备）：下发配置，如
 *   {"type":"set_config","t1":30.0,"t2":40.0,"res":10}
 * - 设备对下行命令回 {"type":"ack","ok":true,...}
 *
 * 本类只处理 status 消息并推送到 [statusFlow]，其他消息（ack 等）忽略。
 *
 * 自动重连守护循环：[connect] 内部持续尝试连接，连接失败或意外断开后
 * 等待 [RETRY_DELAY_MS]（3 秒）自动重试，直到 [disconnect] 将 active 置
 * false 停止。连接状态通过 [connectionState] 对外暴露，收发动作均打日志。
 *
 * @param host 设备 IP（ESP32 AP 模式默认 192.168.4.1）
 * @param port 设备端口（默认 8080）
 */
class TcpClient(
    private val host: String = "192.168.4.1",
    private val port: Int = 8080,
) {
    /** 内部协程作用域：保留生命周期语义（disconnect 时取消并重建），供后续扩展使用 */
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 自动重连守护标志：true 时持续尝试连接；disconnect 置 false 后循环退出 */
    @Volatile
    private var active = false

    /** 当前 Socket，volatile 保证多线程可见 */
    @Volatile
    private var socket: Socket? = null

    /** 上行输出流（写 JSON 行给设备） */
    @Volatile
    private var writer: BufferedWriter? = null

    /** 状态流：设备推送的 type=status 消息经解析后 emit 到这里 */
    private val _statusFlow = MutableSharedFlow<TempStatus>(extraBufferCapacity = 16)
    val statusFlow: Flow<TempStatus> = _statusFlow.asSharedFlow()

    /** 连接状态流：DISCONNECTED / CONNECTING / CONNECTED，供 UI 展示连接指示 */
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * 建立连接并启动自动重连守护循环。
     *
     * 挂起运行直到 [disconnect] 被调用（active 置 false）为止：
     * - 每次尝试前先置状态为 CONNECTING 并打日志；
     * - 连接成功后置 CONNECTED，进入读取循环（按行解析设备推送）；
     * - 连接失败或读取循环断开（异常/对端关闭）时，置 DISCONNECTED、
     *   清理资源并等待 3 秒后自动重试。
     */
    suspend fun connect() {
        // 幂等清理：重复连接前先断开旧连接
        disconnect()
        active = true
        while (active) {
            _connectionState.value = ConnectionState.CONNECTING
            Log.i(TAG, "尝试连接 $host:$port")
            try {
                // 分阶段日志：精确区分卡在"建 Socket / connect / 拿流"哪一步
                Log.d(TAG, "[连接] 创建 Socket")
                val s = Socket()
                Log.d(TAG, "[连接] 发起 connect ${host}:${port} 超时${CONNECT_TIMEOUT_MS}ms")
                withContext(Dispatchers.IO) {
                    s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    s.soTimeout = READ_TIMEOUT_MS
                }
                Log.i(TAG, "[连接] connect 返回 local=${s.localAddress}:${s.localPort} remote=${s.inetAddress}:${s.port}")
                socket = s
                // 提前创建输出流并缓存，保证连接后 sendConfig 立即可用
                writer = s.getOutputStream().bufferedWriter(Charsets.UTF_8)
                _connectionState.value = ConnectionState.CONNECTED
                Log.i(TAG, "连接成功 $host:$port")
                // 读取循环：持续按行解析设备推送，直到断开（异常抛出）或对端关闭
                withContext(Dispatchers.IO) {
                    s.getInputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                        readLoop(reader)
                    }
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.DISCONNECTED
                // 完整异常堆栈（含 Caused by / 系统调用失败原因），便于诊断
                Log.e(TAG, "连接失败: ${e.message}", e)
                cleanup()
                delay(RETRY_DELAY_MS)
            }
        }
    }

    /**
     * 断开连接：停止自动重连守护循环（active 置 false）、置状态为
     * DISCONNECTED、取消内部作用域并关闭 Socket。
     * 幂等，可多次调用。
     */
    fun disconnect() {
        active = false
        _connectionState.value = ConnectionState.DISCONNECTED
        scope.cancel()
        cleanup()
        // 取消后的 scope 无法复用，重建以便下次 connect
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /**
     * 下发配置到设备。
     *
     * 发送 {"type":"set_config","t1":..,"t2":..,"res":..} 一行。
     * 未连接或发送失败时抛出 IOException（由调用方捕获处理）。
     *
     * @param cfg 要下发的配置
     */
    suspend fun sendConfig(cfg: Config) {
        val w = writer ?: throw IOException("Socket 未连接，无法下发配置")
        withContext(Dispatchers.IO) {
            val json = JSONObject()
                .put("type", "set_config")
                .put("t1", cfg.t1.toDouble())
                .put("t2", cfg.t2.toDouble())
                .put("res", cfg.res)
                .toString()
            Log.i(TAG, "发送 set_config: t1=${cfg.t1} t2=${cfg.t2} res=${cfg.res}")
            w.write(json)
            w.newLine()
            w.flush()
        }
    }

    /**
     * 读取循环：按行读取设备推送，解析 JSON，过滤 type=status 后 emit 到 statusFlow。
     */
    private suspend fun readLoop(reader: BufferedReader) {
        while (true) {
            val line = reader.readLine() ?: break // null 表示对端关闭，结束循环
            if (line.isBlank()) continue
            try {
                val obj = JSONObject(line)
                if (obj.optString("type") == "status") {
                    val s = parseStatus(obj)
                    Log.d(TAG, "收到 status: temp=${s.temp} level=${s.level} t1=${s.t1} t2=${s.t2} res=${s.res}")
                    _statusFlow.emit(s)
                }
                // 其他 type（ack 等）当前不处理，忽略
            } catch (e: Exception) {
                // 单行解析失败不中断读取循环，跳过继续
            }
        }
    }

    /**
     * 从 status JSON 解析出 [TempStatus]。
     */
    private fun parseStatus(obj: JSONObject): TempStatus = TempStatus(
        temp = obj.optDouble("temp", 0.0).toFloat(),
        level = obj.optInt("level", 0),
        t1 = obj.optDouble("t1", 0.0).toFloat(),
        t2 = obj.optDouble("t2", 0.0).toFloat(),
        res = obj.optInt("res", 0),
    )

    /** 关闭 Socket 与输出流 */
    private fun cleanup() {
        try {
            writer?.close()
        } catch (_: IOException) {
        }
        writer = null
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }

    companion object {
        /** 日志标签 */
        private const val TAG = "TcpClient"
        /** 连接超时：10 秒 */
        private const val CONNECT_TIMEOUT_MS = 10_000
        /** 读超时：5 秒（设备 1s 推一次，5s 无数据视为断线） */
        private const val READ_TIMEOUT_MS = 5_000
        /** 断线/失败后的自动重连间隔：3 秒 */
        private const val RETRY_DELAY_MS = 3_000L
    }
}