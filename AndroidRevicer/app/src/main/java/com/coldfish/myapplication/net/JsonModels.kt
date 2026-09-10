package com.coldfish.myapplication.net

/**
 * 设备实时状态数据模型
 *
 * 对应 ESP32 设备每秒推送的 type=status 消息：
 * {"type":"status","temp":26.5,"level":0,"t1":30.0,"t2":40.0,"res":10}
 *
 * @param temp 当前温度（℃）
 * @param level 当前档位
 * @param t1 阈值1（目标温度下限）
 * @param t2 阈值2（目标温度上限）
 * @param res 电阻/分辨率档位参数
 */
data class TempStatus(val temp: Float, val level: Int, val t1: Float, val t2: Float, val res: Int)

/**
 * 下发给设备的配置数据模型
 *
 * 对应 type=set_config 消息：
 * {"type":"set_config","t1":30.0,"t2":40.0,"res":10}
 *
 * @param t1 阈值1（目标温度下限）
 * @param t2 阈值2（目标温度上限）
 * @param res 电阻/分辨率档位参数
 */
data class Config(val t1: Float, val t2: Float, val res: Int)
