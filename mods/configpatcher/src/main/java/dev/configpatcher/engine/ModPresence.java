package dev.configpatcher.engine;

import net.neoforged.fml.ModList;

/** 判断目标 mod 是否存在、拿到版本号。所有调用都做了兜底，任何异常都当作“不存在/未知”。 */
public final class ModPresence {

    private ModPresence() {
    }

    public static boolean isLoaded(String modId) {
        if (modId == null || modId.isBlank()) {
            return false;
        }
        try {
            ModList list = ModList.get();
            return list != null && list.isLoaded(modId);
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 目标 mod 的版本字符串；拿不到时返回 null。 */
    public static String versionOf(String modId) {
        if (modId == null || modId.isBlank()) {
            return null;
        }
        try {
            ModList list = ModList.get();
            if (list == null) {
                return null;
            }
            Object container = ReflectSupport.invoke(list, "getModContainerById", String.class, modId);
            if (container == null) {
                return null;
            }
            Object info = ReflectSupport.invoke(container, "getModInfo");
            if (info == null) {
                return null;
            }
            Object version = ReflectSupport.invoke(info, "getVersion");
            return version == null ? null : version.toString();
        } catch (Throwable throwable) {
            return null;
        }
    }

    /** 目标 mod 的显示名；拿不到时返回 modId 本身。 */
    public static String displayNameOf(String modId) {
        try {
            ModList list = ModList.get();
            if (list == null) {
                return modId;
            }
            Object container = ReflectSupport.invoke(list, "getModContainerById", String.class, modId);
            if (container == null) {
                return modId;
            }
            Object info = ReflectSupport.invoke(container, "getModInfo");
            if (info == null) {
                return modId;
            }
            Object name = ReflectSupport.invoke(info, "getDisplayName");
            return name == null ? modId : name.toString();
        } catch (Throwable throwable) {
            return modId;
        }
    }
}
