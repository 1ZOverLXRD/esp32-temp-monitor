/*
 * threshold_store.c — 温度阈值配置的 NVS 存储实现
 *
 * t1/t2 以 float 保存：NVS 无 float API，按 ×100 缩放为 int32 存盘
 * （0.01℃ 精度），读出时还原。res 以 uint8 存盘。
 */
#include "threshold_store.h"

#include "nvs.h"
#include "nvs_flash.h"

#define NVS_NS       "temp_cfg"      /* NVS namespace */
#define KEY_T1       "t1"            /* T1 低温阈值 key（int32，存 t1*100） */
#define KEY_T2       "t2"            /* T2 高温阈值 key（int32，存 t2*100） */
#define KEY_RES      "res"           /* ADC 分辨率 key（uint8） */
#define SCALE_F      100.0f          /* float→int32 缩放系数 */

#define DEFAULT_T1   30.0f
#define DEFAULT_T2   40.0f
#define DEFAULT_RES  10

/* 阈值物理边界（℃） */
#define TEMP_MIN     0.0f
#define TEMP_MAX     125.0f

/* 内存缓存：thr_init 从 NVS 载入，thr_set 成功后更新 */
static threshold_cfg_t s_cfg = {
    .t1  = DEFAULT_T1,
    .t2  = DEFAULT_T2,
    .res = DEFAULT_RES,
};

/* 校验配置：0<=t1<t2<=125 且 res∈{9,10,11,12} */
static bool cfg_valid(const threshold_cfg_t *cfg)
{
    return cfg != NULL &&
           cfg->res >= 9 && cfg->res <= 12 &&
           cfg->t1 >= TEMP_MIN && cfg->t1 < cfg->t2 && cfg->t2 <= TEMP_MAX;
}

esp_err_t thr_init(void)
{
    /* nvs_flash_init 幂等：已被 main 初始化过则直接返回 ESP_OK */
    esp_err_t err = nvs_flash_init();
    if (err != ESP_OK) {
        return err;
    }

    nvs_handle_t h;
    err = nvs_open(NVS_NS, NVS_READONLY, &h);
    if (err == ESP_ERR_NVS_NOT_FOUND) {
        /* 首次上电无 namespace：写默认值 */
        return thr_set(&s_cfg);
    }
    if (err != ESP_OK) {
        return err;
    }

    threshold_cfg_t cfg = s_cfg; /* 部分缺失时保留默认值 */

    int32_t v;
    if (nvs_get_i32(h, KEY_T1, &v) == ESP_OK) {
        cfg.t1 = (float)v / SCALE_F;
    }
    if (nvs_get_i32(h, KEY_T2, &v) == ESP_OK) {
        cfg.t2 = (float)v / SCALE_F;
    }
    if (nvs_get_u8(h, KEY_RES, &cfg.res) != ESP_OK) {
        cfg.res = DEFAULT_RES;
    }

    nvs_close(h);

    if (!cfg_valid(&cfg)) {
        /* NVS 数据损坏：回退默认并重写 */
        return thr_set(&s_cfg);
    }
    s_cfg = cfg;
    return ESP_OK;
}

void thr_get(threshold_cfg_t *cfg)
{
    if (cfg != NULL) {
        *cfg = s_cfg;
    }
}

esp_err_t thr_set(const threshold_cfg_t *cfg)
{
    /* 校验失败：返回 INVALID_ARG，不写 NVS */
    if (!cfg_valid(cfg)) {
        return ESP_ERR_INVALID_ARG;
    }

    nvs_handle_t h;
    esp_err_t err = nvs_open(NVS_NS, NVS_READWRITE, &h);
    if (err != ESP_OK) {
        return err;
    }

    err = nvs_set_i32(h, KEY_T1, (int32_t)(cfg->t1 * SCALE_F));
    if (err == ESP_OK) {
        err = nvs_set_i32(h, KEY_T2, (int32_t)(cfg->t2 * SCALE_F));
    }
    if (err == ESP_OK) {
        err = nvs_set_u8(h, KEY_RES, cfg->res);
    }
    if (err == ESP_OK) {
        err = nvs_commit(h); /* 提交到 flash，掉电不丢 */
    }

    nvs_close(h);

    /* 全部写盘成功才更新内存缓存 */
    if (err == ESP_OK) {
        s_cfg = *cfg;
    }
    return err;
}
