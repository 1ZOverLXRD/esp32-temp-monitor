/*
 * SPDX-FileCopyrightText: 2026 ESP32TEMP Project
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * DS18B20 温度传感器组件（基于 espressif/ds18b20 + espressif/onewire_bus）
 *
 * 硬件连接：DS18B20 数据线接 GPIO4，使用芯片内部上拉（1-Wire 总线
 * 使能内部上拉；注意：内部上拉驱动能力有限，长线场景建议外加 4.7k
 * 上拉电阻）。
 *
 * 使用流程：
 *   temp_init();                 // 初始化总线并找到 DS18B20
 *   temp_set_resolution(12);     // 可选，设置分辨率（9/10/11/12 bit）
 *   float t; temp_read(&t);      // 阻塞读取一次温度
 */

#include <stdint.h>
#include <string.h>

#include "esp_err.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "onewire_bus.h"
#include "onewire_device.h"
#include "onewire_types.h"
#include "ds18b20.h"

static const char *TAG = "ds18b20_sensor";

/* DS18B20 数据引脚 */
#define TEMP_GPIO_NUM 4

/* 温度异常上限：DS18B20 测温范围 -55°C ~ +125°C，读数超过 100°C
 * 属于接触不良/悬空读数的典型故障，直接丢弃并返回错误 */
#define TEMP_MAX_VALID_C 100.0f

/* 总线与设备句柄 */
static onewire_bus_handle_t s_bus = NULL;
static ds18b20_device_handle_t s_dev = NULL;

/**
 * @brief 创建 1-Wire 总线（RMT 后端）并枚举第一个 DS18B20 设备
 */
static esp_err_t temp_create_bus_and_device(void)
{
    esp_err_t ret = ESP_FAIL;

    /* 1) 创建 1-Wire 总线，GPIO4，使能内部上拉 */
    onewire_bus_config_t bus_config = {
        .bus_gpio_num = TEMP_GPIO_NUM,
        .flags = {
            .en_pull_up = true,
        },
    };
    /* RMT 后端配置：最大接收 10 字节（1 字节 ROM 命令 + 8 字节 ROM 编号 + 1 字节设备命令） */
    onewire_bus_rmt_config_t rmt_config = {
        .max_rx_bytes = 10,
    };
    ret = onewire_new_bus_rmt(&bus_config, &rmt_config, &s_bus);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "创建 1-Wire 总线失败: %s", esp_err_to_name(ret));
        return ret;
    }

    /* 2) 枚举总线上的设备，找到第一个 DS18B20（家族码 0x28） */
    onewire_device_iter_handle_t iter = NULL;
    onewire_device_t next_dev;
    esp_err_t search_ret = ESP_OK;

    ret = onewire_new_device_iter(s_bus, &iter);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "创建设备迭代器失败: %s", esp_err_to_name(ret));
        goto err;
    }

    while ((search_ret = onewire_device_iter_get_next(iter, &next_dev)) == ESP_OK) {
        ds18b20_config_t ds_cfg = {};
        ret = ds18b20_new_device_from_enumeration(&next_dev, &ds_cfg, &s_dev);
        if (ret == ESP_OK) {
            /* 找到 DS18B20 设备 */
            onewire_device_address_t addr = 0;
            ds18b20_get_device_address(s_dev, &addr);
            ESP_LOGI(TAG, "找到 DS18B20 设备, 地址: %016llX", addr);
            break;
        }
        ESP_LOGW(TAG, "发现未知设备, 地址: %016llX", next_dev.address);
    }

    /* 释放迭代器 */
    onewire_del_device_iter(iter);

    if (search_ret == ESP_ERR_NOT_FOUND && s_dev == NULL) {
        ESP_LOGE(TAG, "总线上未找到 DS18B20 设备");
        ret = ESP_ERR_NOT_FOUND;
        goto err;
    }
    if (s_dev == NULL) {
        ESP_LOGE(TAG, "设备枚举失败: %s", esp_err_to_name(ret));
        goto err;
    }

    ESP_LOGI(TAG, "DS18B20 初始化完成, GPIO%d", TEMP_GPIO_NUM);
    return ESP_OK;

err:
    /* 清理总线资源 */
    if (s_bus != NULL) {
        onewire_bus_del(s_bus);
        s_bus = NULL;
    }
    return ret;
}

esp_err_t temp_init(void)
{
    /* 若已初始化过，先清理旧资源，保证可重复调用 */
    if (s_bus != NULL) {
        ESP_LOGW(TAG, "重复调用 temp_init，先清理旧资源");
        if (s_dev != NULL) {
            ds18b20_del_device(s_dev);
            s_dev = NULL;
        }
        onewire_bus_del(s_bus);
        s_bus = NULL;
    }

    return temp_create_bus_and_device();
}

esp_err_t temp_set_resolution(uint8_t res)
{
    if (s_dev == NULL) {
        ESP_LOGE(TAG, "设备未初始化");
        return ESP_ERR_INVALID_STATE;
    }

    /* 分辨率映射：9/10/11/12 bit -> 官方枚举 */
    static const ds18b20_resolution_t res_map[] = {
        [9]  = DS18B20_RESOLUTION_9B,
        [10] = DS18B20_RESOLUTION_10B,
        [11] = DS18B20_RESOLUTION_11B,
        [12] = DS18B20_RESOLUTION_12B,
    };
    if (res < 9 || res > 12) {
        ESP_LOGE(TAG, "无效的分辨率: %u (仅支持 9/10/11/12)", res);
        return ESP_ERR_INVALID_ARG;
    }

    esp_err_t ret = ds18b20_set_resolution(s_dev, res_map[res]);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "设置分辨率失败: %s", esp_err_to_name(ret));
        return ret;
    }
    ESP_LOGI(TAG, "分辨率已设置为 %u bit", res);
    return ESP_OK;
}

esp_err_t temp_read(float *out)
{
    if (out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    if (s_dev == NULL) {
        ESP_LOGE(TAG, "设备未初始化");
        return ESP_ERR_INVALID_STATE;
    }

    /* 1) 触发温度转换（内部已按分辨率等待转换完成：
     *    9bit≈93.75ms / 10bit≈187.5ms / 11bit≈375ms / 12bit≈750ms） */
    esp_err_t ret = ds18b20_trigger_temperature_conversion(s_dev);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "触发温度转换失败: %s", esp_err_to_name(ret));
        return ret;
    }

    /* 2) 读取温度（内部含 CRC 校验） */
    float temperature = 0.0f;
    ret = ds18b20_get_temperature(s_dev, &temperature);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "读取温度失败: %s", esp_err_to_name(ret));
        return ret;
    }

    /* 3) 异常值保护：读数超过 100°C 判定为接触不良/悬空，丢弃 */
    if (temperature > TEMP_MAX_VALID_C) {
        ESP_LOGW(TAG, "温度异常 (%.2f°C)，疑似传感器接触不良，丢弃本次读数", temperature);
        return ESP_ERR_INVALID_STATE;
    }

    *out = temperature;
    return ESP_OK;
}
