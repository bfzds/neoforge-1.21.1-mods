package com.dyxiaojiazi.blockdetector;

import com.dyxiaojiazi.blockdetector.client.ClientKeyBindings;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(BlockDetector.MODID)
public final class BlockDetector {
    public static final String MODID = "blockdetector";
    public static final Logger LOGGER = LoggerFactory.getLogger("Block Detector");

    public BlockDetector(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, BlockDetectorClientConfig.SPEC);

        // 按键映射属于 mod 事件总线（RegisterKeyMappingsEvent），而 EventBusSubscriber 注解的 bus() 已被弃用，
        // 所以这里直接在构造器里注册。只在客户端做，避免专用服务器加载客户端类。
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(ClientKeyBindings::registerKeyMappings);
        }

        LOGGER.info("Loaded Block Detector Highlighter.");
    }
}
