package dev.configpatcher.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话注入报告：把 Agent 本次启动的注入结果落成文件，供 mod 侧在玩家进入世界时显示到聊天栏。
 *
 * <p>文件位置 {@code config/configpatcher/session-report.txt}，每次启动整体覆盖：
 * 第一行是 {@code bootId=}（本次启动编号），其余行是 {@code chat=} 前缀的聊天栏文本——
 * mod 侧只显示带这个前缀的行。报告属于哪次启动由 bootId 比对（与 {@code session.ok} 一致才显示），
 * 避免上一次崩溃残留的报告被当成这次的显示。
 *
 * <p>只用 JDK 能力，Agent 阶段可用。
 */
public final class SessionReport {

    public static final String RELATIVE = "config/configpatcher/session-report.txt";
    /** 聊天栏最多显示的行数（含汇总行）；超出的折叠进 agent.log，避免刷屏。 */
    static final int MAX_CHAT_LINES = 12;

    private SessionReport() {
    }

    /** 注入结束后调用；gameDir 为 null（连目录都没探测到）时跳过。任何异常都吞掉，绝不影响游戏启动。 */
    public static void write(Path gameDir, String bootId, List<AgentInjector.Action> actions) {
        if (gameDir == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("bootId=").append(bootId == null ? "" : bootId).append('\n');
            for (String line : chatLines(actions)) {
                sb.append("chat=").append(line).append('\n');
            }
            Path file = gameDir.resolve(RELATIVE);
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // 报告写不出来只影响聊天栏提示
        }
    }

    /**
     * 把注入动作渲染成聊天栏文本：一行汇总（变更/无变化/需注意计数）+ 每项一行。
     * {@code ✔} 表示真的改了东西，{@code ⚠} 表示失败/没找到，{@code •} 表示无变化（含按设计跳过）。
     * 超过 {@link #MAX_CHAT_LINES} 行时截断并提示看 agent.log。
     */
    static List<String> chatLines(List<AgentInjector.Action> actions) {
        List<AgentInjector.Action> list = actions == null ? List.of() : actions;
        List<String> result = new ArrayList<>();
        int done = 0;
        int note = 0;
        int unchanged = 0;
        for (AgentInjector.Action action : list) {
            if (action.changed()) {
                done++;
            } else if (isNoticeable(action.detail())) {
                note++;
            } else {
                unchanged++;
            }
        }
        StringBuilder summary = new StringBuilder("本次启动注入 ").append(list.size())
                .append(" 项：变更 ").append(done).append(" · 无变化 ").append(unchanged);
        if (note > 0) {
            summary.append(" · 需注意 ").append(note);
        }
        result.add(summary.toString());

        int bodyLimit = MAX_CHAT_LINES - 2; // 汇总占 1 行，超限提示占 1 行
        int shown = 0;
        for (AgentInjector.Action action : list) {
            if (shown >= bodyLimit) {
                result.add("……其余 " + (list.size() - shown) + " 项见 agent.log");
                return result;
            }
            result.add(glyph(action) + " " + action.detail());
            shown++;
        }
        return result;
    }

    private static String glyph(AgentInjector.Action action) {
        if (action.changed()) {
            return "✔";
        }
        return isNoticeable(action.detail()) ? "⚠" : "•";
    }

    private static boolean isNoticeable(String detail) {
        if (detail == null) {
            return false;
        }
        return detail.contains("失败") || detail.contains("未找到") || detail.contains("异常")
                || detail.contains("未生效");
    }
}
