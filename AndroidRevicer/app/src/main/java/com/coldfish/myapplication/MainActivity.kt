package com.coldfish.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.coldfish.myapplication.data.TemperatureRepository
import com.coldfish.myapplication.data.WifiState
import com.coldfish.myapplication.net.TcpClient
import com.coldfish.myapplication.ui.ConfigScreen
import com.coldfish.myapplication.ui.HomeScreen
import com.coldfish.myapplication.ui.theme.BackgroundColor
import com.coldfish.myapplication.ui.theme.MyApplicationTheme
import com.coldfish.myapplication.ui.theme.PrimaryColor
import com.coldfish.myapplication.ui.theme.SurfaceColor
import com.coldfish.myapplication.ui.theme.TextSecondaryColor
import kotlinx.coroutines.launch

/**
 * 应用入口 Activity：
 * - 装配 [TcpClient]（长连接）与 [TemperatureRepository]（数据仓库，UI 唯一数据来源）；
 * - onCreate 启动 TCP 连接（失败静默，由 UI 呈现「未连接」状态）；
 * - onDestroy 释放连接，保证生命周期内连接资源不泄漏。
 */
class MainActivity : ComponentActivity() {

    /** TCP 客户端：Activity 生命周期内单例，负责与 ESP32 建立长连接 */
    private val client = TcpClient()

    /** 温度数据仓库：懒加载（首次被 UI 引用时才创建），内部持有 applicationContext 避免泄漏 */
    private val repository by lazy { TemperatureRepository(client, applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 启动 TCP 连接：挂起直到建连成功或超时（10s）。
        // 未连 ESP32-TEMP 热点 / 设备不在线时抛异常，这里静默吞掉，
        // 界面根据 wifiState / status 流自动显示「未连接」提示，无需用户干预。
        lifecycleScope.launch {
            try {
                client.connect()
            } catch (e: Exception) {
                // 未连热点等场景：静默，UI 显示未连接状态
            }
        }

        setContent {
            MyApplicationTheme {
                MainScreen(repository = repository)
            }
        }
    }

    override fun onDestroy() {
        // 断开 TCP 连接：取消读写协程并关闭 Socket（disconnect 幂等，可安全重复调用）
        client.disconnect()
        super.onDestroy()
    }
}

/**
 * 应用主界面骨架：
 * - 顶部：WiFi 未连接提示条（仅在未连上 ESP32-TEMP 热点时显示，细条 + 次级文字色）；
 * - 中部：当前选中 tab 的内容区（监测页 [HomeScreen] / 配置页 [ConfigScreen]）；
 * - 底部：Material3 [NavigationBar] 双 tab 导航（不引入 navigation-compose，用状态切换）。
 *
 * @param repository 温度数据仓库（由 MainActivity 装配后传入）
 */
@Composable
fun MainScreen(repository: TemperatureRepository) {
    // 当前选中的 tab 索引：0 = 监测，1 = 配置
    var selectedTab by remember { mutableStateOf(0) }

    // 观察 WiFi 连接状态，决定是否显示顶部提示条
    val wifiState by repository.wifiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundColor)
    ) {
        // ---------- 顶部：未连接提示条 ----------
        if (wifiState != WifiState.CONNECTED_AP) {
            Text(
                text = "未连接 ESP32-TEMP 热点，请在系统设置中连接",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondaryColor,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    // 提示条位于屏幕最顶部，需自行避开状态栏（项目为边到边布局）
                    .statusBarsPadding()
                    // 浅灰细底，与各卡片底色一致（#F7F7FA）
                    .background(Color(0xFFF7F7FA))
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        // ---------- 中部：当前 tab 内容区 ----------
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (selectedTab) {
                // 监测页：其内部已自带 statusBarsPadding / navigationBarsPadding
                0 -> HomeScreen(repository = repository)
                // 配置页：内部未处理状态栏 inset，此处补齐；
                // 底部导航栏在流内布局（不与内容重叠），故无需 navigationBarsPadding
                else -> ConfigScreen(
                    repository = repository,
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                )
            }
        }

        // ---------- 底部：双 tab 导航栏 ----------
        NavigationBar(containerColor = SurfaceColor) {
            NavigationBarItem(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                icon = { Icon(Icons.Rounded.Thermostat, contentDescription = "监测") },
                label = { Text("监测") },
                colors = navItemColors(),
            )
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                icon = { Icon(Icons.Rounded.Tune, contentDescription = "配置") },
                label = { Text("配置") },
                colors = navItemColors(),
            )
        }
    }
}

/** 底部导航项配色：选中主色高亮 + 浅蓝指示条，未选中次级灰（与主题 token 一致） */
@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = PrimaryColor,
    selectedTextColor = PrimaryColor,
    indicatorColor = PrimaryColor.copy(alpha = 0.12f),
    unselectedIconColor = TextSecondaryColor,
    unselectedTextColor = TextSecondaryColor,
)
