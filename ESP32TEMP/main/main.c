/*
 * ESP32TEMP 主程序 —— 温度采集 / 分级告警 / OLED 显示 / TCP 上报 集成
 *
 * 任务划分：
 *   temp_task（Core1, 优先级 5）：每 200ms 采样 DS18B20，按阈值判级，
 *                                  驱动 LED 指示灯，并同步最新数据到 TCP 服务器
 *   ui_task （Core1, 优先级 4）：每 500ms 从阈值存储读取配置并刷新 OLED 界面
 *
 * 初始化顺序：nvs -> threshold_store -> led_indicator -> ds18b20_sensor
 *             -> 分辨率设置 -> u8g2_ui -> tcp_server -> 创建任务
 */

#include <stdio.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs_flash.h"
#include "esp_log.h"

#include "threshold_store.h"
#include "led_indicator.h"
#include "ds18b20_sensor.h"
#include "u8g2_ui.h"
#include "tcp_server.h"

#define TAG "main"

/* 任务参数 */
#define TEMP_TASK_PRIO      5
#define UI_TASK_PRIO        4
#define TASK_STACK_SIZE     4096
#define TEMP_TASK_PERIOD_MS 200   /* 略大于 10bit 分辨率约 187.5ms 的转换时间 */
#define UI_TASK_PERIOD_MS   500

/* 温度与等级状态：temp_task 写，ui_task 读（volatile 保证跨任务可见，单字节/字读取原子） */
static volatile float s_temp  = 0.0f;
static volatile int   s_level = 0;

/* 当前生效的传感器分辨率（bit）：temp_task 检测 NVS 配置变化时同步到 DS18B20 硬件 */
static uint8_t s_last_res = 0xFF;   /* 0xFF 表示未初始化，首次必触发同步 */

/**
 * @brief 温度采样任务（Core1，优先级 5）
 *        循环：读温度 -> 判级 -> 驱动 LED -> 同步 TCP -> 延时 200ms
 */
static void temp_task(void *arg)
{
    threshold_cfg_t cfg;
    float t = 0.0f;
    int level = 0;
    int last_level = -1;          /* 初始 -1：保证首次采样必打印一次级别 */

    ESP_LOGI(TAG, "temp_task started");

    for (;;) {
        /* 1. 读取 DS18B20 温度；失败时保持上一次有效值 */
        if (temp_read(&t) == ESP_OK) {
            s_temp = t;
        } else {
            ESP_LOGW(TAG, "temp_read failed, keep last value: %.2f", (double)s_temp);
        }

        /* 2. 读取当前阈值并判级：<=t1 绿(0)，<=t2 黄(1)，其余红(2) */
                thr_get(&cfg);
                level = (s_temp <= cfg.t1) ? 0 : ((s_temp <= cfg.t2) ? 1 : 2);
                s_level = level;

                /* 2.5 分辨率变化检测：Android 下发新 res 后必须同步到 DS18B20 硬件，
                 * 否则 11/12bit 精度不生效（温度步进仍停留在原分辨率） */
                if (cfg.res != s_last_res) {
                    esp_err_t r = temp_set_resolution(cfg.res);
                    if (r == ESP_OK) {
                        ESP_LOGI(TAG, "分辨率切换 -> %u bit", cfg.res);
                        s_last_res = cfg.res;
                    } else {
                        ESP_LOGW(TAG, "分辨率切换失败 res=%u", cfg.res);
                    }
                }

        /* 3. 驱动 LED 指示灯 */
        led_set_level(level);

        /* 3.5 级别切换日志：每次变化打印一次（绿->黄->红 或反之） */
        if (level != last_level) {
            static const char *lv_name[] = {"绿·正常", "黄·警告", "红·报警"};
            ESP_LOGI(TAG, "温度 %.1f°C 级别切换 -> %s (T1=%.1f T2=%.1f)",
                     (double)s_temp, lv_name[level], (double)cfg.t1, (double)cfg.t2);
            last_level = level;
        }

        /* 4. 同步最新数据到 TCP 服务器（客户端订阅用） */
                        net_set_current(s_temp, s_level);

                /* 5. 采样延时跟随分辨率：转换时间 + 余量（9→100 10→200 11→400 12→800ms），
                 *    保证实际采样周期与 App 标注一致 */
                static const int delay_by_res[] = {0,0,0,0,0,0,0,0,0,100,200,400,800};
                int delay_ms = (cfg.res >= 9 && cfg.res <= 12) ? delay_by_res[cfg.res] : TEMP_TASK_PERIOD_MS;
                vTaskDelay(pdMS_TO_TICKS(delay_ms));
    }
}

/**
 * @brief 界面刷新任务（Core1，优先级 4）
 *        循环：读取阈值配置 -> 渲染 OLED -> 延时 500ms
 */
static void ui_task(void *arg)
{
    threshold_cfg_t cfg;

    ESP_LOGI(TAG, "ui_task started");

    for (;;) {
        /* 读取最新阈值配置（分辨率、上下限），与温度任务共用存储，读取无锁风险 */
        thr_get(&cfg);

        /* 渲染 OLED：温度 / 等级 / 阈值上下限 / 分辨率 / WiFi 连接状态 */
        ui_render(s_temp, s_level, cfg.t1, cfg.t2, cfg.res, net_is_client_connected());

        vTaskDelay(pdMS_TO_TICKS(UI_TASK_PERIOD_MS));
    }
}

/**
 * @brief 应用入口：按固定顺序初始化各组件后创建两个任务
 */
void app_main(void)
{
    threshold_cfg_t cfg;

    /* 1. NVS 初始化（阈值存储、网络配置的持久化基础） */
    ESP_ERROR_CHECK(nvs_flash_init());

    /* 2. 阈值存储 */
    ESP_ERROR_CHECK(thr_init());

    /* 3. LED 指示灯 */
    ESP_ERROR_CHECK(led_init());

    /* 4. DS18B20 温度传感器 */
    ESP_ERROR_CHECK(temp_init());

    /* 5. 应用存储中恢复的分辨率（9/10/11/12 bit），必须在首次采样前生效 */
    thr_get(&cfg);
    ESP_ERROR_CHECK(temp_set_resolution(cfg.res));

    /* 6. OLED 界面（u8g2） */
    ESP_ERROR_CHECK(ui_init());

    /* 7. TCP 服务器（网络） */
    ESP_ERROR_CHECK(net_init());

    /* 8. 创建任务：温度采样（高优先级）、界面刷新（低优先级），均固定 Core1 */
    ESP_LOGI(TAG, "creating tasks");
    xTaskCreatePinnedToCore(temp_task, "temp_task", TASK_STACK_SIZE, NULL,
                            TEMP_TASK_PRIO, NULL, 1);
    xTaskCreatePinnedToCore(ui_task, "ui_task", TASK_STACK_SIZE, NULL,
                            UI_TASK_PRIO, NULL, 1);
}
