package dev.configpatcher.rule;

import dev.configpatcher.engine.VersionRange;

import java.util.List;

/**
 * 一条规则：当 {targetMod} 存在（且版本符合 {versionRange}）时，
 * 对它的某个配置文件的若干条目做改写。
 *
 * <p>规则只描述“做什么”，不描述“怎么做”，具体落地由 {@code PatchHandler} 负责。
 *
 * @param id           规则唯一标识，仅用于日志与命令输出
 * @param comment      人类可读备注
 * @param targetMod    目标 mod 的 modId
 * @param enabled      单条规则的开关，默认 true（出问题时可单独关掉而不影响别的规则）
 * @param versionRange 可空；空 / {@code *} 表示不限制版本
 * @param configFile   可空；目标配置文件名（如 {@code othermod-common.toml}），空表示该 mod 的所有配置
 * @param configType   可空；{@code COMMON} / {@code SERVER} / {@code CLIENT} / {@code STARTUP}，空表示不限
 * @param handler      处理方式 id，默认 {@code configvalue}
 * @param required     true 表示目标 mod 缺失时用 WARN 级别提示，false（默认）静默跳过
 * @param entries      改写项列表
 */
public record PatchRule(
        String id,
        String comment,
        String targetMod,
        boolean enabled,
        String versionRange,
        String configFile,
        String configType,
        String handler,
        boolean required,
        List<PatchEntry> entries
) {

    public static final String DEFAULT_HANDLER = "configvalue";

    public PatchRule {
        if (targetMod == null || targetMod.isBlank()) {
            throw new IllegalArgumentException("规则缺少 target（目标 modId）：" + id);
        }
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("规则没有任何改写项：" + id);
        }
        entries = List.copyOf(entries);
        handler = (handler == null || handler.isBlank()) ? DEFAULT_HANDLER : handler.trim();
        targetMod = targetMod.trim();
    }

    /** 该规则是否适用于当前正在加载的这个配置。 */
    public boolean matchesConfig(String fileName, String typeName) {
        if (configFile != null && !configFile.isBlank()) {
            String normalized = fileName == null ? "" : fileName.replace('\\', '/');
            String expected = configFile.trim().replace('\\', '/');
            if (!normalized.equals(expected) && !normalized.endsWith("/" + expected)) {
                return false;
            }
        }
        if (configType != null && !configType.isBlank()) {
            return configType.trim().equalsIgnoreCase(typeName);
        }
        return true;
    }

    /** 已加载版本是否落在规则声明的区间内。 */
    public boolean matchesVersion(String loadedVersion) {
        return VersionRange.satisfies(versionRange, loadedVersion);
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(id == null || id.isBlank() ? "(未命名规则)" : id);
        sb.append(" → ").append(targetMod);
        if (versionRange != null && !versionRange.isBlank()) {
            sb.append(' ').append(versionRange);
        }
        sb.append(" [").append(entries.size()).append(" 项]");
        if (!enabled) {
            sb.append("（已停用）");
        }
        return sb.toString();
    }
}
