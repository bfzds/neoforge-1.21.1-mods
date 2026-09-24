package dev.configpatcher.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 「本次会话到底有没有成功启动」的握手标记。
 *
 * <p>为什么需要它：Agent 的「关闭游戏自动导出」注册的是 JVM 退出钩子，**游戏崩溃、mod 加载失败
 * 也会触发**。那种时候实例里的配置文件往往是半成品（例如只剩一个换行符），而导出是「整份覆盖样本」，
 * 一写就会把样本库带坏 —— 样本一旦变空，后续所有实例的键位注入都会静默失效。
 *
 * <p>握手流程（两个文件都在 {@code <实例目录>/config/configpatcher/} 下）：
 * <ol>
 *     <li><b>Agent（premain）</b>：删掉旧的 {@code session.ok}，把本次启动编号写进 {@code session.pending}；</li>
 *     <li><b>mod（FMLCommonSetupEvent）</b>：游戏真的走到「mod 加载完成」阶段，才把编号抄进 {@code session.ok}；</li>
 *     <li><b>Agent（退出钩子）</b>：两个文件的编号一致才允许回写样本，否则跳过。</li>
 * </ol>
 *
 * <p>mod 加载失败时第 2 步根本不会发生，编号对不上 → 不回写。这正是需要挡住的情形。
 *
 * <p>只使用 JDK 自带能力，因此可以在 Agent 阶段（FML 之前）安全使用。
 */
public final class SessionMarker {

    public static final String RELATIVE_DIR = "config/configpatcher";
    public static final String PENDING_RELATIVE = RELATIVE_DIR + "/session.pending";
    public static final String OK_RELATIVE = RELATIVE_DIR + "/session.ok";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private SessionMarker() {
    }

    /** 生成一次启动的唯一编号：时间戳 + 随机段，日志里能肉眼对照。 */
    public static String newBootId() {
        return LocalDateTime.now().format(STAMP) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Agent premain 调用：作废上一次的「启动成功」标记，并写下本次的编号。
     *
     * @return 本次启动编号（写盘失败时也返回，仅表示"这一轮的编号"）
     */
    public static String beginSession(Path gameDir) {
        String bootId = newBootId();
        if (gameDir == null) {
            return bootId;
        }
        try {
            Files.deleteIfExists(gameDir.resolve(OK_RELATIVE));
            Path pending = gameDir.resolve(PENDING_RELATIVE);
            Path parent = pending.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(pending, bootId, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 标记写不进去就当作「启动没成功」处理：宁可少回写样本，也不要写坏样本
        }
        return bootId;
    }

    /**
     * mod 侧调用：把 {@code session.pending} 里的编号抄进 {@code session.ok}，表示"本次真的起来了"。
     *
     * @return 是否成功落标记
     */
    public static boolean markStarted(Path gameDir) {
        if (gameDir == null) {
            return false;
        }
        try {
            String bootId = read(gameDir.resolve(PENDING_RELATIVE));
            if (bootId.isEmpty()) {
                return false;
            }
            Path ok = gameDir.resolve(OK_RELATIVE);
            Path parent = ok.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(ok, bootId, StandardCharsets.UTF_8);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    /** Agent 退出钩子调用：本次会话是否真的启动成功过（两个编号一致才算）。 */
    public static boolean startedThisSession(Path gameDir) {
        if (gameDir == null) {
            return false;
        }
        try {
            String pending = read(gameDir.resolve(PENDING_RELATIVE));
            String ok = read(gameDir.resolve(OK_RELATIVE));
            return !pending.isEmpty() && pending.equals(ok);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException ex) {
            return "";
        }
    }
}
