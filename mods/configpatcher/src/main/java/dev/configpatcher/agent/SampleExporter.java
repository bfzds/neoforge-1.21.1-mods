package dev.configpatcher.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * “关闭游戏自动导出”的执行体：把实例里**当前生效**的配置回写成本地样本库的文件。
 *
 * <p>和 {@link AgentInjector} 正好反过来：注入是「样本 → 实例」，导出是「实例 → 样本」。
 * 两者合起来才是闭环：在任意实例里把键位调好 → 关游戏时自动沉淀成样本 → 其它实例下次启动照这套走。
 *
 * <h2>导出哪些</h2>
 * <ul>
 *     <li>{@code keybindSource} / {@code keybindTarget}：把实例 {@code options.txt} 里的键位与声音行
 *         按「只增改」并入键位/声音样本（分辨率、语言等无关设置不带进样本，实例没有的键位保留样本原值）；</li>
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
 *     <li>写样本前先把旧样本复制到 {@code <样本目录>/.backup/<时间戳>/}，导错了还能回滚
 *         （最多保留 20 份，写备份时自动清理更旧的）；</li>
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

    /** 键位/声音样本：把实例 options.txt 里的键位与声音行按「只增改」合并进全局样本（口径 A）。 */
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
        Path sample = Path.of(source);
        try {
            List<String> keyLines = new ArrayList<>();
            for (String line : Files.readAllLines(target, StandardCharsets.UTF_8)) {
                if (KeybindMerger.isCarriedLine(line.trim())) {
                    keyLines.add(line);
                }
            }
            if (keyLines.isEmpty()) {
                actions.add(new AgentInjector.Action("export", nameOf(source),
                        targetName + " 里没有键位/声音行，跳过", false));
                return;
            }
            // 崩溃 / 启动失败退出时实例的键位表可能是半成品，绝不能并进全局
            String instanceContent = String.join("\n", keyLines) + "\n";
            String reject = ConfigFileGuard.rejectReason(targetName, true, instanceContent);
            if (reject != null) {
                actions.add(new AgentInjector.Action("export", nameOf(source),
                        "内容异常（" + reject + "），已跳过回写，样本保持不变", false));
                return;
            }
            // 口径 A（只增改）：以全局为基准合并——实例没有的键位保留全局原值，全局永不缩减
            String global = Files.isRegularFile(sample)
                    ? Files.readString(sample, StandardCharsets.UTF_8)
                    : "";
            long before = countCarriedLines(global);
            String merged = KeybindMerger.mergeIntoGlobal(global, instanceContent);
            long after = countCarriedLines(merged);
            boolean written = backupAndWrite(sample, merged);
            actions.add(new AgentInjector.Action("export", nameOf(source),
                    written ? "已按键位/声音样本合并：全局 " + before + " → " + after + " 条"
                            : "与样本一致，无需回写", written));
        } catch (IOException ex) {
            actions.add(new AgentInjector.Action("export", nameOf(source), "回写失败：" + ex.getMessage(), false));
        }
    }

    private static long countCarriedLines(String content) {
        return content == null ? 0
                : content.lines().filter(line -> KeybindMerger.isCarriedLine(line.trim())).count();
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
        Path sample = Path.of(override.from());
        try {
            String content = Files.readString(target, StandardCharsets.UTF_8);
            String reject = ConfigFileGuard.rejectReason(override.to(), keybindFile, content);
            if (reject != null) {
                // 崩溃 / 启动失败退出时实例里可能只剩半成品，整份回写会把样本带坏 —— 直接跳过
                actions.add(new AgentInjector.Action("export", nameOf(override.from()),
                        "内容异常（" + reject + "），已跳过回写，样本保持不变", false));
                return;
            }
            // 键位 JSON（tweakeroo 这类）按口径 A「只增改」合并：以样本为基准，只更新实例里改过的 keys 值，
            // 样本里实例没有的条目一律保留 —— 键位内容有差异的实例退出不再把样本整份带偏。
            // 样本不存在 / 已损坏（canPatchInPlace 不通过）时才整份采用实例，和旧行为一致，作为兜底。
            String toWrite = content;
            String how = "整份";
            if (keybindFile && Files.isRegularFile(sample)) {
                String global = Files.readString(sample, StandardCharsets.UTF_8);
                if (JsonKeybindMerger.canPatchInPlace(global)) {
                    String merged = JsonKeybindMerger.merge(global, content);
                    String mergedReject = ConfigFileGuard.rejectMergedResult(override.to(), merged);
                    if (mergedReject != null) {
                        actions.add(new AgentInjector.Action("export", nameOf(override.from()),
                                "合并结果异常（" + mergedReject + "），已跳过回写，样本保持不变", false));
                        return;
                    }
                    toWrite = merged;
                    how = "合并";
                }
            }
            boolean written = backupAndWrite(sample, toWrite);
            actions.add(new AgentInjector.Action("export", nameOf(override.from()),
                    written ? "已回写（" + kind + "，" + how + "，源：" + override.to() + "）" : "与样本一致，无需回写", written));
        } catch (IOException ex) {
            actions.add(new AgentInjector.Action("export", nameOf(override.from()),
                    "回写失败：" + ex.getMessage(), false));
        }
    }

    // 内容健康检查（rejectReason / balanced / MIN_KEY_LINES）已抽到 ConfigFileGuard：
    // 导出方向（实例 → 样本）与注入方向（样本 → 实例）共用同一套规则。

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
            pruneBackups(backupDir.getParent());
        }
        Files.writeString(destination, content, StandardCharsets.UTF_8);
        return true;
    }

    /** .backup 下最多保留的份数；再写就删最旧的，防止长期使用堆积成几百个目录。 */
    static final int MAX_BACKUPS = 20;
    private static final Pattern BACKUP_DIR_NAME = Pattern.compile("\\d{8}-\\d{6}");

    /** 只保留最近 MAX_BACKUPS 份备份目录（按时间戳目录名倒序），多余的全部递归删除。 */
    static void pruneBackups(Path backupRoot) {
        if (backupRoot == null || !Files.isDirectory(backupRoot)) {
            return;
        }
        List<Path> dirs;
        try (var stream = Files.list(backupRoot)) {
            dirs = stream.filter(Files::isDirectory)
                    .filter(dir -> BACKUP_DIR_NAME.matcher(dir.getFileName().toString()).matches())
                    .sorted(Comparator.comparing((Path dir) -> dir.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException ex) {
            return; // 清理失败不影响本次导出
        }
        for (int i = MAX_BACKUPS; i < dirs.size(); i++) {
            deleteRecursively(dirs.get(i));
        }
    }

    private static void deleteRecursively(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // 删不掉就留给下次
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private static String nameOf(String spec) {
        try {
            return Path.of(spec).getFileName().toString();
        } catch (RuntimeException ex) {
            return spec;
        }
    }
}