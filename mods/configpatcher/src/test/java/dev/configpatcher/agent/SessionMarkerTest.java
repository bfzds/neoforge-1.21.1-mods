package dev.configpatcher.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「本次会话有没有成功启动」的握手标记。
 *
 * <p>这组用例守的是 2026-09-24 那次事故：缺前置导致 mod 加载失败、游戏崩溃，
 * 退出钩子却照样把半成品配置回写成样本，把样本库的 tweakeroo 键位从 17 条带成 1 个字节。
 */
class SessionMarkerTest {

    @Test
    void startedSessionIsRecognised(@TempDir Path gameDir) {
        SessionMarker.beginSession(gameDir);
        assertFalse(SessionMarker.startedThisSession(gameDir), "mod 侧还没认账时不该放行");

        assertTrue(SessionMarker.markStarted(gameDir), "有 pending 时应该能落凭据");
        assertTrue(SessionMarker.startedThisSession(gameDir), "编号一致就该放行回写");
    }

    @Test
    void crashBeforeCommonSetupKeepsItLocked(@TempDir Path gameDir) {
        // Agent 写了 pending；游戏崩在 mod 加载阶段，mod 侧永远没机会写 ok
        SessionMarker.beginSession(gameDir);
        assertFalse(SessionMarker.startedThisSession(gameDir), "启动失败时必须拦住回写");
    }

    @Test
    void staleOkFromPreviousBootDoesNotCount(@TempDir Path gameDir) {
        // 上一轮成功启动留下的 ok
        SessionMarker.beginSession(gameDir);
        SessionMarker.markStarted(gameDir);
        assertTrue(SessionMarker.startedThisSession(gameDir));

        // 本轮启动：beginSession 必须先把它作废，否则一次成功启动会永久放行
        SessionMarker.beginSession(gameDir);
        assertFalse(SessionMarker.startedThisSession(gameDir), "上一轮的凭据必须失效");
    }

    @Test
    void mismatchedBootIdDoesNotCount(@TempDir Path gameDir) throws IOException {
        SessionMarker.beginSession(gameDir);
        Files.writeString(gameDir.resolve(SessionMarker.OK_RELATIVE), "别的编号", StandardCharsets.UTF_8);
        assertFalse(SessionMarker.startedThisSession(gameDir));
    }

    @Test
    void markStartedWithoutPendingIsFalse(@TempDir Path gameDir) {
        assertFalse(SessionMarker.markStarted(gameDir), "没有 pending 时不该凭空造凭据");
    }

    @Test
    void bootIdsAreUnique() {
        assertFalse(SessionMarker.newBootId().equals(SessionMarker.newBootId()));
    }
}
