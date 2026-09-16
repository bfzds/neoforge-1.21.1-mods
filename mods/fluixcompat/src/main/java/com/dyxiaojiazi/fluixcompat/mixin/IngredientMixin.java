package com.dyxiaojiazi.fluixcompat.mixin;

import com.dyxiaojiazi.fluixcompat.CrystalIngredientRules;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.List;

@Mixin(Ingredient.class)
public abstract class IngredientMixin {
    @Shadow
    @Nullable
    private ItemStack[] itemStacks;

    @Shadow
    @Nullable
    private IntList stackingIds;

    @Unique
    private boolean fluixcompat$expandedOnce = false;

    @Inject(method = "getItems", at = @At("RETURN"), cancellable = true)
    private void fluixcompat$expandCrystalItems(CallbackInfoReturnable<ItemStack[]> cir) {
        if (fluixcompat$expandedOnce) {
            return;
        }
        fluixcompat$expandedOnce = true;

        if (!CrystalIngredientRules.hasAnyRules()) {
            return;
        }

        ItemStack[] originalItems = cir.getReturnValue();
        if (originalItems == null || originalItems.length == 0) {
            return;
        }

        Item[] addedItems = null;
        int addedCount = 0;

        for (ItemStack stack : originalItems) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }

            List<Item> extras = CrystalIngredientRules.getExtras(stack.getItem());
            for (Item extra : extras) {
                if (fluixcompat$containsItem(originalItems, extra)
                        || fluixcompat$containsItem(addedItems, addedCount, extra)) {
                    continue;
                }

                if (addedItems == null) {
                    addedItems = new Item[4];
                } else if (addedCount == addedItems.length) {
                    addedItems = Arrays.copyOf(addedItems, addedCount * 2);
                }
                addedItems[addedCount++] = extra;
            }
        }

        if (addedCount == 0) {
            return;
        }

        ItemStack[] expandedItems = Arrays.copyOf(originalItems, originalItems.length + addedCount);
        for (int i = 0; i < addedCount; i++) {
            expandedItems[originalItems.length + i] = new ItemStack(addedItems[i]);
        }

        this.itemStacks = expandedItems;
        this.stackingIds = null;
        cir.setReturnValue(expandedItems);
    }

    @Unique
    private static boolean fluixcompat$containsItem(ItemStack[] stacks, Item item) {
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty() && stack.getItem() == item) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static boolean fluixcompat$containsItem(@Nullable Item[] items, int size, Item item) {
        if (items == null) {
            return false;
        }
        for (int i = 0; i < size; i++) {
            if (items[i] == item) {
                return true;
            }
        }
        return false;
    }
}
