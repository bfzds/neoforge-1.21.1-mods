package com.dyxiaojiazi.floatingexcavation;

import com.dyxiaojiazi.floatingexcavation.event.ModEvents;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Floating Excavation —— 浮空挖掘。
 *
 * <p>移除「完全浸没在水中」与「悬空」时的挖掘速度惩罚：
 * 原版会对这两种情况分别打折，本 mod 在 {@code PlayerEvent.BreakSpeed} 里把折扣还原回去。
 *
 * <p>具体的判定与还原逻辑在 {@link ModEvents#onBreakSpeed}，这里只负责注册事件。
 */
@Mod(FloatingExcavation.MODID)
public final class FloatingExcavation {

    public static final String MODID = "floatingexcavation";
    public static final String MODNAME = "Floating Excavation";

    public static final Logger LOGGER = LoggerFactory.getLogger(MODNAME);

    public FloatingExcavation(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.addListener(ModEvents::onBreakSpeed);
    }
}
