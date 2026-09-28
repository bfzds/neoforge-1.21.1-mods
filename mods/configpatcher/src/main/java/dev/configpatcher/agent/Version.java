package dev.configpatcher.agent;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 构建版本标识：回答「实例里跑的到底是哪次构建」。
 *
 * <p>{@code configpatcher/build-info.properties} 由 Gradle 打包时的 {@code generateBuildInfo}
 * 任务生成，内容是 mod 版本号与构建时间。只用 JDK 能力，Agent 与 mod 两侧都能读。
 */
public final class Version {

    private Version() {
    }

    /** 形如 {@code 0.1.0（构建 2026-09-28 15:30:00）}；资源缺失时各项显示 unknown。 */
    public static String describe() {
        Properties props = new Properties();
        try (InputStream in = Version.class.getResourceAsStream("/configpatcher/build-info.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // 读不到就用 unknown，绝不影响启动
        }
        return props.getProperty("build.version", "unknown")
                + "（构建 " + props.getProperty("build.time", "unknown") + "）";
    }
}
