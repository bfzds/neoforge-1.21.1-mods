package com.dyxiaojiazi.blockdetector;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(BlockDetector.MODID)
public final class BlockDetector {
    public static final String MODID = "blockdetector";
    public static final Logger LOGGER = LoggerFactory.getLogger("Block Detector");

    public BlockDetector(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, BlockDetectorClientConfig.SPEC);
        LOGGER.info("Loaded Block Detector Highlighter.");
    }
}
