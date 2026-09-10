/**
 * @file led_indicator.h
 * @brief 三色 LED 指示灯驱动（GPIO16/17/18 开漏输出，共阳接法，低电平点亮）
 *
 * 共阳接法：LED 正极接 3V3，负极串电阻接 GPIO。
 * 开漏输出拉低（输出 0）时点亮，开漏释放（输出 1）时熄灭。
 */

#ifndef LED_INDICATOR_H
#define LED_INDICATOR_H

#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief 初始化 LED 指示灯 GPIO
 *
 * 将 GPIO16/17/18 配置为 GPIO_MODE_OUTPUT_OD（开漏输出），
 * 并启用内部上拉保证熄灭态电平确定。初始化后三灯全部熄灭。
 *
 * @return ESP_OK 成功，否则返回 gpio_config 的错误码
 */
esp_err_t led_init(void);

/**
 * @brief 设置指示灯状态（同一时刻仅一路拉低，三路互斥）
 *
 * @param level 0=绿灯亮（GPIO16 拉低）
 *              1=黄灯亮（GPIO17 拉低）
 *              2=红灯亮（GPIO18 拉低）
 *              其余值=三灯全灭（三路全部输出 1，开漏释放）
 */
void led_set_level(int level);

#ifdef __cplusplus
}
#endif

#endif /* LED_INDICATOR_H */
