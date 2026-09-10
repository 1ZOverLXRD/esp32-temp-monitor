/**
 * @file u8g2_ui.h
 * @brief 0.96 寸 SSD1306 OLED（128×64，I2C）纯显示界面驱动
 *
 * 组件：u8g2_ui（基于 managed_components/nixy4__u8g2，u8g2 v0.1.4）
 *
 * 屏幕布局（参照 F:\Cache\Hermes\scripts\esp32-temp-oled.html 点阵模拟）：
 *   - 顶部状态栏 y 0~14  ：左侧 WiFi 图标（已连/未连）+ 右侧标题
 *   - 中部大温度  y 16~48 ：大号数字（u8g2_font_logisoso32_tn）+ 小号 °C
 *   - 底部状态栏  y 50~63 ：级别图标 + 状态字（正常/警告/报警）+ T1/T2 阈值
 *
 * 接线：SDA = GPIO21，SCL = GPIO22，I2C 速率 400kHz，设备地址 0x3C
 */

#ifndef U8G2_UI_H
#define U8G2_UI_H

#include "esp_err.h"
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief 初始化 OLED（I2C + SSD1306 128x64 全缓冲）
 *
 * 内部步骤：
 *   1. 配置 I2C 主总线（GPIO21=SDA / GPIO22=SCL，400kHz，0x3C，内部上拉）
 *   2. 通过 u8g2 端口层 (u8x8_byte_esp32_hw_i2c) 注册为默认上下文
 *   3. u8g2_Setup_ssd1306_i2c_128x64_noname_f 全缓冲初始化
 *   4. 上电显示、清屏
 *
 * 可重复调用（内部做幂等保护，二次调用直接返回 ESP_OK）。
 *
 * @return ESP_OK 成功；ESP_ERR_INVALID_ARG/ESP_FAIL 初始化失败
 */
esp_err_t ui_init(void);

/**
 * @brief 全量重绘 OLED 主界面（每次调用先清缓冲再逐区绘制，最后统一发送）
 *
 * @param temp          当前温度（℃），显示 1 位小数，如 26.5
 * @param level         级别：0=正常 1=警告 2=报警（底部图标+状态字随之切换）
 * @param t1            一级阈值（℃），底部显示 T1:xx.x
 * @param t2            二级阈值（℃），底部显示 T2:xx.x
 * @param res           保留参数（当前未使用，预留）
 * @param wifi_connected WiFi 连接状态：true=顶部显示已连图标，false=未连图标
 */
void ui_render(float temp, int level, float t1, float t2, uint8_t res, bool wifi_connected);

#ifdef __cplusplus
}
#endif

#endif /* U8G2_UI_H */
