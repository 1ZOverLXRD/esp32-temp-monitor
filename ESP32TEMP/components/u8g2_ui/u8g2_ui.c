/**
 * @file u8g2_ui.c
 * @brief 0.96 寸 SSD1306 OLED（128×64，I2C）纯显示界面实现
 *
 * 依赖：managed_components/nixy4__u8g2（u8g2 v0.1.4）
 *   - 端口层：esp32_hw_i2c.h（u8g2_esp32_i2c_ctx_t / u8x8_byte_esp32_hw_i2c）
 *   - 控制器：u8g2_Setup_ssd1306_i2c_128x64_noname_f（全缓冲，1024B）
 *
 * 布局（参照 esp32-temp-oled.html）：
 *   顶部 y 0~14   ：WiFi 图标 + 「温度监测」标题（wqy12 中文，UTF-8 输入）
 *   中部 y 16~48  ：logisoso32 大号温度数字 + 小号 °C
 *   底部 y 50~63  ：级别图标(空心圆/实心圆/实心圆+方框) + 状态字 + T1/T2
 */

#include "u8g2_ui.h"

#include <stdio.h>
#include <string.h>

#include "esp32_hw_i2c.h"
#include "esp_log.h"
#include "u8g2.h"

static const char *TAG = "u8g2_ui";

/* ------------------------- 硬件/屏幕参数 ------------------------- */

#define OLED_WIDTH      128   /* SSD1306 128x64 宽 */
#define OLED_HEIGHT     64    /* SSD1306 128x64 高 */
#define OLED_I2C_ADDR   0x3C  /* SSD1306 7bit 地址（多数模块默认） */

/* ------------------------- 全局对象 ------------------------- */

/* u8g2 主对象：全缓冲模式（_f 后缀，内部 1024B 静态缓冲） */
static u8g2_t s_u8g2;
/* 端口层上下文：保存 I2C 总线/设备句柄，u8x8_byte_esp32_hw_i2c 回调使用 */
static u8g2_esp32_i2c_ctx_t s_i2c_ctx;
/* 初始化完成标志（幂等保护） */
static bool s_ui_inited = false;

/* ------------------------- 静态辅助：绘制小函数 ------------------------- */

/**
 * @brief 在 (x,y) 处画 WiFi 信号图标（9×8 点阵区域）
 * @param on true=已连接（实心弧+底座）；false=未连接（弧上加叉）
 */
static void draw_wifi_icon(u8g2_t *u8g2, int x, int y, bool on)
{
    /* 三层信号弧（每层两行像素模拟弧线） */
    u8g2_DrawHLine(u8g2, x + 3, y + 0, 3);          /* 最上弧顶 */
    u8g2_DrawHLine(u8g2, x + 2, y + 3, 5);          /* 中弧 */
    u8g2_DrawHLine(u8g2, x + 1, y + 4, 7);          /* 外弧 */
    u8g2_DrawHLine(u8g2, x + 1, y + 6, 1);          /* 底座左侧 */
    u8g2_DrawHLine(u8g2, x + 7, y + 6, 1);          /* 底座右侧 */
    u8g2_DrawHLine(u8g2, x + 3, y + 6, 3);          /* 底座 */
    if (!on) {
        /* 未连接：弧上画叉 */
        u8g2_DrawLine(u8g2, x + 2, y + 1, x + 6, y + 5);
        u8g2_DrawLine(u8g2, x + 6, y + 1, x + 2, y + 5);
    }
}

/**
 * @brief 在 (x,y) 处画级别图标（6×7 点阵区域）
 * @param level 0=正常(空心圆)；1=警告(实心圆)；2=报警(实心圆+外方框)
 */
static void draw_level_icon(u8g2_t *u8g2, int x, int y, int level)
{
    if (level == 0) {
        /* 正常：空心圆（r=3） */
        u8g2_DrawCircle(u8g2, x + 3, y + 3, 3, U8G2_DRAW_ALL);
    } else if (level == 1) {
        /* 警告：实心圆（r=3） */
        u8g2_DrawDisc(u8g2, x + 3, y + 3, 3, U8G2_DRAW_ALL);
    } else {
        /* 报警：实心圆 + 外方框 */
        u8g2_DrawDisc(u8g2, x + 3, y + 3, 2, U8G2_DRAW_ALL);
        u8g2_DrawFrame(u8g2, x, y, 7, 7);
    }
}

/* ------------------------- 公共接口 ------------------------- */

esp_err_t ui_init(void)
{
    /* 幂等保护：重复调用直接返回成功 */
    if (s_ui_inited) {
        return ESP_OK;
    }

    /* 1. 端口层上下文：I2C 0 号总线，SDA=21，SCL=22，400kHz，0x3C */
    s_i2c_ctx.cfg = (u8g2_esp32_i2c_config_t)U8G2_ESP32_I2C_CONFIG_DEFAULT();
    s_i2c_ctx.cfg.i2c_port = 0;
    s_i2c_ctx.cfg.sda_pin = 21;
    s_i2c_ctx.cfg.scl_pin = 22;
    s_i2c_ctx.cfg.clk_hz = 400000;
    s_i2c_ctx.cfg.dev_addr_7bit = OLED_I2C_ADDR;
    s_i2c_ctx.cfg.reset_pin = U8G2_ESP32_PIN_UNUSED; /* 无复位引脚 */
    s_i2c_ctx.initialized = 0;
    s_i2c_ctx.bus_handle = NULL;
    s_i2c_ctx.dev_handle = NULL;

    /* 2. 注册为端口层默认上下文（u8x8_byte_esp32_hw_i2c 回调内部读取） */
    esp_err_t err = u8g2_esp32_i2c_set_default_context(&s_i2c_ctx);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "u8g2_esp32_i2c_set_default_context failed: %s", esp_err_to_name(err));
        return err;
    }

    /* 3. u8g2 初始化：SSD1306 128x64 I2C 全缓冲（_f = full buffer） */
    u8g2_Setup_ssd1306_i2c_128x64_noname_f(&s_u8g2, U8G2_R0,
                                           u8x8_byte_esp32_hw_i2c,
                                           u8x8_gpio_and_delay_esp32_i2c);
    /* 4. 设置 I2C 地址（8bit 形式：7bit 左移 1），I2C 总线初始化在首个字节回调内完成 */
    u8x8_SetI2CAddress(u8g2_GetU8x8(&s_u8g2), OLED_I2C_ADDR << 1);

    /* 5. 上电显示 + 清屏 */
    u8g2_InitDisplay(&s_u8g2);
    u8g2_SetPowerSave(&s_u8g2, 0);
    u8g2_ClearBuffer(&s_u8g2);
    u8g2_SendBuffer(&s_u8g2);

    s_ui_inited = true;
    ESP_LOGI(TAG, "OLED init done (SSD1306 128x64, I2C SDA=21 SCL=22 @400kHz, addr=0x%02X)",
             OLED_I2C_ADDR);
    return ESP_OK;
}

void ui_render(float temp, int level, float t1, float t2, uint8_t res, bool wifi_connected)
{
    char buf[16];
    uint16_t w;
    int x;

    (void)res; /* 保留参数，当前未使用 */

    /* 防御：未初始化时静默返回 */
    if (!s_ui_inited) {
        return;
    }

    /* 清空缓冲，准备整帧重绘 */
    u8g2_ClearBuffer(&s_u8g2);

    /* ==================== 区域一：顶部状态栏 y 0~14 ==================== */
    draw_wifi_icon(&s_u8g2, 2, 3, wifi_connected);       /* 左侧 WiFi 图标 */
    /* 标题：wqy12 中文字库（UTF-8 输入），12px 高，顶部靠右 */
    u8g2_SetFont(&s_u8g2, u8g2_font_wqy12_t_gb2312);
    u8g2_SetFontDirection(&s_u8g2, 0);
    w = u8g2_GetUTF8Width(&s_u8g2, "\xe6\xb8\xa9\xe5\xba\xa6\xe7\x9b\x91\xe6\xb5\x8b"); /* "温度监测" */
    u8g2_DrawUTF8(&s_u8g2, OLED_WIDTH - 3 - w, 13, "\xe6\xb8\xa9\xe5\xba\xa6\xe7\x9b\x91\xe6\xb5\x8b");

    /* ==================== 区域二：中部大温度 y 16~48 ==================== */
    /* 小数位跟随分辨率：9→1位 10→2位 11→3位 12→4位；位数多时自动缩小字体防溢出 128px */
    u8g2_SetFont(&s_u8g2, u8g2_font_logisoso32_tn);
    char fmt[8];
    int decimals = (res >= 12) ? 4 : (res >= 11) ? 3 : (res >= 10) ? 2 : 1;
    snprintf(fmt, sizeof(fmt), "%%.%df", decimals);
    snprintf(buf, sizeof(buf), fmt, temp);
    w = u8g2_GetUTF8Width(&s_u8g2, buf);                  /* 数字串总宽 */
    if (w > OLED_WIDTH - 8) {                             /* 超宽 → 换 24px 字体 */
        u8g2_SetFont(&s_u8g2, u8g2_font_logisoso24_tn);
        w = u8g2_GetUTF8Width(&s_u8g2, buf);
    }
    if (w > OLED_WIDTH - 8) {                             /* 再超 → 换 18px 字体 */
        u8g2_SetFont(&s_u8g2, u8g2_font_logisoso18_tn);
        w = u8g2_GetUTF8Width(&s_u8g2, buf);
    }
    x = (OLED_WIDTH - (int)w) / 2;                        /* 水平居中 */
    u8g2_DrawUTF8(&s_u8g2, x, 48, buf);                   /* 基线 y=48 */

    /* 小号 °C（数字右侧；右侧空间不足时省略，避免顶到屏边） */
    u8g2_SetFont(&s_u8g2, u8g2_font_wqy12_t_gb2312);
    if (x + (int)w + 3 + 16 < OLED_WIDTH) {
        u8g2_DrawUTF8(&s_u8g2, x + w + 3, 48, "\xc2\xb0\x43"); /* "°C"（UTF-8） */
    }

    /* ==================== 区域三：底部状态栏 y 50~63 ==================== */
    /* 级别图标 + 状态字（左对齐） */
    draw_level_icon(&s_u8g2, 2, 51, level);
    u8g2_SetFont(&s_u8g2, u8g2_font_wqy12_t_gb2312);
    switch (level) {
    case 0:
        u8g2_DrawUTF8(&s_u8g2, 12, 63, "\xe6\xad\xa3\xe5\xb8\xb8");     /* "正常" */
        break;
    case 1:
        u8g2_DrawUTF8(&s_u8g2, 12, 63, "\xe8\xad\xa6\xe5\x91\x8a");     /* "警告" */
        break;
    default:
        u8g2_DrawUTF8(&s_u8g2, 12, 63, "\xe6\x8a\xa5\xe8\xad\xa6");     /* "报警" */
        break;
    }

    /* T1/T2 阈值（底部同一行右对齐；T2 之前画在 y=51 会与大温度数字重叠，统一挪到 y=63） */
    snprintf(buf, sizeof(buf), "T2:%.1f", t2);
    const int w_t2 = u8g2_GetUTF8Width(&s_u8g2, buf);
    const int x_t2 = OLED_WIDTH - 3 - w_t2;
    snprintf(buf, sizeof(buf), "T1:%.1f", t1);
    const int w_t1 = u8g2_GetUTF8Width(&s_u8g2, buf);
    const int x_t1 = x_t2 - 3 - w_t1;
    u8g2_DrawUTF8(&s_u8g2, x_t1, 63, buf);   /* T1 */
    snprintf(buf, sizeof(buf), "T2:%.1f", t2);
    u8g2_DrawUTF8(&s_u8g2, x_t2, 63, buf);   /* T2 */

    /* 整帧发送到屏幕 */
    u8g2_SendBuffer(&s_u8g2);
}
