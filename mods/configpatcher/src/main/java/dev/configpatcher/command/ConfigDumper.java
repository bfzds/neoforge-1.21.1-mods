package dev.configpatcher.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.configpatcher.Log;
import dev.configpatcher.engine.ModPresence;
import dev.configpatcher.engine.PathSuggest;
import dev.configpatcher.handler.ConfigValuePatchHandler;
import dev.configpatcher.rule.RuleLoader;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * “配置项改名/改类型”的排查工具：
 * 把某个目标 mod 当前真实存在的配置项全部导出，并顺手生成一份可以直接改的规则草稿。
 *
 * <p>产出两个文件（都在 {@code config/configpatcher/} 下）：
 * <ul>
 *     <li>{@code dump-<modid>.txt} —— 全部路径 + 当前值 + 类型，人工对照用</li>
 *     <li>{@code draft-<modid>.json} —— 可直接粘到 rules.json 的规则草稿</li>
 * </ul>
 */
public final class ConfigDumper {

    private static final int PREVIEW_LINES = 20;

    private ConfigDumper() {
    }

    /**
     * @return null 表示找不到该 mod 的任何配置
     */
    public static Result dump(String modId) {
        List<ModConfig> configs = findConfigs(modId);
        if (configs.isEmpty()) {
            return null;
        }

        List<String> lines = new ArrayList<>();
        lines.add("=== configpatcher dump: " + modId + " ===");
        lines.add("mod 显示名: " + ModPresence.displayNameOf(modId));
        lines.add("已安装版本: " + ModPresence.versionOf(modId));
        lines.add("");

        JsonObject draftRoot = new JsonObject();
        draftRoot.addProperty("schemaVersion", RuleLoader.SCHEMA_VERSION);
        draftRoot.addProperty("enabled", true);
        JsonArray draftRules = new JsonArray();

        int totalEntries = 0;
        int usableConfigs = 0;
        for (ModConfig config : configs) {
            lines.add("--- " + config.getFileName() + " (" + config.getType().name() + ") ---");
            Object specObject = config.getSpec();
            if (!(specObject instanceof ModConfigSpec spec) || !spec.isLoaded()) {
                lines.add("  (该配置未加载或不是 ModConfigSpec，无法导出；可改用 \"handler\": \"toml-file\")");
                lines.add("");
                continue;
            }
            Map<List<String>, ModConfigSpec.ConfigValue<?>> values = ConfigValuePatchHandler.index(spec.getValues());
            if (values.isEmpty()) {
                lines.add("  (没有可导出的配置项)");
                lines.add("");
                continue;
            }
            usableConfigs++;

            JsonObject patches = new JsonObject();
            for (Map.Entry<List<String>, ModConfigSpec.ConfigValue<?>> entry : values.entrySet()) {
                String path = PathSuggest.join(entry.getKey());
                ModConfigSpec.ConfigValue<?> configValue = entry.getValue();
                Object raw = configValue.getRaw();
                Object template = raw != null ? raw : configValue.getDefault();
                String type = template == null ? "unknown" : template.getClass().getSimpleName();
                lines.add("  " + padRight(path, 48) + " = " + padRight(String.valueOf(raw), 24) + " (" + type + ")");
                patches.add(path, toJson(raw));
                totalEntries++;
            }
            lines.add("");

            JsonObject rule = new JsonObject();
            rule.addProperty("id", modId + "-" + config.getType().name().toLowerCase(java.util.Locale.ROOT) + "-draft");
            rule.addProperty("comment", "自动生成的草稿；删掉不需要的条目后即可使用");
            rule.addProperty("target", modId);
            rule.addProperty("versionRange", "[" + versionOrZero(modId) + ",)");
            rule.addProperty("configFile", config.getFileName());
            rule.addProperty("configType", config.getType().name());
            rule.add("patches", patches);
            draftRules.add(rule);
        }

        draftRoot.add("rules", draftRules);

        Path dumpFile = null;
        Path draftFile = null;
        try {
            Files.createDirectories(RuleLoader.configDir());
            dumpFile = RuleLoader.configDir().resolve("dump-" + modId + ".txt");
            Files.write(dumpFile, lines, StandardCharsets.UTF_8);
            draftFile = RuleLoader.configDir().resolve("draft-" + modId + ".json");
            Files.writeString(draftFile, RuleLoader.toPrettyJson(draftRoot), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Log.LOGGER.warn("导出配置项失败：{}", ex.toString());
        }

        List<String> preview = lines.size() > PREVIEW_LINES ? lines.subList(0, PREVIEW_LINES) : lines;
        return new Result(dumpFile, draftFile, usableConfigs, totalEntries, List.copyOf(preview));
    }

    private static List<ModConfig> findConfigs(String modId) {
        List<ModConfig> result = new ArrayList<>();
        for (ModConfig.Type type : ModConfig.Type.values()) {
            try {
                for (ModConfig config : ModConfigs.getConfigSet(type)) {
                    if (modId.equalsIgnoreCase(config.getModId())) {
                        result.add(config);
                    }
                }
            } catch (Throwable throwable) {
                Log.LOGGER.warn("读取 {} 配置集合失败：{}", type, throwable.toString());
            }
        }
        return result;
    }

    private static String versionOrZero(String modId) {
        String version = ModPresence.versionOf(modId);
        if (version == null || version.isBlank()) {
            return "0.0.0";
        }
        int dash = version.indexOf('-');
        return dash > 0 ? version.substring(0, dash) : version;
    }

    private static JsonElement toJson(Object value) {
        if (value == null) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof String text) {
            return new JsonPrimitive(text);
        }
        if (value instanceof Collection<?> collection) {
            JsonArray array = new JsonArray();
            for (Object item : collection) {
                array.add(toJson(item));
            }
            return array;
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    private static String padRight(String text, int width) {
        if (text.length() >= width) {
            return text + " ";
        }
        return text + " ".repeat(width - text.length());
    }

    /**
     * @param dumpFile   完整清单文件（写失败时为 null）
     * @param draftFile  规则草稿文件（写失败时为 null）
     * @param configCount 成功导出的配置文件数
     * @param entryCount  导出的配置项总数
     * @param preview    前若干行，方便直接回复到聊天栏
     */
    public record Result(Path dumpFile, Path draftFile, int configCount, int entryCount, List<String> preview) {
    }
}
