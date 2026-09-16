package dev.configpatcher.engine;

import java.lang.reflect.Method;

/**
 * 极小的反射工具。
 *
 * <p>本 mod 只在“无法用公开 API 直接表达”的地方使用反射（例如给 ModConfig 落盘、
 * 读取目标 mod 版本号），核心的取值/写值走的是 NeoForge 公开的 ModConfigSpec API。
 */
public final class ReflectSupport {

    private ReflectSupport() {
    }

    public static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 调用无参方法；任何异常都返回 null，由调用方决定降级方式。 */
    public static Object invoke(Object target, String name) {
        if (target == null) {
            return null;
        }
        Method method = findMethod(target.getClass(), name);
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 调用单参方法；任何异常都返回 null。 */
    public static Object invoke(Object target, String name, Class<?> parameterType, Object argument) {
        if (target == null) {
            return null;
        }
        Method method = findMethod(target.getClass(), name, parameterType);
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target, argument);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 调用无参方法，返回是否成功（用于 {@code save()} 这类只关心成败的调用）。 */
    public static boolean call(Object target, String name) {
        if (target == null) {
            return false;
        }
        Method method = findMethod(target.getClass(), name);
        if (method == null) {
            return false;
        }
        try {
            method.invoke(target);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
