package com.coldfish.myapplication.data

/**
 * WiFi 连接状态枚举。
 *
 * 用于告知 UI 当前手机与 ESP32 设备热点的连接情况，
 * 方便界面在连接异常时给出提示（例如提示用户切换到 ESP32 热点）。
 */
enum class WifiState {

    /**
     * 已连接到 ESP32 设备的热点
     * （当前 WiFi 的 SSID 中包含约定前缀 "ESP32-TEMP"，去掉引号后比较）。
     */
    CONNECTED_AP,

    /**
     * 已连接到某个 WiFi，但不是 ESP32 设备的热点
     * （说明用户连了别的网络，需要提示切换到目标热点）。
     */
    WRONG_NETWORK,

    /**
     * 未连接任何 WiFi 网络。
     */
    DISCONNECTED,
}