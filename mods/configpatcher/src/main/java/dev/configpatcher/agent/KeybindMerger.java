package dev.configpatcher.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 键位合并。
 *
 * <p>整份覆盖 {@code options.txt} 会连带改掉分辨率、语言、视野等一堆无关设置，
 * 所以这里只做「键位 + 资源包」这两件事：
 * <ul>
 *     <li>把样本里所有 {@code key_xxx:...} 行写进目标文件（目标缺的键补上，目标多的键保留）；</li>
 *     <li>把注入的资源包名追加进 {@code resourcePacks}，并从 {@code incompatibleResourcePacks} 里移除，
 *         保证材质包能被游戏真正启用。</li>
 * </ul>
 *
 * <p>纯字符串处理，不依赖任何库，因此可以在 Java Agent 阶段（FML 启动之前）安全使用。
 */
public final class KeybindMerger {

    private static final String KEY_PREFIX = "key_";
    private static final String RESOURCE_PACKS = "resourcePacks:";
    private static final String INCOMPATIBLE = "incompatibleResourcePacks:";

    private KeybindMerger() {
    }

    /** 用样本里的键位覆盖 / 补全目标 options.txt 的内容，其余设置原样保留。 */
    public static String merge(String existing, String sample) {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (String line : sample.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith(KEY_PREFIX) && trimmed.indexOf(':') > 0) {
                wanted.put(keyOf(trimmed), trimmed);
            }
        }
        List<String> lines = new ArrayList<>(existing.lines().toList());
        if (wanted.isEmpty()) {
            return join(lines);
        }

        Set<String> applied = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            String key = keyOf(trimmed);
            if (trimmed.startsWith(KEY_PREFIX) && wanted.containsKey(key)) {
                lines.set(i, wanted.get(key));
                applied.add(key);
            }
        }
        for (Map.Entry<String, String> entry : wanted.entrySet()) {
            if (!applied.contains(entry.getKey())) {
                lines.add(entry.getValue());
            }
        }
        return join(lines);
    }

    /**
     * 「退出回流」方向的合并（口径 A·只增改）：把实例的键位并进全局样本。
     *
     * <p>与 {@link #merge(String, String)}（注入：样本 → 实例）正好相反，这是导出方向（实例 → 样本）：
     * <ul>
     *     <li>实例里存在且与全局不同的 {@code key_*} 行 —— 原位更新进全局（保持全局的行顺序）；</li>
     *     <li>实例有、全局没有的键 —— 追加到全局末尾；</li>
     *     <li>全局有、实例没有的键 —— 原样保留，绝不删除。</li>
     * </ul>
     *
     * <p>键位少的实例退出因此不会再把全局砍短（此前样本是整份覆盖，谁最后退出谁说了算，
     * 全局键位曾被从 528 条砍到 464 条）。实例没有键位行时原样返回全局。
     *
     * @param global   全局样本当前内容（null / 空串也可以，此时结果就是实例的键位行）
     * @param instance 实例的键位内容（只取其中的 {@code key_*} 行）
     */
    public static String mergeIntoGlobal(String global, String instance) {
        Map<String, String> fromInstance = new LinkedHashMap<>();
        for (String line : instance.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith(KEY_PREFIX) && trimmed.indexOf(':') > 0) {
                fromInstance.put(keyOf(trimmed), trimmed);
            }
        }
        if (fromInstance.isEmpty()) {
            return global;
        }
        List<String> lines = global == null ? new ArrayList<>() : new ArrayList<>(global.lines().toList());
        Set<String> applied = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            String key = keyOf(trimmed);
            if (trimmed.startsWith(KEY_PREFIX) && fromInstance.containsKey(key)) {
                lines.set(i, fromInstance.get(key));
                applied.add(key);
            }
        }
        for (Map.Entry<String, String> entry : fromInstance.entrySet()) {
            if (!applied.contains(entry.getKey())) {
                lines.add(entry.getValue());
            }
        }
        return join(lines);
    }

    /** 把资源包名（形如 {@code file/xxx.zip}）追加进 resourcePacks，并从 incompatible 列表移除。 */
    public static String ensureResourcePacks(String existing, Collection<String> packNames) {
        if (packNames == null || packNames.isEmpty()) {
            return existing.endsWith("\n") ? existing : existing + "\n";
        }
        List<String> lines = new ArrayList<>(existing.lines().toList());
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith(RESOURCE_PACKS)) {
                found = true;
                List<String> packs = parseList(trimmed.substring(RESOURCE_PACKS.length()));
                for (String name : packNames) {
                    if (!packs.contains(name)) {
                        packs.add(name);
                    }
                }
                lines.set(i, RESOURCE_PACKS + toJson(packs));
            } else if (trimmed.startsWith(INCOMPATIBLE)) {
                List<String> packs = parseList(trimmed.substring(INCOMPATIBLE.length()));
                packs.removeAll(packNames);
                lines.set(i, INCOMPATIBLE + toJson(packs));
            }
        }
        if (!found) {
            lines.add(RESOURCE_PACKS + toJson(new ArrayList<>(packNames)));
        }
        return join(lines);
    }

    private static String keyOf(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? line : line.substring(0, colon).trim();
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines) + "\n";
    }

    static List<String> parseList(String json) {
        List<String> result = new ArrayList<>();
        String body = json.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        for (String item : body.split(",")) {
            String value = item.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            result.add(value);
        }
        return result;
    }

    static String toJson(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }
}
