/*
 * threshold_store.h — 温度阈值配置组件（NVS 持久化）
 *
 * 存储 T1（低温阈值）、T2（高温阈值）与 ADC 分辨率（res=9/10/11/12 位）。
 * NVS namespace: "temp_cfg"，keys: "t1"/"t2"/"res"。
 * 全局内存缓存一份，thr_get 读缓存，thr_set 校验后先写 NVS 再更新缓存。
 */
#ifndef THRESHOLD_STORE_H
#define THRESHOLD_STORE_H

#include "esp_err.h"
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* 温度阈值配置。t1<t2，单位 ℃；res 为 ADC 分辨率位数（9/10/11/12） */
typedef struct { float t1; float t2; uint8_t res; } threshold_cfg_t;  // res=9/10/11/12

/*
 * 初始化：读 NVS namespace "temp_cfg" 的 keys t1/t2/res 到内存缓存。
 * 无记录（首次上电）写入默认值 30.0 / 40.0 / 10 并返回 ESP_OK。
 * NVS 闪存已由 esp_common 链初始化；本函数可重复调用（nvs_flash_init 幂等）。
 */
esp_err_t thr_init(void);

/* 读内存缓存（拷贝输出，线程内使用，无锁） */
void thr_get(threshold_cfg_t *cfg);

/*
 * 设置新配置：校验 0<=t1<t2<=125 且 res∈{9,10,11,12}。
 * 校验失败返回 ESP_ERR_INVALID_ARG 且不写 NVS；成功则写 NVS 并更新内存缓存。
 */
esp_err_t thr_set(const threshold_cfg_t *cfg);

#ifdef __cplusplus
}
#endif

#endif /* THRESHOLD_STORE_H */
