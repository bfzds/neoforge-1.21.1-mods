package dev.configpatcher.rule;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.configpatcher.Log;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规则文件的读写。
 *
 * <p>规则文件位置：{@code <游戏目录>/config/configpatcher/rules.json}。
 * 首次运行会从 mod jar 内的 {@code /configpatcher/default-rules.json} 复制一份模板出来。
 *
 * <p>解析是“逐条容错”的：某一条规则写错只会被跳过并打日志，不会让整个 mod 起不来；
 * 顶层还有 {@code schemaVersion}，将来规则格式升级时可以据此做迁移。
 */
public final class RuleLoader {

    public static final String CONFIG_SUBDIR = "configpatcher";
    public static final String RULES_FILE_NAME = "rules.json";
    /** 当前规则文件格式版本。 */
    public static final int SCHEMA_VERSION = 1;

    private static final String DEFAULT_RESOURCE = "/configpatcher/default-rules.json";

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private RuleLoader() {
    }

    public static Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve(CONFIG_SUBDIR);
    }

    public static Path rulesPath() {
        return configDir().resolve(RULES_FILE_NAME);
    }

    /** 规则文件不存在时写出一份默认模板。 */
    public static void ensureDefaultRulesFile() {
        Path path = rulesPath();
        if (Files.isRegularFile(path)) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            String content = readDefaultResource();
            if (content == null || content.isBlank()) {
                content = GSON.toJson(emptyTemplate());
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
            Log.LOGGER.info("已生成默认规则文件：{}", path);
        } catch (IOException ex) {
            Log.LOGGER.warn("生成默认规则文件失败：{}", ex.toString());
        }
    }

    /** 读取并解析规则文件；任何异常都退化成空规则集。 */
    public static RuleSet load() {
        Path path = rulesPath();
        if (!Files.isRegularFile(path)) {
            return RuleSet.empty(path.toString());
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            return parse(json, path.toString());
        } catch (IOException ex) {
            Log.LOGGER.error("读取规则文件失败：{}", ex.toString());
            return RuleSet.empty(path.toString());
        }
    }

    /** 纯函数式解析入口，方便单元测试。 */
    public static RuleSet parse(String json, String source) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                Log.LOGGER.error("规则文件顶层必须是 JSON 对象：{}", source);
                return RuleSet.empty(source);
            }
            root = parsed.getAsJsonObject();
        } catch (RuntimeException ex) {
            Log.LOGGER.error("规则文件 JSON 语法错误（{}）：{}", source, ex.getMessage());
            return RuleSet.empty(source);
        }

        if (root.has("schemaVersion") && root.get("schemaVersion").isJsonPrimitive()) {
            int version = root.get("schemaVersion").getAsInt();
            if (version > SCHEMA_VERSION) {
                Log.LOGGER.warn("规则文件格式版本 {} 高于本 mod 支持的 {}，更高版本的字段会被忽略：{}",
                        version, SCHEMA_VERSION, source);
            }
        }

        boolean enabled = true;
        if (root.has("enabled") && root.get("enabled").isJsonPrimitive()) {
            enabled = root.get("enabled").getAsBoolean();
        }

        List<PatchRule> rules = new ArrayList<>();
        JsonElement rulesElement = root.get("rules");
        if (rulesElement != null && rulesElement.isJsonArray()) {
            int index = 0;
            for (JsonElement element : rulesElement.getAsJsonArray()) {
                index++;
                try {
                    rules.add(parseRule(element, index));
                } catch (RuntimeException ex) {
                    Log.LOGGER.warn("第 {} 条规则解析失败，已跳过：{}", index, ex.getMessage());
                }
            }
        }
        return new RuleSet(source, enabled, rules);
    }

    private static PatchRule parseRule(JsonElement element, int index) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("规则必须是 JSON 对象");
        }
        JsonObject object = element.getAsJsonObject();
        String id = stringOr(object, "id", "rule#" + index);
        String target = stringOr(object, "target", null);
        String comment = stringOr(object, "comment", null);
        boolean ruleEnabled = !object.has("enabled") || !object.get("enabled").isJsonPrimitive()
                || object.get("enabled").getAsBoolean();
        String versionRange = stringOr(object, "versionRange", null);
        String configFile = stringOr(object, "configFile", null);
        String configType = stringOr(object, "configType", null);
        String handler = stringOr(object, "handler", PatchRule.DEFAULT_HANDLER);
        boolean required = object.has("required") && object.get("required").isJsonPrimitive()
                && object.get("required").getAsBoolean();
        List<PatchEntry> entries = parseEntries(object.get("patches"));
        return new PatchRule(id, comment, target, ruleEnabled, versionRange, configFile, configType,
                handler, required, entries);
    }

    /** {@code patches} 支持两种写法：对象 {@code {"a.b": 1}} 或数组 {@code [{"path":"a.b","value":1}]}。 */
    private static List<PatchEntry> parseEntries(JsonElement patches) {
        if (patches == null || patches.isJsonNull()) {
            throw new IllegalArgumentException("规则缺少 patches");
        }
        List<PatchEntry> entries = new ArrayList<>();
        if (patches.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : patches.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (isEntryObject(value)) {
                    entries.add(parseEntryObject(value.getAsJsonObject(), entry.getKey()));
                } else {
                    entries.add(new PatchEntry(entry.getKey(), value));
                }
            }
        } else if (patches.isJsonArray()) {
            for (JsonElement item : patches.getAsJsonArray()) {
                if (!isEntryObject(item)) {
                    throw new IllegalArgumentException("patches 数组的元素必须是对象");
                }
                entries.add(parseEntryObject(item.getAsJsonObject(), null));
            }
        } else {
            throw new IllegalArgumentException("patches 必须是对象或数组");
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("规则没有任何改写项");
        }
        return entries;
    }

    /** 判断一个 JSON 值是不是“完整写法”的改写项对象（含 path / value 字段）。 */
    private static boolean isEntryObject(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return false;
        }
        JsonObject object = element.getAsJsonObject();
        return object.has("path") && object.has("value");
    }

    private static PatchEntry parseEntryObject(JsonObject object, String fallbackPath) {
        String path = stringOr(object, "path", fallbackPath);
        JsonElement value = object.get("value");
        List<String> aliases = new ArrayList<>();
        JsonElement aliasElement = object.get("aliases");
        if (aliasElement != null && aliasElement.isJsonArray()) {
            JsonArray array = aliasElement.getAsJsonArray();
            for (JsonElement alias : array) {
                if (!alias.isJsonNull()) {
                    aliases.add(alias.getAsString());
                }
            }
        }
        boolean optional = object.has("optional") && object.get("optional").isJsonPrimitive()
                && object.get("optional").getAsBoolean();
        return new PatchEntry(path, aliases, optional, value);
    }

    private static String stringOr(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        return element.getAsString();
    }

    private static String readDefaultResource() {
        try (InputStream in = RuleLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
    }

    /** 供 dump 功能复用的 pretty printer。 */
    public static String toPrettyJson(JsonElement element) {
        return GSON.toJson(element);
    }

    private static JsonObject emptyTemplate() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("enabled", true);
        root.add("rules", new JsonArray());
        return root;
    }
}
