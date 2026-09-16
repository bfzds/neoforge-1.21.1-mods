package dev.configpatcher.handler;

import dev.configpatcher.Log;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 处理方式注册表。内置两种实现，也可以由其它代码在 mod 构造阶段追加。 */
public final class HandlerRegistry {

    private static final Map<String, PatchHandler> HANDLERS = new LinkedHashMap<>();

    static {
        register(new ConfigValuePatchHandler());
        register(new TomlFilePatchHandler());
    }

    private HandlerRegistry() {
    }

    public static synchronized void register(PatchHandler handler) {
        if (handler == null || handler.id() == null || handler.id().isBlank()) {
            return;
        }
        HANDLERS.put(handler.id().toLowerCase(java.util.Locale.ROOT), handler);
    }

    public static PatchHandler get(String id) {
        if (id == null) {
            return null;
        }
        return HANDLERS.get(id.toLowerCase(java.util.Locale.ROOT));
    }

    /** 取不到时回退到 {@code configvalue}，并打一条提示，避免一条规则写错就静默失效。 */
    public static PatchHandler getOrDefault(String id) {
        PatchHandler handler = get(id);
        if (handler == null) {
            Log.LOGGER.warn("未知的处理方式 {}，已回退到 {}", id, ConfigValuePatchHandler.ID);
            return get(ConfigValuePatchHandler.ID);
        }
        return handler;
    }

    public static Set<String> ids() {
        return Set.copyOf(HANDLERS.keySet());
    }
}
