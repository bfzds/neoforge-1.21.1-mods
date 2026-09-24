package dev.configpatcher.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * “关闭游戏自动导出”的执行体：把实例里**当前生效**的配置回写成本地样本库的文件。
 *
 * <p>和 {@link AgentInjector} 正好反过来：注入是「样本 → 实例」，导出是「实例 → 样本」。
 * 两者合起来才是闭环：在任意实例里把键位调好 → 关游戏时自动沉淀成样本 → 其它实例下次启动照这套走。
 *
 * <h2>导出哪些</h2>
 * <ul>
 *     <li>{@code keybindSource} / {@code keybindTarget}：从实例的 {@code options.txt} 里抽出所有
 *         {@code key_*} 行，写成键位样本（分辨率、语言等无关设置不带进样本）；</li>
 *     <li>{@code keybindFileOverrides}：把实例里的目标文件整份回写成样本（如 tweakeroo.json）；</li>
 *     <li>{@code fileOverrides}：同上（如 recipe_type_names.json）。</li>
 * </ul>
 *
 * <h2>不导出哪些</h2>
 * <ul>
 *     <li>源写成 {@code preset:xxx} 的项 —— 那是打包在 jar 里的只读预设，写不回去；</li>
 *     <li>{@code valueEdits} 的单键改写 —— 它们是「固定目标值」（如 AE2 的 terminalMargin=0），
 *         不是用户会在游戏里调的项，所以不参与回流。</li>
 * </ul>
 *
 * <h2>安全设计</h2>
 * <ul>
 *     <li>写样本前先把旧样本复制到 {@code <样本目录>/.backup/<时间戳>/}，导错了还能回滚；</li>
 *     <li>内容与样本一致时直接跳过，不写文件、不产生备份；</li>
 *     <li>任何一步失败都只记日志，绝不影响游戏退出。</li>
 * </ul>
 */
public final class SampleExporter {

    /** 打包在 jar 里的只读预设前缀，这类「源」无法回写。 */
    private static final String PRESET_PREFIX = "preset:";

    /** 每次导出前，旧样本备份到样本目录下的这个子目录。 */
    private static final String BACKUP_DIR = ".backup";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private SampleExporter() {
    }

    /**
     * 执行一次导出：把 {@code gameDir} 里当前生效的配置回写到各样本源文件。
     *
     * @return 逐条结果，供日志展示
     */
    public static List<AgentInjector.Action> export(Path gameDir, AgentInjector.Settings settings) {
        List<AgentInjector.Action> actions = new ArrayList<>();
        if (gameDir == null || settings == null) {
            return actions;
        }

        exportKeybindSample(gameDir, settings, actions);

        for (AgentInjector.FileOverride override : settings.keybindFileOverrides()) {
            exportFileOverride(gameDir, override, "键位文件", true, actions);
        }
        for (AgentInjector.FileOverride override : settings.fileOverrides()) {
            exportFileOverride(gameDir, override, "文件", false, actions);
        }
        return actions;
    }

    /** 键位样本：只把实例 options.txt 里的 {@code key_*} 行带进样本。 */
    private static void exportKeybindSample(Path gameDir, AgentInjector.Settings settings,
                                            List<AgentInjector.Action> actions) {
        String source = settings.keybindSource();
        if (!isWritableSource(source)) {
            return;
        }
        String targetName = settings.keybindTarget() == null || settings.keybindTarget().isBlank()
                ? "options.txt"
                : settings.keybindTarget();
        Path target = gameDir.resolve(targetName);
        if (!Files.isRegularFile(target)) {
            actions.add(new AgentInjector.Action("export", nameOf(source), "实例里没有 " + targetName + "，跳过", false));
            return;
        }
        try {
            List<String> keyLines = new ArrayList<>();
            for (String line : Files.readAllLines(target, StandardCharsets.UTF_8)) {
                if (line.trim().startsWith("key_")) {
                    keyLines.add(line);
                }
            }
            if (keyLines.isEmpty()) {
                actions.add(new AgentInjector.Action("export", nameOf(source),
                        targetName + " 里没有键位行，跳过", false));
                return;
            }
            boolean written = backupAndWrite(Path.of(source), String.join("\n", keyLines) + "\n");
            actions.add(new AgentInjector.Action("export", nameOf(source),
                    written ? "已回写 " + keyLines.size() + " 条键位" : "与样本一致，无需回写", written));
        } catch (IOException ex) {
            actions.add(new AgentInjector.Action("export", nameOf(source), "回写失败：" + ex.getMessage(), false));
        }
    }

    /**
     * 整份文件类：目标文件原样回写成样本。
     *
     * @param keybindFile 这一项是不是键位文件（是的话多一道「样本里还有没有 keys」的检查）
     */
    private static void exportFileOverride(Path gameDir, AgentInjector.FileOverride override, String kind,
                                           boolean keybindFile, List<AgentInjector.Action> actions) {
        if (!isWritableSource(override.from())) {
            return;
        }
        Path target = gameDir.resolve(override.to());
        if (!Files.isRegularFile(target)) {
            actions.add(new AgentInjector.Action("export", nameOf(override.from()),
                    "实例里没有 " + override.to() + "，跳过", false));
            return;
        }
        try {
            String content = Files.readString(target, StandardCharsets.UTF_8);
            String reject = rejectReason(override.to(), keybindFile, content);
            if (reject != null) {
                // 崩溃 / 启动失败退出时实例里可能只剩半成品，整份回写会把样本带坏 —— 直接跳过
                actions.add(new AgentInjector.Action("export", nameOf(override.from()),
                        "内容异常（" + reject + "），已跳过回写，样本保持不变", false));
                return;
            }
            boolean written = backupAndWrite(Path.of(override.from()), content);
            actions.add(new AgentInjector.Action("export", nameOf(override.from()),
                    written ? "已回写（" + kind + "，源：" + override.to() + "）" : "与样本一致，无需回写", written));
        } catch (IOException ex) {
            actions.add(new AgentInjector.Action("export", nameOf(override.from()),
                    "回写失败：" + ex.getMessage(), false));
        }
    }

    /** 键位样本至少要有的 {@code "keys"} 行数；真实样本有 247 行，低于这个数基本可以断定是半成品。 */
    private static final int MIN_KEY_LINES = 100;

    /**
     * 回写前的健康检查：返回拒绝原因，{@code null} 表示通过。
     *
     * <p>起因：崩溃 / mod 加载失败时游戏照样会触发退出钩子，而那时实例里的配置文件可能是半成品
     * （真实案例：{@code tweakeroo.json} 只剩一个换行符 0x0A）。导出是「整份覆盖样本」，
     * 一旦写下去，样本库就废了，之后所有实例的键位注入都会静默失效。所以宁可少写一次。
     *
     * @param relativePath 实例内的相对路径（用来判断文件类型）
     * @param keybindFile  这一项是不是键位文件（键位文件才检查 {@code "keys"} 行数，
     *                     否则会误伤 {@code recipe_type_names.json} 这类没有 keys 的正常文件）
     */
    static String rejectReason(String relativePath, boolean keybindFile, String content) {
        if (content == null || content.isBlank()) {
            return "内容为空或只有空白";
        }
        String lower = relativePath == null ? "" : relativePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
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

    /** 花括号 / 方括号 / 引号是否配平（跳过字符串内部与转义字符）。 */
    static boolean balanced(String text) {
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

    /** 样本「源」是不是可以写回的真实文件路径（{@code preset:} 是 jar 内置只读预设，写不回去）。 */
    static boolean isWritableSource(String spec) {
        return spec != null && !spec.isBlank() && !spec.toLowerCase(Locale.ROOT).startsWith(PRESET_PREFIX);
    }

    /**
     * 写样本前先备份旧内容；内容一致时完全不碰文件。
     *
     * @return 是否真的写了
     */
    static boolean backupAndWrite(Path destination, String content) throws IOException {
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.isRegularFile(destination)) {
            String existing = Files.readString(destination, StandardCharsets.UTF_8);
            if (existing.equals(content)) {
                return false;
            }
            Path backupDir = parent == null
                    ? destination.resolveSibling(BACKUP_DIR)
                    : parent.resolve(BACKUP_DIR).resolve(LocalDateTime.now().format(STAMP));
            Files.createDirectories(backupDir);
            Files.copy(destination, backupDir.resolve(destination.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Files.writeString(destination, content, StandardCharsets.UTF_8);
        return true;
    }

    private static String nameOf(String spec) {
        try {
            return Path.of(spec).getFileName().toString();
        } catch (RuntimeException ex) {
            return spec;
        }
    }
}