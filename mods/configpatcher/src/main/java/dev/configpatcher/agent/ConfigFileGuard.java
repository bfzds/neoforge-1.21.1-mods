package dev.configpatcher.agent;

import java.util.Locale;

/**
 * 配置文件「内容是否可信」的统一检查。
 *
 * <p>两个方向都要用：
 * <ul>
 *     <li><b>导出方向</b>（实例 → 样本）：崩溃 / 启动失败退出时实例里的文件可能是半成品，
 *         整份回写会把样本库带坏（真实案例：{@code tweakeroo.json} 只剩一个换行符）；</li>
 *     <li><b>注入方向</b>（样本 → 实例）：合并结果如果不成样子，也不能往目标文件里写，
 *         否则目标 mod 读不懂，会直接回落到默认配置（真实案例：新实例首次启动时目标文件不存在，
 *         合并器输出 1 个换行符，tweakeroo 的键位被整体打回默认）。</li>
 * </ul>
 *
 * <p>只用 JDK 自带能力，因此 Agent 与 mod 两侧都能用。
 */
public final class ConfigFileGuard {

    /** 键位样本至少要有的 {@code "keys"} 行数；真实样本有 247 行，低于这个数基本可以断定是半成品。 */
    public static final int MIN_KEY_LINES = 100;

    private ConfigFileGuard() {
    }

    /**
     * 内容检查：返回拒绝原因，{@code null} 表示通过。
     *
     * @param relativePath 配置文件在实例内的相对路径（用来判断文件类型）
     * @param keybindFile  这一项是不是键位文件。是的话额外要求 {@code "keys"} 行数达标；
     *                     不能对所有 JSON 都查 keys，否则会误伤 {@code recipe_type_names.json}
     *                     这类本来就没有 keys 的正常文件
     * @param content      待检查的内容
     */
    public static String rejectReason(String relativePath, boolean keybindFile, String content) {
        if (content == null || content.isBlank()) {
            return "内容为空或只有空白";
        }
        String lower = relativePath == null ? "" : relativePath.toLowerCase(Locale.ROOT);
        if (isJsonPath(relativePath)) {
            String body = content.strip();
            if (!body.startsWith("{")) {
                return "不是 JSON 对象（首字符不是 {）";
            }
            if (!balanced(body)) {
                return "JSON 括号或引号不配对，疑似写了一半";
            }
        }
        if (keybindFile && lower.endsWith(".json")) {
            long keys = content.lines()
                    .filter(line -> line.trim().startsWith("\"keys\""))
                    .count();
            if (keys < MIN_KEY_LINES) {
                return "keys 行只有 " + keys + " 行（少于 " + MIN_KEY_LINES + "），疑似半成品";
            }
        }
        return null;
    }

    /** 内容是否通过检查。 */
    public static boolean isUsable(String relativePath, boolean keybindFile, String content) {
        return rejectReason(relativePath, keybindFile, content) == null;
    }

    /**
     * 注入方向的检查：合并结果只要「结构合法、并且确实带着 keys 行」就可以写。
     *
     * <p>这里**不能**套用导出方向那条「keys 行数 ≥ 100」：合并是「在目标文件里逐行替换」，
     * 不会补全目标本来就缺的配置项，所以目标文件小的时候，合并结果的 keys 行数自然也少
     * （例如只有几个热键的精简配置）。行数门槛只在判断「整份文件是不是半成品」时才有意义。
     */
    public static String rejectMergedResult(String relativePath, String content) {
        String structural = rejectReason(relativePath, false, content);
        if (structural != null) {
            return structural;
        }
        if (isJsonPath(relativePath)
                && content.lines().noneMatch(line -> line.trim().startsWith("\"keys\""))) {
            return "合并结果里没有任何 keys 行";
        }
        return null;
    }

    /** 路径是不是 JSON 配置。 */
    public static boolean isJsonPath(String relativePath) {
        return relativePath != null && relativePath.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    /** 花括号 / 方括号 / 引号是否配平（跳过字符串内部与转义字符）。 */
    public static boolean balanced(String text) {
        int brace = 0;
        int bracket = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{' -> brace++;
                case '}' -> {
                    if (--brace < 0) {
                        return false;
                    }
                }
                case '[' -> bracket++;
                case ']' -> {
                    if (--bracket < 0) {
                        return false;
                    }
                }
                default -> {
                    // 其它字符与配对无关
                }
            }
        }
        return !inString && brace == 0 && bracket == 0;
    }
}
