package thetadev.constructionwand.items;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tiers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import thetadev.constructionwand.ConstructionWand;
import thetadev.constructionwand.basics.option.WandOptions;
import thetadev.constructionwand.crafting.RecipeWandUpgrade;
import thetadev.constructionwand.items.core.ItemCoreAngel;
import thetadev.constructionwand.items.core.ItemCoreDestruction;
import thetadev.constructionwand.items.wand.ItemWand;
import thetadev.constructionwand.items.wand.ItemWandBasic;
import thetadev.constructionwand.items.wand.ItemWandInfinity;

public class ModItems
{
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ConstructionWand.MODID);
    public static final DeferredRegister<net.minecraft.world.item.crafting.RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, ConstructionWand.MODID);

    // Wands
    public static final DeferredItem<ItemWandBasic> WAND_STONE = ITEMS.register("stone_wand", () -> new ItemWandBasic(propWand(), Tiers.STONE));
    public static final DeferredItem<ItemWandBasic> WAND_IRON = ITEMS.register("iron_wand", () -> new ItemWandBasic(propWand(), Tiers.IRON));
    public static final DeferredItem<ItemWandBasic> WAND_DIAMOND = ITEMS.register("diamond_wand", () -> new ItemWandBasic(propWand(), Tiers.DIAMOND));
    public static final DeferredItem<ItemWandInfinity> WAND_INFINITY = ITEMS.register("infinity_wand", () -> new ItemWandInfinity(propWand()));

    // Cores
    public static final DeferredItem<ItemCoreAngel> CORE_ANGEL = ITEMS.register("core_angel", () -> new ItemCoreAngel(propUpgrade()));
    public static final DeferredItem<ItemCoreDestruction> CORE_DESTRUCTION = ITEMS.register("core_destruction", () -> new ItemCoreDestruction(propUpgrade()));

    // Collections
    public static final DeferredItem<? extends Item>[] WANDS = new DeferredItem[] {WAND_STONE, WAND_IRON, WAND_DIAMOND, WAND_INFINITY};
    public static final DeferredItem<? extends Item>[] CORES = new DeferredItem[] {CORE_ANGEL, CORE_DESTRUCTION};

    public static final DeferredHolder<net.minecraft.world.item.crafting.RecipeSerializer<?>, net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer<RecipeWandUpgrade>>
            WAND_UPGRADE_SERIALIZER = RECIPE_SERIALIZERS.register("wand_upgrade", () -> RecipeWandUpgrade.SERIALIZER);

    public static Item.Properties propWand() {
        return new Item.Properties();
    }

    private static Item.Properties propUpgrade() {
        return new Item.Properties().stacksTo(1);
    }

    public static void registerRecipeSerializers(net.neoforged.neoforge.registries.RegisterEvent event) {
        // Recipes are registered through RECIPE_SERIALIZERS; this listener keeps the mod constructor wiring stable.
    }

    @OnlyIn(Dist.CLIENT)
    public static void registerModelProperties() {
        for(DeferredItem<? extends Item> itemSupplier : WANDS) {
            Item item = itemSupplier.get();
            ItemProperties.register(
                    item, ConstructionWand.loc("using_core"),
                    (stack, world, entity, n) -> entity == null || !(stack.getItem() instanceof ItemWand) ? 0 :
                            new WandOptions(stack).cores.get().getColor() > -1 ? 1 : 0
            );
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static void registerItemColors(RegisterColorHandlersEvent.Item event) {
        for(DeferredItem<? extends Item> itemSupplier : WANDS) {
            Item item = itemSupplier.get();
            event.register((stack, layer) -> (layer == 1 && stack.getItem() instanceof ItemWand) ?
                    new WandOptions(stack).cores.get().getColor() : -1, item);
        }
    }

    public static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            for(DeferredItem<? extends Item> itemSupplier : WANDS) {
                event.accept(itemSupplier);
            }
        } else if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            for(DeferredItem<? extends Item> itemSupplier : CORES) {
                event.accept(itemSupplier);
            }
        }
    }
}


