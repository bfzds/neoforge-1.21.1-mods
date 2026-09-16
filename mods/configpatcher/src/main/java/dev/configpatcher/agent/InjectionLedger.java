package dev.configpatcher.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 注入账本：记录「本工具往这个实例里放过哪些 jar」。
 *
 * <p>之所以需要它，是因为注入类工具最危险的失败模式不是「没注入」，而是「注入过、后来整合包自己更新了，
 * 于是同一个 modId 出现两份」——那会让 FML 直接报重复 mod 并崩游戏。
 * 有了账本，下次启动就能：只删自己放过的那一份，绝不碰整合包自带的文件。
 *
 * <p>账本是人类可读的纯文本，一行一条，放在 {@code config/configpatcher/injected.log}：
 * <pre>
 * 文件名|modId|字节数|来源|注入时间
 * </pre>
 */
public final class InjectionLedger {

    public static final String RELATIVE = "config/configpatcher/injected.log";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String HEADER = "# Config Patcher 注入账本：由本工具自动维护，记录“我往 mods 里放过哪些文件”。"
            + "\n# 作用：整合包自带版本出现时，只删除这里记录过的副本，不碰你/整合包自己的文件。"
            + "\n# 删除某一行可以让本工具不再管理对应文件；整个文件删掉等于清空记录。";

    private InjectionLedger() {
    }

    /**
     * @param fileName 注入到 mods 目录后的文件名
     * @param modId    该 jar 的 modId（读不出来时为空串）
     * @param size     注入时的字节数（用来判断文件后来有没有被改动）
     * @param source   来源（绝对路径或 preset:xxx）
     * @param time     注入时间
     */
    public record Entry(String fileName, String modId, long size, String source, String time) {

        public static Entry of(String fileName, String modId, long size, String source) {
            return new Entry(fileName, modId == null ? "" : modId, size, source == null ? "" : source,
                    LocalDateTime.now().format(TIME));
        }

        public String toLine() {
            return fileName + "|" + modId + "|" + size + "|" + source + "|" + time;
        }
    }

    public static List<Entry> load(Path file) {
        List<Entry> entries = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return entries;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Entry entry = parse(line);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (IOException ignored) {
            // 读不出来就当空账本，不影响游戏启动
        }
        return entries;
    }

    public static void save(Path file, Collection<Entry> entries) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringBuilder sb = new StringBuilder(HEADER).append('\n');
            for (Entry entry : entries) {
                sb.append(entry.toLine()).append('\n');
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 写失败只影响下次的对账，不影响本次注入
        }
    }

    static Entry parse(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }
        String[] parts = trimmed.split("\\|", -1);
        if (parts.length < 3 || parts[0].isBlank()) {
            return null;
        }
        long size;
        try {
            size = Long.parseLong(parts[2].trim());
        } catch (NumberFormatException ex) {
            size = -1L;
        }
        return new Entry(parts[0].trim(), parts[1].trim(), size,
                parts.length > 3 ? parts[3] : "", parts.length > 4 ? parts[4] : "");
    }
}
