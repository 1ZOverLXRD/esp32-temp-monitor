# 三色 LED 指示灯组件（GPIO16/17/18 开漏输出，共阳低电平点亮）
idf_component_register(SRCS "led_indicator.c" INCLUDE_DIRS "." PRIV_REQUIRES esp_driver_gpio)
