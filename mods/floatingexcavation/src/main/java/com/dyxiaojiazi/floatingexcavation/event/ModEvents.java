package com.dyxiaojiazi.floatingexcavation.event;

import com.dyxiaojiazi.floatingexcavation.FloatingExcavation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

public final class ModEvents {
    private static final float AIRBORNE_MINING_MULTIPLIER = 0.2F;

    private ModEvents() {
    }

    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        boolean isSubmergedInWater = player.isEyeInFluidType(NeoForgeMod.WATER_TYPE.value());
        boolean isAirborne = !player.onGround();

        float penaltyMultiplier = 1.0F;
        if (isSubmergedInWater) {
            penaltyMultiplier *= getSubmergedMiningPenalty(player);
        }

        if (isAirborne) {
            penaltyMultiplier *= AIRBORNE_MINING_MULTIPLIER;
        }

        if (penaltyMultiplier <= 0.0F || penaltyMultiplier >= 1.0F) {
            return;
        }

        float restoredSpeed = event.getOriginalSpeed() / penaltyMultiplier;
        if (restoredSpeed > event.getNewSpeed()) {
            event.setNewSpeed(restoredSpeed);
            logDebug(player, event, penaltyMultiplier, restoredSpeed, isSubmergedInWater, isAirborne);
        }
    }

    private static float getSubmergedMiningPenalty(Player player) {
        double submergedMiningSpeed = player.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
        if (submergedMiningSpeed >= 1.0D) {
            return 1.0F;
        }

        return (float) submergedMiningSpeed;
    }

    private static void logDebug(
            Player player,
            PlayerEvent.BreakSpeed event,
            float penaltyMultiplier,
            float restoredSpeed,
            boolean isSubmergedInWater,
            boolean isAirborne
    ) {
        if (!Boolean.getBoolean("floatingexcavation.debug")) {
            return;
        }

        FloatingExcavation.LOGGER.info(
                "Adjusted mining speed for {} at {}: submerged={}, airborne={}, speed={} -> {}, penaltyMultiplier={}",
                player.getName().getString(),
                event.getPosition().map(Object::toString).orElse("unknown"),
                isSubmergedInWater,
                isAirborne,
                event.getOriginalSpeed(),
                restoredSpeed,
                penaltyMultiplier
        );
    }
}
