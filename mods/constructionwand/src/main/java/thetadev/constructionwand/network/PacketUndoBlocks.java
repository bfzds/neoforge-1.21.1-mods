package thetadev.constructionwand.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import thetadev.constructionwand.ConstructionWand;

import java.util.HashSet;
import java.util.Set;

public record PacketUndoBlocks(Set<BlockPos> undoBlocks) implements CustomPacketPayload
{
    public static final Type<PacketUndoBlocks> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ConstructionWand.MODID, "undo_blocks"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PacketUndoBlocks> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC.apply(ByteBufCodecs.collection(HashSet::new)), PacketUndoBlocks::undoBlocks, PacketUndoBlocks::new);

    public PacketUndoBlocks {
        undoBlocks = new HashSet<>(undoBlocks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final PacketUndoBlocks msg, final IPayloadContext ctx) {
        if(ConstructionWand.instance.renderBlockPreview == null) return;
        //ConstructionWand.LOGGER.debug("PacketUndoBlocks received, Blocks: " + msg.undoBlocks.size());
        ConstructionWand.instance.renderBlockPreview.undoBlocks = msg.undoBlocks;
    }
}


