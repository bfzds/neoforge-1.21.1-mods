package dev.configpatcher.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 失败账本：记录“哪条规则的哪个配置项没改成”，并把这些条目加入跳过集合。
 *
 * <p>两件事：
 * <ol>
 *   <li><b>跳过</b> —— 已经失败的条目不再重试（配置热重载、服务器启动兜底、/configpatcher apply 都会跳过它们），
 *       避免反复报错、反复刷日志；</li>
 *   <li><b>通知</b> —— 玩家进入游戏时由 {@code JoinNotice} 把清单发到聊天栏，
 *       说清楚是哪个 mod 的哪个选项没改成功；也可以用 {@code /configpatcher failures} 随时查看。</li>
 * </ol>
 *
 * <p>清空方式：{@code /configpatcher failures clear}，或者 {@code /configpatcher reload}
 * （重读规则等于重新评估，账本会一起清掉）。
 */
public final class FailureLedger {

    private static final int MAX_REASON_LENGTH = 140;
    private static final Object LOCK = new Object();

    /** key = ruleId|path */
    private static final Map<String, Failure> FAILURES = new LinkedHashMap<>();
    private static final Set<String> SKIPPED = new LinkedHashSet<>();

    private FailureLedger() {
    }

    /**
     * @param ruleId     规则 id
     * @param modId      目标 modId
     * @param configFile 目标配置文件名
     * @param path       规则里写的配置路径
     * @param reason     失败原因（会截断，保证聊天栏一行放得下）
     */
    public record Failure(String ruleId, String modId, String configFile, String path, String reason) {

        /** 聊天栏用的短描述。 */
        public String shortLine() {
            return modId + " · " + path + " —— " + reason;
        }
    }

    public static void record(String ruleId, String modId, String configFile, String path, String reason) {
        String key = key(ruleId, path);
        synchronized (LOCK) {
            FAILURES.put(key, new Failure(ruleId, modId, configFile, path, shorten(reason)));
            SKIPPED.add(key);
        }
    }

    /** 某一项后来成功了，把它从账本与跳过集合里划掉。 */
    public static void forget(String ruleId, String path) {
        String key = key(ruleId, path);
        synchronized (LOCK) {
            FAILURES.remove(key);
            SKIPPED.remove(key);
        }
    }

    /** 该项是否已被放弃（不再重试）。 */
    public static boolean isSkipped(String ruleId, String path) {
        synchronized (LOCK) {
            return SKIPPED.contains(key(ruleId, path));
        }
    }

    public static List<Failure> failures() {
        synchronized (LOCK) {
            return List.copyOf(FAILURES.values());
        }
    }

    public static List<Failure> forMod(String modId) {
        List<Failure> result = new ArrayList<>();
        synchronized (LOCK) {
            for (Failure failure : FAILURES.values()) {
                if (failure.modId().equalsIgnoreCase(modId)) {
                    result.add(failure);
                }
            }
        }
        return result;
    }

    public static int size() {
        synchronized (LOCK) {
            return FAILURES.size();
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            FAILURES.clear();
            SKIPPED.clear();
        }
    }

    private static String key(String ruleId, String path) {
        return (ruleId == null ? "" : ruleId) + "|" + (path == null ? "" : path);
    }

    private static String shorten(String reason) {
        if (reason == null) {
            return "(没有更多信息)";
        }
        String flat = reason.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.isEmpty()) {
            return "(没有更多信息)";
        }
        return flat.length() <= MAX_REASON_LENGTH ? flat : flat.substring(0, MAX_REASON_LENGTH) + "…";
    }
}
