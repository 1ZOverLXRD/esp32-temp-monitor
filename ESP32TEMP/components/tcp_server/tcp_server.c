/*
 * tcp_server.c — WiFi 软AP + TCP 服务器 + JSON 通讯实现
 *
 * 工作流程：
 *   net_init()            -> 初始化 esp_netif + WiFi SoftAP + 创建 net_task 任务
 *   net_task()            -> 循环：accept 一个客户端，然后每 1s 推 status、
 *                            按行接收 set_config 并调 thr_set 后回 ack
 *   net_set_current()     -> temp_task 周期调用，更新共享温度/级别（volatile 单写多读）
 *   net_is_client_connected() -> 返回共享的连接状态标志
 *
 * JSON 协议：
 *   下行 status：  {"type":"status","temp":26.5,"level":0,"t1":30.0,"t2":40.0,"res":10}
 *   上行 set_config：{"type":"set_config","t1":28.0,"t2":45.0,"res":10}
 *   下行 ack：    {"type":"ack","ok":true,"t1":28.0,"t2":45.0,"res":10}
 *
 * 注意：nvs_flash 由 main 初始化；本组件只做网络侧初始化，不重复 init nvs。
 */
#include <string.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/select.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "esp_netif.h"
#include "esp_event.h"
#include "lwip/sockets.h"
#include "cJSON.h"
#include "threshold_store.h"
#include "tcp_server.h"

#define TAG "tcp_server"

#define WIFI_SSID      "ESP32-TEMP"   /* 软AP SSID */
#define WIFI_PASS      "12345678"     /* 软AP 密码 */
#define WIFI_MAX_CONN  4              /* 最大连接数 */
#define TCP_PORT       8080           /* TCP 监听端口 */
#define PUSH_INTERVAL_MS 1000         /* 状态推送周期 1s */
#define RECV_TIMEOUT_MS 500           /* recv 超时（与推送周期解耦，及时响应断开） */
#define LINE_BUF_SIZE  256            /* 行缓冲大小 */
#define JSON_BUF_SIZE  256            /* 发送缓冲大小 */

/* ---------------- 共享状态（单写多读，volatile 足够） ---------------- */
static volatile float s_temp = 0.0f;          /* 当前温度（net_set_current 写入，net_task 读取） */
static volatile int   s_level = 0;            /* 当前级别（net_set_current 写入，net_task 读取） */
static volatile bool  s_client_connected = false; /* 是否有客户端已连接（net_task 维护） */

static esp_netif_t *s_ap_netif = NULL;        /* 软AP 网络接口（防止重复初始化） */

/* ---------------- 静态函数声明 ---------------- */
static void net_task(void *arg);
static void push_status(int fd);
static void handle_line(int fd, const char *line);
static void send_json(int fd, const char *json);
static esp_err_t wifi_ap_start(void);
static int tcp_server_start(void);

/* ============ 对外接口 ============ */

esp_err_t net_init(void)
{
    /* 0. esp_netif 组件初始化（幂等，必须最先：esp_netif_create_default_wifi_ap
     *    内部依赖 esp_netif 已初始化，否则返回 ESP_ERR_INVALID_STATE） */
    esp_err_t ret = esp_netif_init();
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "esp_netif_init 失败: %s", esp_err_to_name(ret));
        return ret;
    }

    /* 0.5 默认事件循环（WiFi 事件分发需要；重复调用返回 INVALID_STATE 属正常可忽略） */
    ret = esp_event_loop_create_default();
    if (ret != ESP_OK && ret != ESP_ERR_INVALID_STATE) {
        ESP_LOGE(TAG, "esp_event_loop_create_default 失败: %s", esp_err_to_name(ret));
        return ret;
    }

    /* 1. 创建默认 esp_netif AP 接口（仅首次；须在 esp_netif_init 之后） */
    if (s_ap_netif == NULL) {
        s_ap_netif = esp_netif_create_default_wifi_ap();
        if (s_ap_netif == NULL) {
            ESP_LOGE(TAG, "创建默认软AP网络接口失败");
            return ESP_ERR_NO_MEM;
        }
    }

    /* 2. 启动 WiFi SoftAP */
    ret = wifi_ap_start();
    if (ret != ESP_OK) {
        return ret;
    }

    /* 3. 创建 net_task 任务（含 TCP Server 监听/accept/收发） */
    BaseType_t ok = xTaskCreate(net_task, "net_task", 4096, NULL, 5, NULL);
    if (ok != pdPASS) {
        ESP_LOGE(TAG, "创建 net_task 任务失败");
        return ESP_ERR_NO_MEM;
    }

    ESP_LOGI(TAG, "net_init 完成：AP=%s TCP端口=%d", WIFI_SSID, TCP_PORT);
    return ESP_OK;
}

void net_set_current(float temp, int level)
{
    /* 单写多读：写方仅此一处，volatile 保证读取方可见 */
    s_temp = temp;
    s_level = level;
}

bool net_is_client_connected(void)
{
    return s_client_connected;
}

/* ============ WiFi 软AP 初始化 ============ */

static esp_err_t wifi_ap_start(void)
{
    /* 若 WiFi 已初始化则跳过（esp_wifi_init 已初始化时返回 ESP_OK） */
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    esp_err_t ret = esp_wifi_init(&cfg);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_init 失败: %s", esp_err_to_name(ret));
        return ret;
    }

    /* AP 模式 */
    ret = esp_wifi_set_mode(WIFI_MODE_AP);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_set_mode 失败: %s", esp_err_to_name(ret));
        return ret;
    }

    /* AP 配置：SSID / 密码 / max_conn，信道用默认 */
    wifi_config_t ap_config = {
        .ap = {
            .ssid = WIFI_SSID,
            .password = WIFI_PASS,
            .max_connection = WIFI_MAX_CONN,
        },
    };
    ret = esp_wifi_set_config(WIFI_IF_AP, &ap_config);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_set_config 失败: %s", esp_err_to_name(ret));
        return ret;
    }

    ret = esp_wifi_start();
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "esp_wifi_start 失败: %s", esp_err_to_name(ret));
        return ret;
    }

    ESP_LOGI(TAG, "软AP 启动成功：SSID=%s", WIFI_SSID);
    return ESP_OK;
}

/* ============ TCP Server 监听 ============ */

static int tcp_server_start(void)
{
    /* 创建 TCP socket */
    int listen_fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (listen_fd < 0) {
        ESP_LOGE(TAG, "socket 创建失败: errno %d", errno);
        return -1;
    }

    /* 允许端口复用，避免重启后 TIME_WAIT 导致 bind 失败 */
    int opt = 1;
    setsockopt(listen_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    /* 绑定 0.0.0.0:8080 */
    struct sockaddr_in addr = {
        .sin_family = AF_INET,
        .sin_port = htons(TCP_PORT),
        .sin_addr.s_addr = htonl(INADDR_ANY),
    };
    int ret = bind(listen_fd, (struct sockaddr *)&addr, sizeof(addr));
    if (ret < 0) {
        ESP_LOGE(TAG, "bind 失败: errno %d", errno);
        close(listen_fd);
        return -1;
    }

    ret = listen(listen_fd, 1);
    if (ret < 0) {
        ESP_LOGE(TAG, "listen 失败: errno %d", errno);
        close(listen_fd);
        return -1;
    }

    ESP_LOGI(TAG, "TCP Server 监听中：端口 %d", TCP_PORT);
    return listen_fd;
}

/* ============ net_task 主循环 ============ */

static void net_task(void *arg)
{
    (void)arg;

    /* 启动 TCP Server（监听 socket） */
    int listen_fd = tcp_server_start();
    if (listen_fd < 0) {
        ESP_LOGE(TAG, "TCP Server 启动失败，net_task 退出");
        vTaskDelete(NULL);
        return;
    }

    while (1) {
        /* ---- 接受一个客户端连接（阻塞） ---- */
        struct sockaddr_in client_addr;
        socklen_t addr_len = sizeof(client_addr);
        int client_fd = accept(listen_fd, (struct sockaddr *)&client_addr, &addr_len);
        if (client_fd < 0) {
            ESP_LOGE(TAG, "accept 失败: errno %d", errno);
            vTaskDelay(pdMS_TO_TICKS(1000));
            continue;
        }

        ESP_LOGI(TAG, "客户端已连接：%s:%d",
                 inet_ntoa(client_addr.sin_addr), ntohs(client_addr.sin_port));
        s_client_connected = true;

        /* ---- 与客户端交互：每 1s 推 status + 按行处理 set_config ---- */
        /* SO_RCVTIMEO：标准 struct timeval（ESP-IDF lwip NONSTANDARD=0） */
        struct timeval recv_timeout = {
            .tv_sec = 0,
            .tv_usec = RECV_TIMEOUT_MS * 1000,
        };
        setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, &recv_timeout, sizeof(recv_timeout));

        char line_buf[LINE_BUF_SIZE];
        size_t line_len = 0;

        while (1) {
            /* a) 每 1s 推送一次 status（含温度/级别/阈值） */
            push_status(client_fd);

            /* b) 非阻塞式接收，超时后回到推送（保证 1s 周期） */
            char ch;
            int n = recv(client_fd, &ch, 1, 0);
            if (n > 0) {
                /* 按行累积：以 '\n' 为一行结束符 */
                if (ch == '\n') {
                    if (line_len > 0) {
                        line_buf[line_len] = '\0';
                        handle_line(client_fd, line_buf);
                    }
                    line_len = 0;
                } else if (line_len < sizeof(line_buf) - 1) {
                    line_buf[line_len++] = ch;
                } else {
                    /* 行过长：丢弃并复位，防止缓冲溢出 */
                    line_len = 0;
                }
                continue;
            }

            /* 客户端断开（recv 返回 0）→ 退出交互循环 */
            if (n == 0) {
                ESP_LOGI(TAG, "客户端断开连接");
                break;
            }

            /* recv 出错：EAGAIN/EWOULDBLOCK 是 SO_RCVTIMEO 超时（1s 推送节奏的正常现象），
             * 静默继续不打日志；其余错误才视为断开 */
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                continue;   /* 超时正常：回到循环顶部继续推 status */
            }
            ESP_LOGW(TAG, "recv 错误: errno %d", errno);
            break;
        }

        close(client_fd);
        s_client_connected = false;
        ESP_LOGI(TAG, "连接已关闭，等待下一个客户端");
    }
}

/* ============ 发送 JSON 报文 ============ */

static void send_json(int fd, const char *json)
{
    size_t len = strlen(json);
    /* 追加换行，方便客户端按行解析 */
    char buf[JSON_BUF_SIZE];
    size_t n = snprintf(buf, sizeof(buf), "%s\n", json);
    if (n > sizeof(buf) - 1) {
        n = sizeof(buf) - 1;   /* 截断保护（理论不会发生） */
    }
    int ret = send(fd, buf, n, 0);
    if (ret < 0) {
        ESP_LOGE(TAG, "send 失败: errno %d", errno);
    }
}

/* ============ 推送 status ============ */

static void push_status(int fd)
{
    /* 从共享变量读取温度/级别 */
    float temp = s_temp;
    int   level = s_level;

    /* 从 threshold_store 读取阈值配置 */
    threshold_cfg_t cfg;
    thr_get(&cfg);

    /* 组装 JSON：温度与阈值保留 1 位小数 */
    cJSON *root = cJSON_CreateObject();
    if (root == NULL) {
        return;
    }
    cJSON_AddStringToObject(root, "type", "status");
    cJSON_AddNumberToObject(root, "temp", temp);
    cJSON_AddNumberToObject(root, "level", level);
    cJSON_AddNumberToObject(root, "t1", cfg.t1);
    cJSON_AddNumberToObject(root, "t2", cfg.t2);
    cJSON_AddNumberToObject(root, "res", cfg.res);

    /* cJSON 格式化为字符串 */
    char *json_str = cJSON_PrintUnformatted(root);
    if (json_str != NULL) {
        send_json(fd, json_str);
        free(json_str);
    }
    cJSON_Delete(root);
}

/* ============ 处理一行指令（set_config） ============ */

static void handle_line(int fd, const char *line)
{
    cJSON *root = cJSON_Parse(line);
    if (root == NULL) {
        ESP_LOGW(TAG, "JSON 解析失败: %s", line);
        return;
    }

    /* 仅处理 set_config 类型指令 */
    cJSON *type_item = cJSON_GetObjectItem(root, "type");
    if (type_item == NULL || !cJSON_IsString(type_item) ||
        strcmp(type_item->valuestring, "set_config") != 0) {
        cJSON_Delete(root);
        return;
    }

    /* 解析 t1 / t2 / res */
    cJSON *t1_item = cJSON_GetObjectItem(root, "t1");
    cJSON *t2_item = cJSON_GetObjectItem(root, "t2");
    cJSON *res_item = cJSON_GetObjectItem(root, "res");
    if (t1_item == NULL || t2_item == NULL || res_item == NULL ||
        !cJSON_IsNumber(t1_item) || !cJSON_IsNumber(t2_item) || !cJSON_IsNumber(res_item)) {
        ESP_LOGW(TAG, "set_config 缺少字段或类型错误");
        cJSON_Delete(root);
        return;
    }

    /* 组装配置并写入 threshold_store */
    threshold_cfg_t cfg = {
        .t1 = (float)t1_item->valuedouble,
        .t2 = (float)t2_item->valuedouble,
        .res = (uint8_t)res_item->valueint,
    };
    /* 打印收到的配置，便于排查客户端下发的 t1/t2/res */
    ESP_LOGI(TAG, "收到 set_config: t1=%.1f t2=%.1f res=%u", (double)cfg.t1, (double)cfg.t2, cfg.res);
    esp_err_t ret = thr_set(&cfg);

    /* 打印写入结果：成功/非法拒绝，便于确认阈值是否生效 */
    if (ret == ESP_OK) {
        ESP_LOGI(TAG, "配置已写入 NVS 并生效");
    } else {
        ESP_LOGW(TAG, "配置非法，拒绝写入");
    }

    /* 回 ack：ok 表示 thr_set 是否成功；ok=false 时不写 t1/t2/res 字段 */
    cJSON *ack = cJSON_CreateObject();
    if (ack == NULL) {
        cJSON_Delete(root);
        return;
    }
    cJSON_AddStringToObject(ack, "type", "ack");
    cJSON_AddBoolToObject(ack, "ok", (ret == ESP_OK));
    if (ret == ESP_OK) {
        cJSON_AddNumberToObject(ack, "t1", cfg.t1);
        cJSON_AddNumberToObject(ack, "t2", cfg.t2);
        cJSON_AddNumberToObject(ack, "res", cfg.res);
    }

    char *json_str = cJSON_PrintUnformatted(ack);
    if (json_str != NULL) {
        send_json(fd, json_str);
        free(json_str);
    }
    cJSON_Delete(ack);
    cJSON_Delete(root);
}
