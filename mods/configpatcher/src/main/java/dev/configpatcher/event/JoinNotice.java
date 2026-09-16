package dev.configpatcher.event;

import dev.configpatcher.ConfigPatcher;
import dev.configpatcher.engine.FailureLedger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

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

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof FakePlayer || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        List<FailureLedger.Failure> failures = FailureLedger.failures();
        if (failures.isEmpty()) {
            return;
        }
        if (!player.hasPermissions(2)) {
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
