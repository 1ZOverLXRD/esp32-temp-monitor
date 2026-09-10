package com.coldfish.myapplication.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.coldfish.myapplication.net.Config
import com.coldfish.myapplication.net.ConnectionState
import com.coldfish.myapplication.net.TcpClient
import com.coldfish.myapplication.net.TempStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 温度数据仓库。
 *
 * 负责：
 * 1. 监听 TCP 客户端推送来的温度状态流，暴露给 UI 层（[status]）；
 * 2. 根据当前 WiFi 网络的默认网关判断是否连上 ESP32 热点（[wifiState]）；
 *    —— 用网关 IP（192.168.4.1）而非 SSID：读 SSID 在 Android 13+ 需要
 *      NEARBY_WIFI_DEVICES 运行时权限，未授权时恒为 <unknown ssid>；网关判断零权限。
 * 3. 转发 TCP 客户端的 Socket 连接状态（[connectionState]），供 UI 层显示连接三态；
 * 4. 将 UI 层下发的配置（阈值等）转发给 TCP 客户端（[sendConfig]）。
 */
class TemperatureRepository(
    private val client: TcpClient,
    context: Context,
) {

    /** ESP32 设备热点（softAP）为 DHCP 分配的网关地址，即 ESP32 自身 IP */
    private val targetGateway: String = "192.168.4.1"

    /** 保存 applicationContext，避免持有 Activity 等短生命周期对象造成内存泄漏。 */
    private val appContext: Context = context.applicationContext

    /** 仓库自身的协程作用域，用于在初始化时订阅温度状态流。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 最新的温度状态（暂未收到数据时为 null），供 UI 层观察。 */
    private val _status = MutableStateFlow<TempStatus?>(null)

    /** 对外暴露的温度状态流。 */
    val status: StateFlow<TempStatus?> = _status.asStateFlow()

    /** 当前的 WiFi 连接状态，供 UI 层观察。 */
    private val _wifiState = MutableStateFlow(WifiState.DISCONNECTED)

    /** 对外暴露的 WiFi 连接状态流。 */
    val wifiState: StateFlow<WifiState> = _wifiState.asStateFlow()

    /** 对外暴露的 TCP 连接状态流（转发自客户端，DISCONNECTED / CONNECTING / CONNECTED）。 */
    val connectionState: StateFlow<ConnectionState> = client.connectionState

    /**
     * 网络回调：WiFi 连接/断开/属性变化时实时刷新 [wifiState]，
     * 保证手机连上 ESP32 热点后 UI 立即更新（隐藏提示条、图标变绿）。
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshWifiState()
        override fun onLost(network: Network) = refreshWifiState()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refreshWifiState()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshWifiState()
    }

    init {
        // 注册默认网络回调：WiFi 状态变化时实时更新（无需注销，随进程生命周期）
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(networkCallback)

        // 订阅 TCP 客户端推送过来的温度数据，写入状态流
        scope.launch {
            client.statusFlow.collect { tempStatus ->
                _status.value = tempStatus
            }
        }

        // 初始刷新一次（App 启动时可能已在热点上）
        refreshWifiState()
    }

    /** 重新计算并发布 WiFi 连接状态 */
    private fun refreshWifiState() {
        _wifiState.value = readWifiState()
    }

    /**
     * 读取当前 WiFi 连接状态（零权限实现，基于网络属性 + 默认网关）。
     *
     * 判断规则：
     * - 无活跃网络 / 非 WiFi   -> [WifiState.DISCONNECTED]；
     * - WiFi 且默认网关 == 192.168.4.1（ESP32 热点） -> [WifiState.CONNECTED_AP]；
     * - WiFi 但网关为其他     -> [WifiState.WRONG_NETWORK]。
     */
    private fun readWifiState(): WifiState {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return WifiState.DISCONNECTED
        val capabilities = cm.getNetworkCapabilities(network) ?: return WifiState.DISCONNECTED
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return WifiState.DISCONNECTED
        }
        val linkProps = cm.getLinkProperties(network) ?: return WifiState.DISCONNECTED
        val gateway = linkProps.routes.firstOrNull { it.isDefaultRoute }?.gateway
        val gatewayIp = gateway?.hostAddress
        return if (gatewayIp == targetGateway) WifiState.CONNECTED_AP else WifiState.WRONG_NETWORK
    }

    /**
     * 将 UI 层配置（温度阈值等）转发给 TCP 客户端。
     */
    suspend fun sendConfig(cfg: Config) {
        client.sendConfig(cfg)
    }
}