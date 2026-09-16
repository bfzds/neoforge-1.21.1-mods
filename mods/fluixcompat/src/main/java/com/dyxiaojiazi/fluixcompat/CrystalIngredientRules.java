package com.dyxiaojiazi.fluixcompat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class CrystalIngredientRules {
    private static final Map<Item, List<Item>> EXTRAS_BY_ITEM = new IdentityHashMap<>();
    private static boolean initialized = false;

    private CrystalIngredientRules() {
    }

    public static List<Item> getExtras(Item item) {
        initializeIfNeeded();
        return EXTRAS_BY_ITEM.getOrDefault(item, List.of());
    }

    public static boolean hasAnyRules() {
        initializeIfNeeded();
        return !EXTRAS_BY_ITEM.isEmpty();
    }

    private static void initializeIfNeeded() {
        if (initialized) {
            return;
        }
        initialized = true;

        addGroup(
                "ae2:certus_quartz_crystal",
                "ae2:charged_certus_quartz_crystal",
                "ae2cs:purified_certus_quartz_crystal"
        );
        addGroup(
                "ae2:fluix_crystal",
                "ae2cs:purified_fluix_crystal"
        );
        addGroup(
                "minecraft:quartz",
                "ae2cs:purified_nether_quartz_crystal"
        );
        addGroup(
                "extendedae:entro_crystal",
                "ae2cs:purified_entro_crystal"
        );
        addGroup(
                "appflux:redstone_crystal",
                "ae2cs:purified_redstone_crystal"
        );
        addGroup(
                "advanced_ae:quantum_alloy",
                "ae2cs:purified_quantum_crystal"
        );
        addGroup(
                "create:rose_quartz",
                "ae2cs:purified_rose_quartz"
        );
        addGroup(
                "ae2cs:ender_quartz",
                "ae2cs:purified_ender_quartz"
        );
        addGroup(
                "ae2cs:meteor_crystal",
                "ae2cs:purified_meteor_crystal"
        );
        addGroup(
                "ae2cs:resonating_crystal",
                "ae2cs:purified_resonating_crystal"
        );
        addGroup(
                "ae2cs:irradiated_crystal",
                "ae2cs:purified_irradiated_crystal"
        );
        addGroup(
                "appgen:ember_crystal",
                "ae2cs:purified_ember_crystal"
        );
    }

    private static void addGroup(String... ids) {
        List<Item> presentItems = new ArrayList<>(ids.length);
        for (String id : ids) {
            Item item = getItem(id);
            if (item != Items.AIR) {
                presentItems.add(item);
            }
        }
        if (presentItems.size() < 2) {
            return;
        }

        for (Item item : presentItems) {
            List<Item> extras = EXTRAS_BY_ITEM.computeIfAbsent(item, ignored -> new ArrayList<>());
            for (Item extra : presentItems) {
                if (extra != item && !containsIdentity(extras, extra)) {
                    extras.add(extra);
                }
            }
        }
    }

    private static Item getItem(String id) {
        ResourceLocation location = ResourceLocation.parse(id);
        return BuiltInRegistries.ITEM.get(location);
    }

    private static boolean containsIdentity(List<Item> items, Item item) {
        for (Item existing : items) {
            if (existing == item) {
                return true;
            }
        }
        return false;
    }
}
