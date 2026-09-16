package com.dyxiaojiazi.blockdetector;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class BlockDetectorClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue SCAN_RANGE;
    public static final ModConfigSpec.IntValue RESCAN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue MAX_HIGHLIGHTED_BLOCKS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TARGET_BLOCKS;
    public static final ModConfigSpec.BooleanValue HIGHLIGHT_THROUGH_BLOCKS;
    public static final ModConfigSpec.DoubleValue RED;
    public static final ModConfigSpec.DoubleValue GREEN;
    public static final ModConfigSpec.DoubleValue BLUE;
    public static final ModConfigSpec.DoubleValue ALPHA;

    public static final ModConfigSpec SPEC;

    static {
        BUILDER.push("block_detector");

        ENABLED = BUILDER
                .comment("Whether block detection and highlighting is enabled on this client.")
                .define("enabled", true);

        SCAN_RANGE = BUILDER
                .comment("Scan radius in blocks around the start position. Large values scan progressively and only cover loaded chunks.")
                .defineInRange("scan_range", 16, 1, 1024);

        RESCAN_INTERVAL_TICKS = BUILDER
                .comment("How often to rescan, in client ticks. 20 ticks is about one second.")
                .defineInRange("rescan_interval_ticks", 10, 1, 200);

        MAX_HIGHLIGHTED_BLOCKS = BUILDER
                .comment("Maximum matched blocks to keep highlighted at once.")
                .defineInRange("max_highlighted_blocks", 512, 1, 8192);

        TARGET_BLOCKS = BUILDER
                .comment(
                        "Block ids, block tags, or AE2 part item ids to highlight.",
                        "Examples: minecraft:diamond_ore, minecraft:deepslate_diamond_ore, #minecraft:logs, ae2:storage_bus, extendedae_plus:entity_speed_ticker"
                )
                .defineListAllowEmpty(
                        "target_blocks",
                        List.of(
                                "minecraft:diamond_ore",
                                "minecraft:deepslate_diamond_ore",
                                "minecraft:ancient_debris",
                                "extendedae_plus:entity_speed_ticker"
                        ),
                        () -> "minecraft:diamond_ore",
                        value -> value instanceof String
                );

        HIGHLIGHT_THROUGH_BLOCKS = BUILDER
                .comment("If true, highlight outlines are visible through other blocks.")
                .define("highlight_through_blocks", true);

        BUILDER.push("color");
        RED = BUILDER.defineInRange("red", 1.0D, 0.0D, 1.0D);
        GREEN = BUILDER.defineInRange("green", 0.18D, 0.0D, 1.0D);
        BLUE = BUILDER.defineInRange("blue", 0.0D, 0.0D, 1.0D);
        ALPHA = BUILDER.defineInRange("alpha", 1.0D, 0.0D, 1.0D);
        BUILDER.pop();

        BUILDER.pop();
        SPEC = BUILDER.build();
    }

    private BlockDetectorClientConfig() {
    }
}
