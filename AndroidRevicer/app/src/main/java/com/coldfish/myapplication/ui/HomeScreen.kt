package com.coldfish.myapplication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coldfish.myapplication.data.TemperatureRepository
import com.coldfish.myapplication.data.WifiState
import com.coldfish.myapplication.net.ConnectionState
import com.coldfish.myapplication.net.TempStatus
import com.coldfish.myapplication.ui.theme.BackgroundColor
import com.coldfish.myapplication.ui.theme.LevelGreen
import com.coldfish.myapplication.ui.theme.LevelRed
import com.coldfish.myapplication.ui.theme.LevelYellow
import com.coldfish.myapplication.ui.theme.MyApplicationTheme
import com.coldfish.myapplication.ui.theme.PrimaryColor
import com.coldfish.myapplication.ui.theme.TextPrimaryColor
import com.coldfish.myapplication.ui.theme.TextSecondaryColor
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * 主屏（监测页）：顶部标题 + WiFi 状态、中部超大温度 + 级别状态、底部阈值卡片。
 *
 * 视觉参照 esp32-temp-android-ui.html 的主屏布局：
 * - 「温度监测」标题，右侧 WiFi 连接状态胶囊
 * - 中部 displayLarge(72sp) 大温度数字 + 小号 °C 单位，下方级别圆点 + 状态文字
 * - 底部「T1 警告阈值 / T2 报警阈值 / 传感器分辨率」三行阈值卡片
 *
 * 数据来源：观察 [TemperatureRepository.status]（温度状态流）、
 * [TemperatureRepository.wifiState]（WiFi 状态流）与
 * [TemperatureRepository.connectionState]（TCP 连接状态流），
 * 未收到数据时显示占位符。
 */
@Composable
fun HomeScreen(repository: TemperatureRepository, modifier: Modifier = Modifier) {
    // 观察温度状态流：可能为 null（尚未收到设备推送的数据）
    val status by repository.status.collectAsState()
    // 观察 WiFi 连接状态流
    val wifiState by repository.wifiState.collectAsState()
    // 观察 TCP 连接状态流：驱动中部状态文字的「连接中 / 未连接自动重连 / 已连接」三态
    val connectionState by repository.connectionState.collectAsState()

    HomeScreenContent(
        status = status,
        wifiState = wifiState,
        connectionState = connectionState,
        modifier = modifier
    )
}

/**
 * 主屏纯 UI 内容（与数据仓库解耦，便于 Preview 与单元测试）。
 *
 * @param status 最新温度状态，null 表示尚未连接 / 尚未收到数据
 * @param wifiState 当前 WiFi 与 ESP32 热点的连接状态
 * @param connectionState 当前 TCP Socket 连接状态（三态）
 */
@Composable
private fun HomeScreenContent(
    status: TempStatus?,
    wifiState: WifiState,
    connectionState: ConnectionState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundColor)
            // 适配边到边布局：避开状态栏与手势导航条
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ========== 顶部：标题 + WiFi 状态 ==========
        AppTopBar(wifiState = wifiState)

        // ========== 中部：超大温度 + 级别状态（占据剩余空间并垂直居中） ==========
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            TemperatureHero(status = status, connectionState = connectionState)
        }

        // ========== 底部：阈值卡片 ==========
        ThresholdCard(status = status)

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * 顶部应用栏：左侧「温度监测」标题，右侧 WiFi 状态胶囊。
 *
 * WiFi 状态映射（按规格）：
 * - [WifiState.CONNECTED_AP]  → 绿色「已连接 ESP32-TEMP」
 * - [WifiState.WRONG_NETWORK] → 黄色「请连 ESP32-TEMP」提示
 * - [WifiState.DISCONNECTED]  → 灰色「未连接」
 */
@Composable
private fun AppTopBar(wifiState: WifiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 标题前的温度计小图标（主题强调蓝）
        Icon(
            imageVector = Icons.Rounded.Thermostat,
            contentDescription = "温度监测",
            tint = PrimaryColor,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "温度监测",
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimaryColor,
        )
        Spacer(modifier = Modifier.weight(1f))
        WifiStateChip(wifiState = wifiState)
    }
}

/**
 * WiFi 状态胶囊：状态色半透明底 + 图标 + 文字。
 *
 * @param wifiState 当前 WiFi 连接状态
 */
@Composable
private fun WifiStateChip(wifiState: WifiState) {
    // 各状态对应的图标 / 颜色 / 提示文字
    val (icon, color, text) = when (wifiState) {
        // 已连上 ESP32 热点：绿色
        WifiState.CONNECTED_AP -> Triple(
            Icons.Rounded.Wifi,
            LevelGreen,
            "已连接 ESP32-TEMP",
        )
        // 连了别的网络：黄色提示切换到目标热点
        WifiState.WRONG_NETWORK -> Triple(
            Icons.Rounded.Wifi,
            LevelYellow,
            "请连 ESP32-TEMP",
        )
        // 未连接任何 WiFi：灰色
        WifiState.DISCONNECTED -> Triple(
            Icons.Rounded.WifiOff,
            TextSecondaryColor,
            "未连接",
        )
    }

    Surface(
        shape = RoundedCornerShape(50),
        // 状态色 12% 透明度作为胶囊底色
        color = color.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null, // 文字已表达语义，图标为装饰
                tint = color,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * 中部大温度区：超大温度数字 + °C 单位 + 级别圆点与状态文字。
 *
 * level 映射（与 HTML 原型一致）：
 * 0 → 绿色「正常」、1 → 黄色「警告」、2 → 红色「报警」；
 * 越界值兜底按「正常」处理。
 *
 * 无数据（status 为 null）时显示 --.-，并按 TCP 连接状态显示文案：
 * - [ConnectionState.CONNECTED]  → 灰色「已连接」（等待首包数据）
 * - [ConnectionState.CONNECTING] → 灰色「连接中…」
 * - [ConnectionState.DISCONNECTED] → 灰色「未连接，自动重连中…」
 */
@Composable
private fun TemperatureHero(status: TempStatus?, connectionState: ConnectionState) {
    // 当前要显示的温度文本：小数位跟随分辨率（9→1位 10→2位 11→3位 12→4位）
    // 舍入用 HALF_EVEN（与 C printf 的 IEEE 银行家舍入一致），避免 29.25 在双端显示成 29.2/29.3
    // null → 占位符
    val tempText = status?.let { s ->
        val decimals = when (s.res) { 9 -> 1; 10 -> 2; 11 -> 3; else -> 4 }
        BigDecimal(s.temp.toDouble())
            .setScale(decimals, RoundingMode.HALF_EVEN)
            .toPlainString()
    } ?: "--.-"

    // 级别状态：颜色 + 文字（无数据时按连接状态显示灰色文案）
    val levelColor: Color = when {
        status == null -> TextSecondaryColor
        status.level == 1 -> LevelYellow
        status.level == 2 -> LevelRed
        else -> LevelGreen // level 0 或越界值，默认安全
    }
    val levelText: String = when {
        status != null -> when (status.level) {
            1 -> "警告"
            2 -> "报警"
            else -> "正常" // level 0 或越界值，默认安全
        }
        // 已连接但尚未收到温度数据：固定文案「已连接」，等待首包推送
        connectionState == ConnectionState.CONNECTED -> "已连接"
        // 正在尝试建立连接
        connectionState == ConnectionState.CONNECTING -> "连接中…"
        // 未连接 / 已断开：TcpClient 内部会 3 秒后自动重试
        else -> "未连接，自动重连中…"
    }

    // 大温度数字 72sp + 小号 °C 单位（底部对齐，°C 稍垫高对齐数字基线）
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = tempText,
            style = MaterialTheme.typography.displayLarge,
            color = TextPrimaryColor,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "°C",
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextSecondaryColor,
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    // 级别圆点 + 状态文字
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(levelColor),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = levelText,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = levelColor,
        )
    }
}

/**
 * 底部阈值卡片：T1 警告阈值 / T2 报警阈值 / 传感器分辨率 三行。
 *
 * 底色 #F7F7FA、圆角 16dp，与 HTML 原型 .thresh-card 一致；
 * 无数据时各项值显示占位符「--」。
 */
@Composable
private fun ThresholdCard(status: TempStatus?) {
    // 卡片底色参照 HTML 原型（#F7F7FA），主题未定义该 token，故在此固定
    val cardColor = Color(0xFFF7F7FA)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cardColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            // T1 警告阈值
            ThresholdRow(
                icon = Icons.Rounded.Speed,
                label = "T1 警告阈值",
                value = status?.t1?.let { String.format(Locale.US, "%.1f °C", it) } ?: "--",
            )
            // T2 报警阈值
            ThresholdRow(
                icon = Icons.Rounded.WarningAmber,
                label = "T2 报警阈值",
                value = status?.t2?.let { String.format(Locale.US, "%.1f °C", it) } ?: "--",
            )
            // 传感器分辨率
            ThresholdRow(
                icon = Icons.Rounded.Sensors,
                label = "传感器分辨率",
                value = status?.res?.let { "$it bit" } ?: "--",
            )
        }
    }
}

/**
 * 阈值卡片中的单行：左侧小图标 + 键名，右侧值（粗体）。
 */
@Composable
private fun ThresholdRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 行图标（次级色装饰）
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextSecondaryColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        // 键名（次级色）
        Text(
            text = label,
            fontSize = 15.sp,
            color = TextSecondaryColor,
        )
        Spacer(modifier = Modifier.weight(1f))
        // 值（主色粗体）
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimaryColor,
        )
    }
}

// ========== 预览 ==========

/** 预览：已收到设备数据的典型状态（26.5°C、正常、T1=30 T2=40 res=10） */
@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomeScreenPreview() {
    MyApplicationTheme {
        HomeScreenContent(
            status = TempStatus(temp = 26.5f, level = 0, t1 = 30.0f, t2 = 40.0f, res = 10),
            wifiState = WifiState.CONNECTED_AP,
            connectionState = ConnectionState.CONNECTED,
        )
    }
}

/** 预览：未收到数据 / 未连接（自动重连中）时的占位状态 */
@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomeScreenEmptyPreview() {
    MyApplicationTheme {
        HomeScreenContent(
            status = null,
            wifiState = WifiState.DISCONNECTED,
            connectionState = ConnectionState.DISCONNECTED,
        )
    }
}

/** 预览：正在尝试连接设备时的占位状态 */
@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun HomeScreenConnectingPreview() {
    MyApplicationTheme {
        HomeScreenContent(
            status = null,
            wifiState = WifiState.CONNECTED_AP,
            connectionState = ConnectionState.CONNECTING,
        )
    }
}