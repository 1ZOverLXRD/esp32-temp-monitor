/*
 * SPDX-FileCopyrightText: 2026 ESP32TEMP Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
#pragma once

#include <stdint.h>
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief 初始化 DS18B20 温度传感器（GPIO4，内部上拉）
 *
 * 创建 1-Wire 总线（RMT 后端）并枚举总线上第一个 DS18B20 设备。
 * 该函数可被重复调用，内部会先清理旧资源。
 *
 * @return
 *      - ESP_OK: 初始化成功
 *      - ESP_ERR_NOT_FOUND: 总线上未找到 DS18B20 设备
 *      - 其他: 初始化失败（总线创建/设备创建失败）
 */
esp_err_t temp_init(void);

/**
 * @brief 设置 DS18B20 温度转换分辨率
 *
 * @param res 分辨率（bit）：仅支持 9 / 10 / 11 / 12，其他值返回错误
 * @return
 *      - ESP_OK: 设置成功
 *      - ESP_ERR_INVALID_ARG: 分辨率无效或设备未初始化
 *      - 其他: 设置失败（总线错误等）
 */
esp_err_t temp_set_resolution(uint8_t res);

/**
 * @brief 读取一次温度（阻塞，内部等待转换完成）
 *
 * 触发一次温度转换并按当前分辨率等待转换完成，然后读取并校验
 * scratchpad。CRC 错误、设备不存在或温度异常（>100°C，接触不良典型故障）
 * 均返回错误。
 *
 * @param[out] out 读取到的温度（摄氏度），仅在返回 ESP_OK 时有效
 * @return
 *      - ESP_OK: 读取成功
 *      - ESP_ERR_INVALID_ARG: 参数无效或设备未初始化
 *      - ESP_ERR_NOT_FOUND: 总线上无设备
 *      - ESP_ERR_INVALID_CRC: 读取数据 CRC 校验失败
 *      - ESP_ERR_INVALID_STATE: 读到异常温度值（如 85°C 上电值或 >100°C）
 */
esp_err_t temp_read(float *out);

#ifdef __cplusplus
}
#endif
