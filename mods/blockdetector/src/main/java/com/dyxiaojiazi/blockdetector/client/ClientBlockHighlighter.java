package com.dyxiaojiazi.blockdetector.client;

import com.dyxiaojiazi.blockdetector.BlockDetector;
import com.dyxiaojiazi.blockdetector.BlockDetectorClientConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.Util;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@EventBusSubscriber(modid = BlockDetector.MODID, value = Dist.CLIENT)
public final class ClientBlockHighlighter {
    private static final int CHUNKS_PER_TICK = 2;
    private static final List<BlockPos> MATCHES = new ArrayList<>();
    private static TargetRules cachedRules = TargetRules.EMPTY;
    private static List<? extends String> cachedConfigEntries = List.of();
    private static boolean scanning = false;
    private static BlockPos scanCenter = BlockPos.ZERO;
    private static ScanJob scanJob = null;
    private static int ticksUntilScan = 0;

    private ClientBlockHighlighter() {
    }

    public static void forceRescan() {
        ticksUntilScan = 0;
        scanJob = null;
        cachedConfigEntries = List.of();
    }

    public static void startFromPlayer() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null) {
            return;
        }

        scanning = true;
        scanCenter = player.blockPosition();
        MATCHES.clear();
        forceRescan();
    }

    public static void stopScanning() {
        scanning = false;
        scanJob = null;
        MATCHES.clear();
    }

    public static boolean isScanning() {
        return scanning;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!BlockDetectorClientConfig.SPEC.isLoaded()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (!BlockDetectorClientConfig.ENABLED.get() || level == null || player == null) {
            stopScanning();
            return;
        }

        if (!scanning) {
            return;
        }

        if (scanJob == null) {
            if (ticksUntilScan > 0) {
                ticksUntilScan--;
                return;
            }
            scanJob = new ScanJob(scanCenter, BlockDetectorClientConfig.SCAN_RANGE.get());
            MATCHES.clear();
        }

        TargetRules rules = getTargetRules();
        if (rules.isEmpty()) {
            scanJob = null;
            return;
        }

        boolean finished = scanJob.tick(level, rules);
        if (finished) {
            scanJob = null;
            ticksUntilScan = BlockDetectorClientConfig.RESCAN_INTERVAL_TICKS.get();
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!BlockDetectorClientConfig.SPEC.isLoaded()) {
            return;
        }

        if (!BlockDetectorClientConfig.ENABLED.get()
                || MATCHES.isEmpty()
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        VertexConsumer consumer = Minecraft.getInstance().renderBuffers().bufferSource().getBuffer(RenderType.lines());

        boolean throughBlocks = BlockDetectorClientConfig.HIGHLIGHT_THROUGH_BLOCKS.get();
        if (throughBlocks) {
            RenderSystem.disableDepthTest();
        }

        double phase = (Util.getMillis() % 1000L) / 1000.0D * Math.PI * 2.0D;
        float pulse = (float) ((Math.sin(phase) + 1.0D) * 0.5D);
        float red = Math.max(BlockDetectorClientConfig.RED.get().floatValue(), 1.0F);
        float green = Math.max(BlockDetectorClientConfig.GREEN.get().floatValue(), 0.15F + 0.85F * pulse);
        float blue = Math.min(BlockDetectorClientConfig.BLUE.get().floatValue(), 0.25F);
        float alpha = Math.max(0.35F, BlockDetectorClientConfig.ALPHA.get().floatValue() * (0.45F + 0.55F * pulse));

        poseStack.pushPose();
        for (BlockPos pos : MATCHES) {
            AABB box = new AABB(pos).move(-camera.x, -camera.y, -camera.z);
            LevelRenderer.renderLineBox(poseStack, consumer, box.inflate(0.01D), red, green, blue, alpha);
            LevelRenderer.renderLineBox(poseStack, consumer, box.inflate(0.04D), 1.0F, 1.0F, 0.0F, alpha * 0.55F);
        }
        poseStack.popPose();

        Minecraft.getInstance().renderBuffers().bufferSource().endBatch(RenderType.lines());
        if (throughBlocks) {
            RenderSystem.enableDepthTest();
        }
    }

    private static TargetRules getTargetRules() {
        List<? extends String> entries = BlockDetectorClientConfig.TARGET_BLOCKS.get();
        if (entries.equals(cachedConfigEntries)) {
            return cachedRules;
        }

        cachedConfigEntries = List.copyOf(entries);
        cachedRules = TargetRules.parse(entries);
        return cachedRules;
    }

    private static final class ScanJob {
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;
        private final int minChunkX;
        private final int maxChunkX;
        private final int minChunkZ;
        private final int maxChunkZ;
        private int chunkX;
        private int chunkZ;

        private ScanJob(BlockPos center, int range) {
            this.minX = center.getX() - range;
            this.maxX = center.getX() + range;
            this.minY = center.getY() - range;
            this.maxY = center.getY() + range;
            this.minZ = center.getZ() - range;
            this.maxZ = center.getZ() + range;
            this.minChunkX = SectionPos.blockToSectionCoord(this.minX);
            this.maxChunkX = SectionPos.blockToSectionCoord(this.maxX);
            this.minChunkZ = SectionPos.blockToSectionCoord(this.minZ);
            this.maxChunkZ = SectionPos.blockToSectionCoord(this.maxZ);
            this.chunkX = this.minChunkX;
            this.chunkZ = this.minChunkZ;
        }

        private boolean tick(ClientLevel level, TargetRules rules) {
            int processedChunks = 0;
            int maxMatches = BlockDetectorClientConfig.MAX_HIGHLIGHTED_BLOCKS.get();
            while (processedChunks < CHUNKS_PER_TICK && this.chunkZ <= this.maxChunkZ && MATCHES.size() < maxMatches) {
                LevelChunk chunk = level.getChunkSource().getChunk(this.chunkX, this.chunkZ, ChunkStatus.FULL, false);
                if (chunk != null) {
                    scanChunk(level, chunk, rules, maxMatches);
                    processedChunks++;
                }
                advanceChunk();
            }

            return this.chunkZ > this.maxChunkZ || MATCHES.size() >= maxMatches;
        }

        private void scanChunk(ClientLevel level, LevelChunk chunk, TargetRules rules, int maxMatches) {
            ChunkPos chunkPos = chunk.getPos();
            int startX = Math.max(this.minX, chunkPos.getMinBlockX());
            int endX = Math.min(this.maxX, chunkPos.getMaxBlockX());
            int startZ = Math.max(this.minZ, chunkPos.getMinBlockZ());
            int endZ = Math.min(this.maxZ, chunkPos.getMaxBlockZ());
            int startY = Math.max(this.minY, level.getMinBuildHeight());
            int endY = Math.min(this.maxY, level.getMaxBuildHeight() - 1);
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            LevelChunkSection[] sections = chunk.getSections();

            for (int sectionIndex = 0; sectionIndex < sections.length && MATCHES.size() < maxMatches; sectionIndex++) {
                LevelChunkSection section = sections[sectionIndex];
                if (section.hasOnlyAir()) {
                    continue;
                }

                int sectionMinY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIndex));
                int sectionMaxY = sectionMinY + 15;
                int yStart = Math.max(startY, sectionMinY);
                int yEnd = Math.min(endY, sectionMaxY);
                if (yStart > yEnd) {
                    continue;
                }

                for (int y = yStart; y <= yEnd && MATCHES.size() < maxMatches; y++) {
                    for (int x = startX; x <= endX && MATCHES.size() < maxMatches; x++) {
                        for (int z = startZ; z <= endZ; z++) {
                            BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                            cursor.set(x, y, z);
                            if (rules.matches(level, cursor, state)) {
                                MATCHES.add(cursor.immutable());
                                if (MATCHES.size() >= maxMatches) {
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }

        private void advanceChunk() {
            this.chunkX++;
            if (this.chunkX > this.maxChunkX) {
                this.chunkX = this.minChunkX;
                this.chunkZ++;
            }
        }
    }

    private static final class TargetRules {
        private static final ResourceLocation AE2_CABLE_BUS = ResourceLocation.fromNamespaceAndPath("ae2", "cable_bus");
        private static final Direction[] PART_SIDES = {
                null,
                Direction.DOWN,
                Direction.UP,
                Direction.NORTH,
                Direction.SOUTH,
                Direction.WEST,
                Direction.EAST
        };
        private static final TargetRules EMPTY = new TargetRules(Set.of(), List.of(), Set.of());

        private final Set<Block> blocks;
        private final List<TagKey<Block>> tags;
        private final Set<ResourceLocation> partItemIds;

        private TargetRules(Set<Block> blocks, List<TagKey<Block>> tags, Set<ResourceLocation> partItemIds) {
            this.blocks = blocks;
            this.tags = tags;
            this.partItemIds = partItemIds;
        }

        private static TargetRules parse(List<? extends String> entries) {
            Set<Block> blocks = new HashSet<>();
            List<TagKey<Block>> tags = new ArrayList<>();
            Set<ResourceLocation> partItemIds = new HashSet<>();
            Registry<Block> blockRegistry = BuiltInRegistries.BLOCK;

            for (String rawEntry : entries) {
                String entry = rawEntry.trim();
                if (entry.isEmpty()) {
                    continue;
                }

                boolean tag = entry.startsWith("#");
                String id = tag ? entry.substring(1) : entry;
                try {
                    ResourceLocation location = ResourceLocation.parse(id);
                    if (tag) {
                        tags.add(TagKey.create(Registries.BLOCK, location));
                    } else {
                        blockRegistry.getOptional(location).ifPresent(blocks::add);
                        BuiltInRegistries.ITEM.getOptional(location).ifPresent(item -> partItemIds.add(location));
                    }
                } catch (RuntimeException exception) {
                    BlockDetector.LOGGER.warn("Ignoring invalid block detector target '{}'.", rawEntry);
                }
            }

            return blocks.isEmpty() && tags.isEmpty() && partItemIds.isEmpty()
                    ? EMPTY
                    : new TargetRules(blocks, List.copyOf(tags), Set.copyOf(partItemIds));
        }

        private boolean matches(BlockGetter level, BlockPos pos, BlockState state) {
            if (blocks.contains(state.getBlock())) {
                return true;
            }

            for (TagKey<Block> tag : tags) {
                if (state.is(tag)) {
                    return true;
                }
            }
            return matchesPartItem(level, pos, state);
        }

        private boolean isEmpty() {
            return blocks.isEmpty() && tags.isEmpty() && partItemIds.isEmpty();
        }

        private boolean matchesPartItem(BlockGetter level, BlockPos pos, BlockState state) {
            if (partItemIds.isEmpty()) {
                return false;
            }

            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (!AE2_CABLE_BUS.equals(blockId)) {
                return false;
            }

            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                return false;
            }

            for (Direction side : PART_SIDES) {
                Object part = getAe2Part(blockEntity, side);
                if (part != null && partItemIds.contains(getAe2PartItemId(part))) {
                    return true;
                }
            }
            return false;
        }

        private static Object getAe2Part(BlockEntity blockEntity, Direction side) {
            try {
                return blockEntity.getClass().getMethod("getPart", Direction.class).invoke(blockEntity, side);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }

        private static ResourceLocation getAe2PartItemId(Object part) {
            try {
                Object partItem = part.getClass().getMethod("getPartItem").invoke(part);
                Item item = partItem instanceof Item directItem
                        ? directItem
                        : (Item) partItem.getClass().getMethod("asItem").invoke(partItem);
                return BuiltInRegistries.ITEM.getKey(item);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
    }
}
