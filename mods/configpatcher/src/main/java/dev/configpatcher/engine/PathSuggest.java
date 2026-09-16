package dev.configpatcher.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * “配置项改名了怎么办”的核心预案：当规则里的路径找不到时，
 * 从目标 mod 实际存在的路径里挑出最接近的几个作为候选提示。
 *
 * <p>匹配用的是归一化后的编辑距离：先统一小写、去掉 {@code _ - 空格}，
 * 再算 Levenshtein 距离，取距离最小且不超过阈值的若干条。
 */
public final class PathSuggest {

    private PathSuggest() {
    }

    public static List<String> suggest(Collection<List<String>> knownPaths, List<String> wanted, int limit) {
        if (knownPaths == null || knownPaths.isEmpty() || wanted == null || wanted.isEmpty()) {
            return List.of();
        }
        String target = normalize(join(wanted));
        int threshold = Math.max(3, target.length() / 3);

        List<Scored> scored = new ArrayList<>();
        for (List<String> candidate : knownPaths) {
            String normalized = normalize(join(candidate));
            int distance = levenshtein(target, normalized);
            if (distance <= threshold) {
                scored.add(new Scored(join(candidate), distance));
            }
        }
        scored.sort((left, right) -> Integer.compare(left.distance, right.distance));

        List<String> result = new ArrayList<>();
        for (Scored item : scored) {
            if (result.size() >= limit) {
                break;
            }
            result.add(item.path);
        }
        return result;
    }

    public static String join(List<String> path) {
        return String.join(".", path);
    }

    /** 归一化：小写 + 去掉分隔用符号，让 enableThing / enable_thing / Enable-Thing 视为同一个名字。 */
    static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_' || c == '-' || c == ' ' || c == '.') {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    /** 把候选路径格式化成一句人类可读提示。 */
    public static String describe(List<String> suggestions) {
        if (suggestions.isEmpty()) {
            return "没有相近的候选（该配置项可能已被目标 mod 移除）";
        }
        return "最接近的候选：" + String.join("、", suggestions);
    }

    private record Scored(String path, int distance) {
    }
}
