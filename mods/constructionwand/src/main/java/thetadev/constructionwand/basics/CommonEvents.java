package thetadev.constructionwand.basics;

import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import thetadev.constructionwand.ConstructionWand;

public class CommonEvents
{
    public static void serverStarting(ServerStartingEvent e) {
        ReplacementRegistry.init();
    }

    public static void logOut(PlayerEvent.PlayerLoggedOutEvent e) {
        Player player = e.getEntity();
        if(player.level().isClientSide) return;
        ConstructionWand.instance.undoHistory.removePlayer(player);
    }
}


