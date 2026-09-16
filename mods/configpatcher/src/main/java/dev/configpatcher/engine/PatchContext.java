package dev.configpatcher.engine;

import dev.configpatcher.rule.PatchRule;
import net.neoforged.fml.config.ModConfig;

/**
 * 一次处理所需的输入：正在加载的配置 + 命中的规则 + 目标 mod 的版本。
 *
 * @param config        目标 mod 的 ModConfig（NeoForge 公开类型）
 * @param rule          命中的规则
 * @param targetVersion 目标 mod 已加载版本，可能为 null
 */
public record PatchContext(ModConfig config, PatchRule rule, String targetVersion) {

    public String targetMod() {
        return rule.targetMod();
    }

    public String fileName() {
        return config.getFileName();
    }

    public String configType() {
        return config.getType().name();
    }
}
