package dev.configpatcher.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * TOML / INI 风格的「单个键值改写」（纯文本级，JDK 自带能力，可在 Agent 阶段使用）。
 *
 * <p>处理形如：
 * <pre>
 * [terminals]
 * 	terminalMargin = 25        →  terminalMargin = 0
 * </pre>
 * 只替换「值」那一段，保留缩进（Tab / 空格）与行尾注释，文件其余内容一字不动。
 *
 * <p>键用点分路径表示：{@code terminals.terminalMargin} 表示 {@code [terminals]} 段里的
 * {@code terminalMargin}；只写 {@code giveMode} 表示全文件找这个键（JEI 的 ini 就是这种）。
 */
public final class TomlValueEditor {

    private TomlValueEditor() {
    }

    /**
     * @param content 改写后的完整内容（未命中时原样返回）
     * @param changed 是否真的改了
     */
    public record Result(String content, boolean changed) {
    }

    public static Result set(String content, String dottedKey, String value) {
        String source = content == null ? "" : content;
        if (dottedKey == null || dottedKey.isBlank()) {
            return new Result(source, false);
        }

        String[] parts = dottedKey.split("\\.");
        String key = parts[parts.length - 1];
        StringBuilder section = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (section.length() > 0) {
                section.append('.');
            }
            section.append(parts[i]);
        }

        List<String> lines = new ArrayList<>(source.lines().toList());
        int from = 0;
        int to = lines.size();
        if (section.length() > 0) {
            String wantedSection = section.toString();
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    if (found) {
                        to = i;
                        break;
                    }
                    String name = trimmed.substring(1, trimmed.length() - 1).trim();
                    if (name.equalsIgnoreCase(wantedSection)) {
                        found = true;
                        from = i + 1;
                    }
                }
            }
            if (!found) {
                return new Result(source, false);
            }
        }

        for (int i = from; i < to; i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            String foundKey = trimmed.substring(0, equals).trim();
            if (!foundKey.equalsIgnoreCase(key)) {
                continue;
            }
            String rewritten = rewrite(line, foundKey, value);
            if (rewritten.equals(line)) {
                return new Result(source, false);
            }
            lines.set(i, rewritten);
            return new Result(String.join("\n", lines) + "\n", true);
        }
        return new Result(source, false);
    }

    /** 保留缩进与行尾注释，只换掉等号后面的值。 */
    static String rewrite(String line, String key, String value) {
        int keyStart = line.indexOf(key);
        if (keyStart < 0) {
            return line;
        }
        String beforeKey = line.substring(0, keyStart);
        String afterKey = line.substring(keyStart + key.length());
        int equals = afterKey.indexOf('=');
        if (equals < 0) {
            return line;
        }
        String rest = afterKey.substring(equals + 1);
        String trailing = "";
        int comment = rest.indexOf('#');
        if (comment >= 0) {
            trailing = " " + rest.substring(comment).trim();
        }
        return beforeKey + key + " = " + value + trailing;
    }
}
