package dev.configpatcher.rule;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条具体的改写项：定位目标配置里的某个路径，并给出要写入的值。
 *
 * <p>JSON 里支持两种写法：
 * <pre>
 * 简写：  "features.enableThing": false
 * 完整：  { "path": "features.enableThing",
 *          "aliases": ["features.enable_thing", "enableThing"],
 *          "optional": true,
 *          "value": false }
 * </pre>
 *
 * <p>{@code aliases} 是“配置项改名预案”的第一道防线：目标 mod 把
 * {@code enableThing} 改成 {@code enable_thing} 时，不需要改规则，按顺序取第一个命中的路径即可。
 * 全都命中不了时，引擎还会用相似度给出候选路径提示。
 *
 * @param path     首选路径
 * @param aliases  备用路径（旧名 / 新名 / 不同版本的写法）
 * @param optional true 表示找不到该配置项时不算异常（目标 mod 删掉了这一项时用）
 * @param value    目标值，JSON 原始值
 */
public record PatchEntry(String path, List<String> aliases, boolean optional, JsonElement value) {

    public PatchEntry(String path, JsonElement value) {
        this(path, List.of(), false, value);
    }

    public PatchEntry {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("改写项缺少 path");
        }
        if (value == null) {
            throw new IllegalArgumentException("改写项缺少 value：" + path);
        }
        path = normalize(path);
        List<String> cleaned = new ArrayList<>();
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank()) {
                    cleaned.add(normalize(alias));
                }
            }
        }
        aliases = List.copyOf(cleaned);
    }

    /** 依次尝试的路径列表：首选路径在最前。 */
    public List<String> candidates() {
        List<String> all = new ArrayList<>(1 + aliases.size());
        all.add(path);
        all.addAll(aliases);
        return all;
    }

    /** 首选路径的层级拆分。 */
    public List<String> segments() {
        return split(path);
    }

    public static List<String> split(String dottedPath) {
        return List.of(normalize(dottedPath).split("\\."));
    }

    /** 把 {@code .a.b} 这类写法归一化，去掉首尾多余的点。 */
    public static String normalize(String dottedPath) {
        String result = dottedPath == null ? "" : dottedPath.trim();
        while (result.startsWith(".")) {
            result = result.substring(1);
        }
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public String displayValue() {
        return value.toString();
    }

    /** 是否配置了改名预案。 */
    public boolean hasAliases() {
        return !aliases.isEmpty();
    }
}
