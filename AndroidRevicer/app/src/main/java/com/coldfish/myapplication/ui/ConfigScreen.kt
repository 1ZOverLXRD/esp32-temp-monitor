package com.coldfish.myapplication.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coldfish.myapplication.data.TemperatureRepository
import com.coldfish.myapplication.net.Config
import com.coldfish.myapplication.ui.theme.BackgroundColor
import com.coldfish.myapplication.ui.theme.DividerColor
import com.coldfish.myapplication.ui.theme.LevelGreen
import com.coldfish.myapplication.ui.theme.LevelRed
import com.coldfish.myapplication.ui.theme.PrimaryColor
import com.coldfish.myapplication.ui.theme.TextPrimaryColor
import com.coldfish.myapplication.ui.theme.TextSecondaryColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 配置屏：阈值（T1/T2）与 DS18B20 分辨率设置，并下发到设备。
 *
 * 视觉参照 F:\Cache\Hermes\scripts\esp32-temp-android-ui.html 配置页：
 * - T1/T2 步进器：左右 -/+ 按钮，每次 ±0.5℃，中间大号显示当前值；
 * - DS18B20 分辨率四段选择（9/10/11/12 bit），选中项主色高亮；
 * - 「下发到设备」主色大按钮：点击后经 [TemperatureRepository.sendConfig] 下发，
 *   带 下发中 / 已下发（绿）/ 下发失败（红）三态反馈，3 秒后恢复默认；
 * - 底部 hint 行展示下发流程（set_config → 写 NVS → 回 ack），
 *   并动态显示设备回传的最新配置（来自 status 流）。
 */
@Composable
fun ConfigScreen(repository: TemperatureRepository, modifier: Modifier = Modifier) {

    // ---------- 本地编辑状态 ----------
    // 初始值优先取设备当前状态（ack 后 status 回传的最新配置），尚未收到数据时用默认值
    val initialStatus = repository.status.value
    var t1 by remember { mutableStateOf(initialStatus?.t1 ?: DEFAULT_T1) }
    var t2 by remember { mutableStateOf(initialStatus?.t2 ?: DEFAULT_T2) }
    var res by remember { mutableStateOf(initialStatus?.res ?: DEFAULT_RES) }

    // ---------- 下发状态机 ----------
    // 下发中 → 协程挂起发送；成功/失败后显示 3 秒再恢复为默认态
    var sendState by remember { mutableStateOf(SendState.IDLE) }

    // 本屏协程作用域：承载 sendConfig 调用与 3 秒恢复倒计时
    val scope = rememberCoroutineScope()

    // ---------- 步进约束（保证 T1 < T2 恒成立，对齐到 0.1℃） ----------
    // T1 增加被禁用：再加 0.5 就 >= T2（T1 必须严格小于 T2）
    val t1PlusEnabled = norm(t1) + STEP <= norm(t2) - EPS
    // T2 减少被禁用：再减 0.5 就 <= T1
    val t2MinusEnabled = norm(t2) - STEP >= norm(t1) + EPS

    // T1 减少：下限 0℃；T1 增加：受 T1<T2 约束
    val onT1Minus = { t1 = norm(t1 - STEP).coerceAtLeast(MIN_TEMP) }
    val onT1Plus = { if (t1PlusEnabled) t1 = norm(t1 + STEP) }
    // T2 减少：受 T1<T2 约束；T2 增加：上限 125℃（DS18B20 量程上限）
    val onT2Minus = { if (t2MinusEnabled) t2 = norm(t2 - STEP) }
    val onT2Plus = { t2 = norm(t2 + STEP).coerceAtMost(MAX_TEMP) }

    // ---------- 下发动作 ----------
    // 点击 → 协程发送 set_config；发送异常（如未连接设备）捕获并进入失败态
    val onSendClick: () -> Unit = {
        scope.launch {
            // 进入下发中状态（按钮禁用，防重复点击）
            sendState = SendState.SENDING
            try {
                // 发送前对齐到 0.1℃ 精度，保证设备端与界面一致
                repository.sendConfig(Config(t1 = norm(t1), t2 = norm(t2), res = res))
                // 发送成功（Socket 已写入，设备会回 ack 并更新 status）
                sendState = SendState.SUCCESS
            } catch (e: Exception) {
                // 失败（未连接 / IO 异常等）：红色提示
                sendState = SendState.FAILURE
            }
            // 3 秒后恢复默认态；若期间用户再次下发（状态已被新协程改写）则不覆盖
            delay(RESET_DELAY_MS)
            if (sendState != SendState.SENDING) {
                sendState = SendState.IDLE
            }
        }
    }

    // ---------- 界面 ----------
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundColor)
    ) {
        // 顶栏标题（对齐参考页 appbar）
        Text(
            text = "配置",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimaryColor,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 4.dp)
        )

        // 可滚动内容区
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // ---- T1 警告阈值 ----
            ThresholdStepper(
                label = "T1 警告阈值（绿 → 黄）",
                value = t1,
                minusEnabled = true,           // T1 可一直减到 0℃
                plusEnabled = t1PlusEnabled,   // 到 T2-0.5 为止，保证 T1<T2
                onMinus = onT1Minus,
                onPlus = onT1Plus,
                modifier = Modifier.fillMaxWidth()
            )

            // ---- T2 报警阈值 ----
            ThresholdStepper(
                label = "T2 报警阈值（黄 → 红）",
                value = t2,
                minusEnabled = t2MinusEnabled, // 到 T1+0.5 为止，保证 T1<T2
                plusEnabled = true,            // T2 可一直加到 125℃
                onMinus = onT2Minus,
                onPlus = onT2Plus,
                modifier = Modifier.fillMaxWidth()
            )

            // ---- DS18B20 分辨率 ----
            Text(
                text = "DS18B20 分辨率（精度 / 速度）",
                fontSize = 14.sp,
                color = TextSecondaryColor,
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
            )
            ResolutionSegment(
                selected = res,
                onSelect = { res = it },
                modifier = Modifier.fillMaxWidth()
            )
            // 分辨率说明：只显示当前选中档位的采样周期与精度（不堆全部档位）
            val resHint = when (res) {
                9 -> "9bit ≈190ms/次（0.5℃ 精度）"
                10 -> "10bit ≈390ms/次（0.25℃ 精度）"
                11 -> "11bit ≈780ms/次（0.125℃ 精度）"
                else -> "12bit ≈1.5s/次（0.0625℃ 精度）"
            }
            Text(
                text = resHint,
                fontSize = 12.sp,
                color = TextSecondaryColor,
                modifier = Modifier.padding(top = 8.dp)
            )

            // ---- 下发到设备 ----（下方不再显示流程说明/设备当前配置，保持简洁）
            SendButton(
                state = sendState,
                onClick = onSendClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp)
            )
        }
    }
}

/** 下发按钮状态机 */
private enum class SendState { IDLE, SENDING, SUCCESS, FAILURE }

/**
 * 阈值步进器（T1 / T2 共用）：
 * 灰底圆角容器，左右为 -/+ 圆形按钮（越界时置灰禁用），中间大号显示当前值 + °C。
 *
 * @param label        组标题（如「T1 警告阈值（绿 → 黄）」）
 * @param value        当前阈值
 * @param minusEnabled 减号按钮是否可用（T2 减到 T1+0.5 时禁用）
 * @param plusEnabled  加号按钮是否可用（T1 加到 T2-0.5 时禁用）
 */
@Composable
private fun ThresholdStepper(
    label: String,
    value: Float,
    minusEnabled: Boolean,
    plusEnabled: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(top = 18.dp)) {
        // 组标题
        Text(
            text = label,
            fontSize = 14.sp,
            color = TextSecondaryColor,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        // 步进器主体：浅灰底、圆角 14
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7F7FA), RoundedCornerShape(14.dp))
                .padding(8.dp)
        ) {
            // 减号按钮：白底 + 细边框 + 圆角 12，禁用时自动置灰
            StepperIconButton(
                icon = { Icon(Icons.Rounded.Remove, contentDescription = "减小阈值") },
                enabled = minusEnabled,
                onClick = onMinus
            )
            // 中间大号数值
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = fmt(value),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryColor
                )
                Text(
                    text = "°C",
                    fontSize = 15.sp,
                    color = TextSecondaryColor,
                    modifier = Modifier.padding(start = 4.dp, bottom = 3.dp)
                )
            }
            // 加号按钮
            StepperIconButton(
                icon = { Icon(Icons.Rounded.Add, contentDescription = "增大阈值") },
                enabled = plusEnabled,
                onClick = onPlus
            )
        }
    }
}

/**
 * 步进器的单个圆形按钮：白底、1dp 细边框、圆角 12，44dp 见方（对照参考页 .btn）。
 * enabled = false 时禁用点击且图标自动置灰。
 */
@Composable
private fun StepperIconButton(
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, DividerColor, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        // 禁用态：整块透明度降至 0.35（对照参考页 .dis）
        Box(modifier = Modifier.alpha(if (enabled) 1f else 0.35f)) {
            icon()
        }
    }
}

/**
 * DS18B20 分辨率四段选择（9 / 10 / 11 / 12 bit）：
 * 等宽分段按钮，选中项主色背景 + 白字，未选中白底 + 细边框。
 */
@Composable
private fun ResolutionSegment(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        RESOLUTIONS.forEach { r ->
            val isSelected = r == selected
            // 每段等宽（weight(1f)），点击切换选中
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(
                        color = if (isSelected) PrimaryColor else Color.White,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) PrimaryColor else DividerColor,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { onSelect(r) }
            ) {
                Text(
                    text = "$r bit",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) Color.White else TextPrimaryColor
                )
            }
        }
    }
}

/**
 * 「下发到设备」主色大按钮，随 [state] 切换外观：
 * - IDLE：    主色背景 + 纸飞机图标 +「下发到设备」；
 * - SENDING： 主色背景 + 白色转圈 +「下发中…」（按钮禁用防连点）；
 * - SUCCESS： 绿色背景 + 对勾 +「已下发」；
 * - FAILURE： 红色背景 + 叉号 +「下发失败」。
 */
@Composable
private fun SendButton(
    state: SendState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 成功绿色 / 失败红色 / 其余主色（对照参考页 .send-btn.done / .fail）
    val containerColor = when (state) {
        SendState.SUCCESS -> LevelGreen
        SendState.FAILURE -> LevelRed
        else -> PrimaryColor
    }
    Button(
        onClick = onClick,
        enabled = state != SendState.SENDING, // 下发中禁用，防重复提交
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White,
            // 禁用态（下发中）保持主色，仅略微压暗
            disabledContainerColor = PrimaryColor.copy(alpha = 0.6f),
            disabledContentColor = Color.White,
        ),
        modifier = modifier.height(56.dp)
    ) {
        when (state) {
            SendState.IDLE -> {
                Icon(Icons.Rounded.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("下发到设备", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            SendState.SENDING -> {
                // 白色小号转圈指示下发中
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
                Spacer(Modifier.width(8.dp))
                Text("下发中…", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            SendState.SUCCESS -> {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("已下发", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            SendState.FAILURE -> {
                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("下发失败", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------- 常量与工具函数 ----------

/** 默认 T1 警告阈值（℃） */
private const val DEFAULT_T1 = 30.0f
/** 默认 T2 报警阈值（℃） */
private const val DEFAULT_T2 = 40.0f
/** 默认分辨率（bit） */
private const val DEFAULT_RES = 10

/** 步进粒度：±0.5℃ */
private const val STEP = 0.5f
/** 浮点比较容差，避免边界误判 */
private const val EPS = 0.001f
/** 温度下限（℃） */
private const val MIN_TEMP = 0.0f
/** 温度上限（℃）：DS18B20 量程上限 */
private const val MAX_TEMP = 125.0f

/** 下发成功/失败提示的显示时长（毫秒），之后恢复默认态 */
private const val RESET_DELAY_MS = 3_000L

/** DS18B20 可选分辨率档位 */
private val RESOLUTIONS = listOf(9, 10, 11, 12)

/** 对齐到 0.1℃ 精度，消除 Float 步进累计误差 */
private fun norm(v: Float): Float = (v * 10f).roundToInt() / 10f

/** 固定 Locale 的数值格式化，保证始终显示一位小数（tab 对齐） */
private fun fmt(v: Float): String = String.format(Locale.US, "%.1f", v)
