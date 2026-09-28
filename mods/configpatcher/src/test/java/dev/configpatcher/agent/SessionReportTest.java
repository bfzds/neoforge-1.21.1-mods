package dev.configpatcher.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话注入报告：聊天栏文本渲染与落盘格式。 */
class SessionReportTest {

    private static AgentInjector.Action action(String detail, boolean changed) {
        return new AgentInjector.Action("mod", "x", detail, changed);
    }

    @Test
    void normalRunRendersGlyphsAndSummary() {
        List<String> lines = SessionReport.chatLines(List.of(
                action("已注入 mods 目录", true),
                action("已存在且内容一致", false),
                action("config/jei/jei-client.ini:cheating.giveMode = INVENTORY", true),
                action("cheating.notExistKey —— 未找到该键", false)));

        assertEquals("本次启动注入 4 项：变更 2 · 无变化 1 · 需注意 1", lines.get(0));
        assertTrue(lines.get(1).startsWith("✔"), lines.get(1));
        assertTrue(lines.get(2).startsWith("•"), lines.get(2));
        assertTrue(lines.get(3).startsWith("✔"), lines.get(3));
        assertTrue(lines.get(4).startsWith("⚠"), lines.get(4));
    }

    @Test
    void longRunsAreFoldedIntoAgentLog() {
        List<AgentInjector.Action> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(action("动作" + i, true));
        }
        List<String> lines = SessionReport.chatLines(many);
        assertEquals(SessionReport.MAX_CHAT_LINES, lines.size());
        assertTrue(lines.get(lines.size() - 1).contains("agent.log"), lines.get(lines.size() - 1));
    }

    @Test
    void writeCreatesReportWithBootId(@TempDir Path gameDir) throws Exception {
        SessionReport.write(gameDir, "boot-1", List.of(action("已注入 mods 目录", true)));

        List<String> lines = Files.readAllLines(gameDir.resolve(SessionReport.RELATIVE), StandardCharsets.UTF_8);
        assertEquals("bootId=boot-1", lines.get(0));
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).startsWith("chat=本次启动注入"), lines.get(1));
        assertTrue(lines.get(2).startsWith("chat=✔"), lines.get(2));
    }

    @Test
    void nullGameDirIsIgnored() {
        // 不应抛异常
        SessionReport.write(null, "boot-1", List.of(action("x", true)));
    }
}
