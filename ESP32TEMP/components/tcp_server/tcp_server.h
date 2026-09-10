/*
 * tcp_server.h — WiFi 软AP + TCP 服务器 + JSON 通讯组件
 *
 * 功能：
 *   1. 启动 WiFi SoftAP（SSID=ESP32-TEMP，密码=12345678）；
 *   2. 监听 TCP 8080 端口，接受一个客户端连接；
 *   3. 每 1 秒向客户端推送 JSON 状态包 status（含当前温度/级别与阈值配置）；
 *   4. 按行接收客户端 JSON 指令 set_config，调用 threshold_store 更新阈值后回 ack。
 *
 * 依赖：threshold_store 组件（阈值存储）、cJSON（json 解析/生成）。
 * 注意：nvs_flash 已由 main 初始化，本组件不重复初始化。
 */
#ifndef TCP_SERVER_H
#define TCP_SERVER_H

#include "esp_err.h"
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * @brief 启动 WiFi SoftAP + TCP Server:8080，并创建内部 net_task 任务。
 *
 * 成功后返回 ESP_OK；任一环节失败返回对应的 esp_err_t。
 * 可重复调用：若 WiFi/网络已初始化则跳过对应步骤。
 *
 * @return ESP_OK 成功；否则失败错误码
 */
esp_err_t net_init(void);

/**
 * @brief 更新共享温度/级别（由 temp_task 每周期调用）。
 *
 * @param temp  当前温度（℃）
 * @param level 当前级别（0/1/2...）
 */
void net_set_current(float temp, int level);

/**
 * @brief 查询当前是否有 TCP 客户端已连接。
 *
 * @return true 有客户端已连接；false 无客户端
 */
bool net_is_client_connected(void);

#ifdef __cplusplus
}
#endif

#endif /* TCP_SERVER_H */
