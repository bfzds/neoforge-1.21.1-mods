package dev.configpatcher.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 rules.json 里的 JSON 值转换成目标配置项真正需要的类型。
 *
 * <p>基准类型来自“当前值”或“默认值”（即 {@code ModConfigSpec.ConfigValue#getRaw()} /
 * {@code getDefault()}），因此不需要用户额外声明类型。
 *
 * <p>转换刻意做得宽容一些（这也是“配置项类型变了”的预案之一）：
 * 字符串 {@code "256"} 可以写进整型项，单个值可以写进列表项，数字可以匹配枚举序数。
 */
public final class ValueCoercion {

    private ValueCoercion() {
    }

    /** 转换失败时抛出，调用方负责转成一条 ERROR 结果。 */
    public static final class CoercionException extends RuntimeException {
        public CoercionException(String message) {
            super(message);
        }

        public CoercionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * @param template 目标配置项当前的类型样板（可为 null，此时按 JSON 原始类型写入）
     * @param json     规则里写的值
     */
    public static Object coerce(Object template, JsonElement json) {
        if (json == null || json.isJsonNull()) {
            throw new CoercionException("规则里的值为 null");
        }
        if (template == null) {
            return fromJson(json);
        }
        try {
            return coerceStrict(template, json);
        } catch (CoercionException ex) {
            throw new CoercionException(ex.getMessage()
                    + "（规则里写的是 " + json + "，目标类型是 " + template.getClass().getSimpleName() + "）", ex);
        } catch (RuntimeException ex) {
            throw new CoercionException("值转换失败：" + ex
                    + "（规则里写的是 " + json + "，目标类型是 " + template.getClass().getSimpleName() + "）", ex);
        }
    }

    private static Object coerceStrict(Object template, JsonElement json) {
        if (template instanceof Boolean) {
            return json.getAsBoolean();
        }
        if (template instanceof Integer) {
            return json.getAsInt();
        }
        if (template instanceof Long) {
            return json.getAsLong();
        }
        if (template instanceof Float) {
            return json.getAsFloat();
        }
        if (template instanceof Double) {
            return json.getAsDouble();
        }
        if (template instanceof Short) {
            return json.getAsShort();
        }
        if (template instanceof Byte) {
            return json.getAsByte();
        }
        if (template instanceof String) {
            return json.getAsString();
        }
        if (template instanceof Number) {
            return json.getAsNumber();
        }
        if (template instanceof List<?> list) {
            return coerceList(list, json);
        }
        if (template.getClass().isEnum()) {
            return coerceEnum(template.getClass(), json);
        }
        if (json.isJsonPrimitive()) {
            return json.getAsString();
        }
        throw new CoercionException("暂不支持的配置类型：" + template.getClass().getName());
    }

    private static List<Object> coerceList(List<?> template, JsonElement json) {
        Object elementTemplate = template.isEmpty() ? null : template.get(0);
        List<Object> result = new ArrayList<>();
        if (json.isJsonArray()) {
            for (JsonElement element : json.getAsJsonArray()) {
                result.add(elementTemplate == null ? fromJson(element) : coerce(elementTemplate, element));
            }
            return result;
        }
        // 宽容处理：写单值也当成单元素列表，避免为了一个值去写 ["x"]
        result.add(elementTemplate == null ? fromJson(json) : coerce(elementTemplate, json));
        return result;
    }

    private static Object fromJson(JsonElement element) {
        if (!element.isJsonPrimitive()) {
            return element.toString();
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return primitive.getAsNumber();
        }
        return primitive.getAsString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerceEnum(Class<?> enumType, JsonElement json) {
        String name = json.getAsString();
        try {
            return Enum.valueOf((Class<Enum>) enumType, name);
        } catch (IllegalArgumentException ignored) {
            for (Object constant : enumType.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(name)) {
                    return constant;
                }
            }
        }
        if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
            Object[] constants = enumType.getEnumConstants();
            int ordinal = json.getAsInt();
            if (ordinal >= 0 && ordinal < constants.length) {
                return constants[ordinal];
            }
        }
        StringBuilder allowed = new StringBuilder();
        for (Object constant : enumType.getEnumConstants()) {
            if (allowed.length() > 0) {
                allowed.append(" / ");
            }
            allowed.append(((Enum<?>) constant).name());
        }
        throw new CoercionException("枚举 " + enumType.getSimpleName() + " 没有取值 " + name
                + "（可选：" + allowed + "）");
    }
}
