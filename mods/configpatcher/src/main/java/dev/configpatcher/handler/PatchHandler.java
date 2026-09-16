package dev.configpatcher.handler;

import dev.configpatcher.engine.PatchContext;
import dev.configpatcher.engine.PatchOutcome;

import java.util.List;

/**
 * 扩展点：规则的“落地方式”。
 *
 * <p>内置两种：
 * <ul>
 *     <li>{@code configvalue} —— 通过 NeoForge 的 ModConfigSpec 在内存中改写（立即生效并落盘）</li>
 *     <li>{@code toml-file} —— 直接改 TOML 文本文件（目标 mod 不用 ModConfigSpec 时的兜底）</li>
 * </ul>
 *
 * <p>想接入别的配置体系（例如 Cloth Config、YACL、纯 JSON 配置），
 * 实现本接口并调用 {@link HandlerRegistry#register(PatchHandler)} 注册即可。
 */
public interface PatchHandler {

    /** 处理方式 id，对应规则里的 {@code handler} 字段。 */
    String id();

    /** 首选方式完全没效果时，可以降级到的方式 id；null 表示不降级。 */
    default String fallbackId() {
        return null;
    }

    List<PatchOutcome> apply(PatchContext context);
}
