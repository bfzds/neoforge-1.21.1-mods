package com.dyxiaojiazi.blockdetector.client;

import com.dyxiaojiazi.blockdetector.BlockDetector;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public final class ClientKeyBindings {
    public static final String CATEGORY = "key.categories.blockdetector";
    public static final KeyMapping OPEN_CONFIG = new KeyMapping(
            "key.blockdetector.open_config",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            CATEGORY
    );

    private ClientKeyBindings() {
    }

    @SubscribeEvent
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_CONFIG);
    }

    @EventBusSubscriber(modid = BlockDetector.MODID, value = Dist.CLIENT)
    public static final class Handler {
        private Handler() {
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft minecraft = Minecraft.getInstance();
            while (OPEN_CONFIG.consumeClick()) {
                if (minecraft.screen == null) {
                    minecraft.setScreen(new BlockDetectorConfigScreen(null));
                }
            }
        }
    }
}
