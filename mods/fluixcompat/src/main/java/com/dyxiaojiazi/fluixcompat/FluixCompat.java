package com.dyxiaojiazi.fluixcompat;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(FluixCompat.MODID)
public final class FluixCompat {
    public static final String MODID = "fluixcompat";
    private static final Logger LOGGER = LoggerFactory.getLogger("Fluix Compatibility");

    public FluixCompat() {
        LOGGER.info("Loaded AE2 addon crystal ingredient compatibility rules.");
    }
}
