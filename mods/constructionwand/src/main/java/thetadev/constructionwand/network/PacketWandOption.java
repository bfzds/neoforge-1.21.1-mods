package thetadev.constructionwand.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import thetadev.constructionwand.ConstructionWand;
import thetadev.constructionwand.basics.WandUtil;
import thetadev.constructionwand.basics.option.IOption;
import thetadev.constructionwand.basics.option.WandOptions;
import thetadev.constructionwand.items.wand.ItemWand;

public record PacketWandOption(String key, String value, boolean shouldNotify) implements CustomPacketPayload
{
    public static final Type<PacketWandOption> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(ConstructionWand.MODID, "wand_option"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PacketWandOption> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(100), PacketWandOption::key,
                    ByteBufCodecs.stringUtf8(100), PacketWandOption::value,
                    ByteBufCodecs.BOOL, PacketWandOption::shouldNotify,
                    PacketWandOption::new
            );

    public PacketWandOption(IOption<?> option, boolean notify) {
        this(option.getKey(), option.getValueString(), notify);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(final PacketWandOption msg, final IPayloadContext ctx) {
        if(!(ctx.player() instanceof ServerPlayer player)) return;

        ItemStack wand = WandUtil.holdingWand(player);
        if(wand == null) return;
        WandOptions options = new WandOptions(wand);

        IOption<?> option = options.get(msg.key);
        if(option == null) return;
        option.setValueString(msg.value);
        options.writeToStack();

        if(msg.shouldNotify) ItemWand.optionMessage(player, option);
        player.getInventory().setChanged();
    }
}


