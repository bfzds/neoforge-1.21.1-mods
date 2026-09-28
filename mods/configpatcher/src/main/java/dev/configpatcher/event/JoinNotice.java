package dev.configpatcher.event;

import dev.configpatcher.ConfigPatcher;
import dev.configpatcher.agent.SessionMarker;
import dev.configpatcher.agent.SessionReport;
import dev.configpatcher.engine.FailureLedger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 玩家进入游戏时，把“没改成功的配置项”说清楚。
 *
 * <p>只发给有 OP 权限的玩家（单人游戏里本地玩家就是 OP），避免在服务器上刷普通玩家的屏幕。
 * 内容与 {@code /configpatcher failures} 一致，每行说明“哪个 mod 的哪个选项、为什么没改成功”。
 */
@EventBusSubscriber(modid = ConfigPatcher.MOD_ID)
public final class JoinNotice {

    private JoinNotice() {
    }

    /** 每次会话只显示一次注入摘要。 */
    private static boolean sessionReportShown = false;

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof FakePlayer || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!player.hasPermissions(2)) {
            return;
        }
        showSessionReport(player);
        showFailures(player);
    }

    /**
     * 把 Agent 写的会话报告（session-report.txt）显示到聊天栏：一眼看到本次启动注入了什么。
     * 三个门槛：本次会话真的启动成功过；报告属于本次启动（bootId 与 session.ok 一致）；每个会话只显示一次。
     */
    private static void showSessionReport(ServerPlayer player) {
        if (sessionReportShown) {
            return;
        }
        Path gameDir = FMLPaths.GAMEDIR.get();
        if (!SessionMarker.startedThisSession(gameDir)
                || !Files.isRegularFile(gameDir.resolve(SessionReport.RELATIVE))) {
            return;
        }
        String reportBootId = null;
        List<String> chat = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(gameDir.resolve(SessionReport.RELATIVE), StandardCharsets.UTF_8)) {
                if (line.startsWith("bootId=")) {
                    reportBootId = line.substring("bootId=".length()).trim();
                } else if (line.startsWith("chat=")) {
                    chat.add(line.substring("chat=".length()));
                }
            }
        } catch (IOException ex) {
            return;
        }
        if (chat.isEmpty() || reportBootId == null || reportBootId.isEmpty()
                || !reportBootId.equals(okBootId(gameDir))) {
            return; // 报告缺失或属于旧会话，不显示
        }
        sessionReportShown = true;
        player.sendSystemMessage(Component.literal("[Config Patcher] 本次启动注入摘要：")
                .withStyle(ChatFormatting.AQUA));
        for (String line : chat) {
            ChatFormatting style = line.startsWith("⚠") ? ChatFormatting.YELLOW
                    : line.startsWith("✔") ? ChatFormatting.GREEN : ChatFormatting.GRAY;
            player.sendSystemMessage(Component.literal("  " + line).withStyle(style));
        }
    }

    private static String okBootId(Path gameDir) {
        try {
            Path ok = gameDir.resolve(SessionMarker.OK_RELATIVE);
            return Files.isRegularFile(ok) ? Files.readString(ok, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException ex) {
            return "";
        }
    }

    private static void showFailures(ServerPlayer player) {
        List<FailureLedger.Failure> failures = FailureLedger.failures();
        if (failures.isEmpty()) {
            return;
        }

        player.sendSystemMessage(Component
                .literal("[Config Patcher] 以下配置项没有改成功，已跳过不再重试：")
                .withStyle(ChatFormatting.YELLOW));
        for (FailureLedger.Failure failure : failures) {
            player.sendSystemMessage(Component
                    .literal("  • " + failure.modId() + " 的 " + failure.path() + " —— " + failure.reason())
                    .withStyle(ChatFormatting.GRAY));
        }
        player.sendSystemMessage(Component
                .literal("  用 /configpatcher check 看详情，/configpatcher dump <modid> 导出该 mod 的实际配置项，"
                        + "/configpatcher failures clear 清空这份提示。")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
