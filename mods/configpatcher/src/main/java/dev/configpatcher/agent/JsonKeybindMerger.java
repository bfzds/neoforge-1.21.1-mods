package dev.configpatcher.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面向 JSON 配置的键位合并（tweakeroo 这类 masa 系 mod 用）。
 *
 * <p>这类配置里键位长这样：
 * <pre>
 * "entityDataSync": {
 *   "enabled": false,
 *   "hotkey": {
 *     "keys": "LEFT_CONTROL"
 *   }
 * },
 * </pre>
 * 光看 {@code "keys": ...} 这一行不知道它属于哪个功能，所以这里做「路径感知」的逐行扫描：
 * 用缩进后的 {@code "name": &#123;} / {@code &#125;} 维护当前所在层级，把 {@code Generic&gt;entityDataSync&gt;hotkey&gt;keys}
 * 当作键位唯一标识，再按样本里的值替换。
 *
 * <p>结果只改动键位那一行，文件里其它开关、注释位置、键的顺序全部保持原样；
 * 只需要 JDK 能力，可在 Java Agent 阶段使用。
 */
public final class JsonKeybindMerger {

    private static final String KEYS = "keys";

    private JsonKeybindMerger() {
    }

    /** 用样本里的键位覆盖目标文件；目标里样本没有的键位按「文件为准」清空为样本缺失时的处理方式保留原值。 */
    public static String merge(String existing, String sample) {
        Map<String, String> wanted = collect(sample);
        if (wanted.isEmpty()) {
            return existing.endsWith("\n") ? existing : existing + "\n";
        }

        List<String> lines = new ArrayList<>(existing.lines().toList());
        Deque<String> stack = new ArrayDeque<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();

            if (trimmed.startsWith("}")) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
                continue;
            }
            if (trimmed.startsWith("{")) {
                // 数组或根对象开括号：不入栈
                continue;
            }

            String key = quotedKey(trimmed);
            if (key == null) {
                continue;
            }
            if (trimmed.endsWith("{")) {
                stack.addLast(key);
                continue;
            }
            if (KEYS.equals(key)) {
                String path = pathOf(stack);
                String replacement = wanted.get(path);
                if (replacement != null) {
                    lines.set(i, replaceValue(line, replacement));
                }
            }
        }
        return String.join("\n", lines) + "\n";
    }

    /** 收集 {@code 路径 → keys 值}（路径形如 {@code Generic>entityDataSync>hotkey>keys}）。 */
    public static Map<String, String> collect(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        for (String raw : json.lines().toList()) {
            String trimmed = raw.trim();
            if (trimmed.startsWith("}")) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
                continue;
            }
            if (trimmed.startsWith("{")) {
                continue;
            }
            String key = quotedKey(trimmed);
            if (key == null) {
                continue;
            }
            if (trimmed.endsWith("{")) {
                stack.addLast(key);
                continue;
            }
            if (KEYS.equals(key)) {
                result.put(pathOf(stack), stringValue(trimmed));
            }
        }
        return result;
    }

    private static String pathOf(Deque<String> stack) {
        return String.join(">", stack) + ">" + KEYS;
    }

    /** 取出形如 {@code "name": ...} 的键名；不是键值行返回 null。 */
    private static String quotedKey(String trimmed) {
        if (!trimmed.startsWith("\"")) {
            return null;
        }
        int end = trimmed.indexOf('"', 1);
        if (end <= 0) {
            return null;
        }
        int colon = trimmed.indexOf(':', end);
        if (colon < 0) {
            return null;
        }
        return trimmed.substring(1, end);
    }

    /** 取出 {@code "keys": "value"} 里的 value（去掉尾部逗号）。 */
    private static String stringValue(String trimmed) {
        int colon = trimmed.indexOf(':', trimmed.indexOf('"'));
        if (colon < 0) {
            return "";
        }
        String rest = trimmed.substring(colon + 1).trim();
        if (rest.endsWith(",")) {
            rest = rest.substring(0, rest.length() - 1).trim();
        }
        if (rest.length() >= 2 && rest.startsWith("\"") && rest.endsWith("\"")) {
            return rest.substring(1, rest.length() - 1);
        }
        return rest;
    }

    /** 只替换值，保留原来的缩进与行尾逗号。 */
    private static String replaceValue(String line, String value) {
        int colon = line.indexOf(':', line.indexOf('"'));
        if (colon < 0) {
            return line;
        }
        String head = line.substring(0, colon + 1);
        String tail = line.substring(colon + 1);
        boolean comma = tail.trim().endsWith(",");
        return head + " \"" + escape(value) + "\"" + (comma ? "," : "");
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
