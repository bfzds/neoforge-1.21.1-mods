package thetadev.constructionwand.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModMessages {
    private ModMessages() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(PacketUndoBlocks.TYPE, PacketUndoBlocks.STREAM_CODEC, PacketUndoBlocks::handle);
        registrar.playToServer(PacketQueryUndo.TYPE, PacketQueryUndo.STREAM_CODEC, PacketQueryUndo::handle);
        registrar.playToServer(PacketWandOption.TYPE, PacketWandOption.STREAM_CODEC, PacketWandOption::handle);
    }

    public static void sendToServer(CustomPacketPayload message) {
        PacketDistributor.sendToServer(message);
    }

    public static void sendToPlayer(CustomPacketPayload message, ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, message);
    }
}


