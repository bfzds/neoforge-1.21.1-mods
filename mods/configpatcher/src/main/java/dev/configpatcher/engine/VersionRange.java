package dev.configpatcher.engine;

/**
 * 极简版本区间判断，不依赖任何 Maven/NeoForge 版本类，便于单独做单元测试。
 *
 * <p>支持的写法：
 * <ul>
 *     <li>空 / {@code *} —— 不限制</li>
 *     <li>Maven 花括号区间：{@code [1.0.0,2.0.0)}、{@code [1.21.1,)}、{@code (,2.0.0]}</li>
 *     <li>单条比较：{@code >=1.0.0}、{@code <2.0.0}、{@code =1.21.1}</li>
 *     <li>逗号分隔的多条比较（全部满足才算命中）：{@code >=1.0.0,<2.0.0}</li>
 * </ul>
 *
 * <p>版本比较是朴素切段比较（按 {@code . - _ +} 切分，数字段按数值比，数字段大于非数字段），
 * 足以覆盖 Minecraft mod 生态里绝大多数版本号；不使用完整的 Maven 语义。
 */
public final class VersionRange {

    private VersionRange() {
    }

    public static boolean satisfies(String range, String version) {
        if (range == null || range.isBlank() || "*".equals(range.trim())) {
            return true;
        }
        if (version == null || version.isBlank()) {
            // 规则限定了版本，但拿不到目标版本时按“不确定”处理，视为不命中，避免误改。
            return false;
        }
        String trimmed = range.trim();
        char first = trimmed.charAt(0);
        if (first == '[' || first == '(') {
            return satisfiesBracket(trimmed, version.trim());
        }
        for (String part : trimmed.split(",")) {
            if (!satisfiesSingle(part.trim(), version.trim())) {
                return false;
            }
        }
        return true;
    }

    private static boolean satisfiesBracket(String range, String version) {
        char first = range.charAt(0);
        char last = range.charAt(range.length() - 1);
        if ((first != '[' && first != '(') || (last != ']' && last != ')')) {
            throw new IllegalArgumentException("版本区间缺少闭合括号：" + range);
        }
        String body = range.substring(1, range.length() - 1);
        int comma = body.indexOf(',');
        if (comma < 0) {
            // [1.0.0] —— 精确等于
            return compare(version, body.trim()) == 0;
        }
        String lower = body.substring(0, comma).trim();
        String upper = body.substring(comma + 1).trim();
        if (!lower.isEmpty()) {
            int c = compare(version, lower);
            if (c < 0 || (c == 0 && first == '(')) {
                return false;
            }
        }
        if (!upper.isEmpty()) {
            int c = compare(version, upper);
            if (c > 0 || (c == 0 && last == ')')) {
                return false;
            }
        }
        return true;
    }

    private static boolean satisfiesSingle(String condition, String version) {
        if (condition.isEmpty() || "*".equals(condition)) {
            return true;
        }
        if (condition.startsWith(">=")) {
            return compare(version, condition.substring(2).trim()) >= 0;
        }
        if (condition.startsWith("<=")) {
            return compare(version, condition.substring(2).trim()) <= 0;
        }
        if (condition.startsWith(">")) {
            return compare(version, condition.substring(1).trim()) > 0;
        }
        if (condition.startsWith("<")) {
            return compare(version, condition.substring(1).trim()) < 0;
        }
        if (condition.startsWith("=")) {
            return compare(version, condition.substring(1).trim()) == 0;
        }
        return compare(version, condition) == 0;
    }

    /** 朴素版本比较：返回负数 / 0 / 正数。 */
    public static int compare(String left, String right) {
        String[] a = tokenize(left);
        String[] b = tokenize(right);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            String x = i < a.length ? a[i] : "0";
            String y = i < b.length ? b[i] : "0";
            boolean xNumeric = isNumeric(x);
            boolean yNumeric = isNumeric(y);
            if (xNumeric && yNumeric) {
                int c = Long.compare(parseLong(x), parseLong(y));
                if (c != 0) {
                    return c;
                }
            } else if (xNumeric != yNumeric) {
                // 数字段优先于非数字段（类似“发布版 > 预发布版”）
                return xNumeric ? 1 : -1;
            } else {
                int c = x.compareToIgnoreCase(y);
                if (c != 0) {
                    return c;
                }
            }
        }
        return 0;
    }

    private static String[] tokenize(String version) {
        return version.trim().split("[.\\-_+]");
    }

    private static boolean isNumeric(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static long parseLong(String token) {
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException ex) {
            return Long.MAX_VALUE;
        }
    }
}
