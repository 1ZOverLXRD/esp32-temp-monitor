/**
 * @file led_indicator.c
 * @brief 三色 LED 指示灯驱动实现
 *
 * 硬件接法（共阳）：LED 正极接 3V3，负极串限流电阻接 GPIO。
 * 开漏输出拉低（输出 0）→ 形成电流回路，LED 点亮；
 * 开漏释放（输出 1）→ 无电流回路，LED 熄灭。
 *
 * 引脚分配：
 *   GPIO16 —— 绿灯
 *   GPIO17 —— 黄灯
 *   GPIO18 —— 红灯
 */

#include "led_indicator.h"

#include "driver/gpio.h"

/* ---- 引脚定义 ---- */
#define LED_GREEN_GPIO   GPIO_NUM_16 /* 绿灯引脚 */
#define LED_YELLOW_GPIO  GPIO_NUM_17 /* 黄灯引脚 */
#define LED_RED_GPIO     GPIO_NUM_18 /* 红灯引脚 */

/* ---- 三路引脚的位掩码 ---- */
#define LED_ALL_GPIO_MASK \
    ((1ULL << LED_GREEN_GPIO) | (1ULL << LED_YELLOW_GPIO) | (1ULL << LED_RED_GPIO))

/* ---- 内部函数声明 ---- */

/**
 * @brief 将三路 LED 引脚统一输出为指定电平
 *
 * 开漏模式下输出 1 等价于释放（高阻），配合内部上拉引脚保持高电平，LED 熄灭。
 *
 * @param green_off 绿灯是否释放（1=熄灭）
 * @param yellow_off 黄灯是否释放（1=熄灭）
 * @param red_off 红灯是否释放（1=熄灭）
 */
static void led_set_all(int green_off, int yellow_off, int red_off)
{
    gpio_set_level(LED_GREEN_GPIO, green_off);
    gpio_set_level(LED_YELLOW_GPIO, yellow_off);
    gpio_set_level(LED_RED_GPIO, red_off);
}

/* ---- 对外接口实现 ---- */

esp_err_t led_init(void)
{
    /* 统一配置三路引脚为开漏输出（GPIO_MODE_OUTPUT_OD）：
     * - 开漏模式：输出 0 时拉低点亮；输出 1 时释放（高阻）
     * - 启用内部上拉：释放时引脚被上拉至 3V3，熄灭态电平确定，不受噪声干扰
     * - 关闭内部下拉、不使用中断 */
    gpio_config_t io_conf = {
        .pin_bit_mask = LED_ALL_GPIO_MASK,
        .mode = GPIO_MODE_OUTPUT_OD,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };

    esp_err_t ret = gpio_config(&io_conf);
    if (ret != ESP_OK) {
        return ret;
    }

    /* 初始状态：三灯全部熄灭 */
    led_set_level(-1);

    return ESP_OK;
}

void led_set_level(int level)
{
    switch (level) {
    case 0:
        /* 绿灯亮：GPIO16 拉低，黄/红两路释放（输出 1） */
        led_set_all(0, 1, 1);
        break;
    case 1:
        /* 黄灯亮：GPIO17 拉低，绿/红两路释放（输出 1） */
        led_set_all(1, 0, 1);
        break;
    case 2:
        /* 红灯亮：GPIO18 拉低，绿/黄两路释放（输出 1） */
        led_set_all(1, 1, 0);
        break;
    default:
        /* 非法值：三灯全灭（三路全部输出 1，开漏释放） */
        led_set_all(1, 1, 1);
        break;
    }
}
