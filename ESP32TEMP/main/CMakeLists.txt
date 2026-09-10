idf_component_register(SRCS "main.c"
                    INCLUDE_DIRS "."
                    REQUIRES threshold_store led_indicator ds18b20_sensor u8g2_ui tcp_server nvs_flash)
