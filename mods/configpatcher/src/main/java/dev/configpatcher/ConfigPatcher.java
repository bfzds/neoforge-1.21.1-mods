package dev.configpatcher;

import dev.configpatcher.command.ConfigPatcherCommand;
import dev.configpatcher.engine.PatchEngine;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Config Patcher —— 按规则条件式改写其它 mod 的配置。
 *
 * <p>核心行为只有三条：
 * <ol>
 *   <li>目标 mod 存在 → 在它的配置加载完成时，按 rules.json 改写指定条目；</li>
 *   <li>目标 mod 不存在 → 什么都不做，只在日志里说明“已跳过”；</li>
 *   <li>目标 mod 改了配置项 → 别名兜底 + 相似路径提示 + dumb/draft 工具，让规则能快速修好。</li>
 * </ol>
 */
@Mod(ConfigPatcher.MOD_ID)
public final class ConfigPatcher {

    public static final String MOD_ID = "configpatcher";

    public ConfigPatcher(IEventBus modEventBus, ModContainer modContainer) {
        // 1) 先准备好规则文件并加载规则。
        //    这一步必须在任何配置加载之前完成，否则目标 mod 的 ModConfigEvent 会错过。
        PatchEngine.bootstrap();

        // 2) 目标 mod 的配置一加载完成，立刻改写它内存里的值（Loading 是首次加载，Reloading 是热重载）。
        modEventBus.addListener((ModConfigEvent.Loading event) -> PatchEngine.onConfigEvent(event));
        modEventBus.addListener((ModConfigEvent.Reloading event) -> PatchEngine.onConfigEvent(event));

        // 3) mod 加载阶段结束后再打一次预检，此时 ModList 一定已经就绪。
        modEventBus.addListener(ConfigPatcher::onCommonSetup);

        // 4) 游戏内排查命令，以及服务器启动时的兜底重放（覆盖 SERVER 类型配置）。
        NeoForge.EVENT_BUS.addListener(ConfigPatcherCommand::register);
        NeoForge.EVENT_BUS.addListener(ConfigPatcher::onServerStarted);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(PatchEngine::summarize);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        PatchEngine.applyAll();
    }
}
