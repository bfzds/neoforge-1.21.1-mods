package dev.configpatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 统一日志入口，避免各处重复创建 Logger，也避免类之间互相持有静态字段。 */
public final class Log {

    public static final Logger LOGGER = LoggerFactory.getLogger("Config Patcher");

    private Log() {
    }
}
