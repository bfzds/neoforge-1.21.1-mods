package dev.configpatcher.engine;

import dev.configpatcher.Log;
import dev.configpatcher.handler.HandlerRegistry;
import dev.configpatcher.handler.PatchHandler;
import dev.configpatcher.rule.PatchEntry;
import dev.configpatcher.rule.PatchRule;
import dev.configpatcher.rule.RuleLoader;
import dev.configpatcher.rule.RuleSet;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.fml.event.config.ModConfigEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 框架的调度核心：
 * <ol>
 *   <li>启动时加载规则文件（缺失则生成模板）并做一次“目标 mod 是否存在”的预检；</li>
 *   <li>任意 mod 的配置加载 / 重载时，匹配规则并把活交给对应的 {@link PatchHandler}；</li>
 *   <li>记录结果，供 {@code /configpatcher status|check|dump} 使用。</li>
 * </ol>
 *
 * <p>所有入口都做了异常隔离：规则写错、目标 mod 配置异常，最坏情况只是“这条规则没生效”，
 * 不会把游戏搞崩。
 */
public final class PatchEngine {

    private static final Object LOCK = new Object();

    /** 每个配置目标最近一次的执行结果（key 形如 {@code othermod/othermod-common.toml}）。 */
    private static final Map<String, PatchReport> LAST_REPORTS = new LinkedHashMap<>();
    /** 每条规则累计命中次数（用于发现“已经失效的规则”）。 */
    private static final Map<String, Integer> HIT_COUNTS = new LinkedHashMap<>();

    private static volatile RuleSet ruleSet = RuleSet.empty("(尚未加载)");
    private static volatile boolean bootstrapped;

    private PatchEngine() {
    }

    /** mod 构造阶段调用：确保规则文件存在并加载。 */
    public static void bootstrap() {
        synchronized (LOCK) {
            if (bootstrapped) {
                return;
            }
            bootstrapped = true;
        }
        try {
            RuleLoader.ensureDefaultRulesFile();
        } catch (Throwable throwable) {
            Log.LOGGER.warn("准备默认规则文件时出错：{}", throwable.toString());
        }
        reload();
    }

    /** 重新读取规则文件（命令 {@code /configpatcher reload} 与启动时都会走这里）。 */
    public static void reload() {
        RuleSet loaded;
        try {
            loaded = RuleLoader.load();
        } catch (Throwable throwable) {
            Log.LOGGER.error("加载规则失败：{}", throwable.toString());
            loaded = RuleSet.empty("(加载失败)");
        }
        ruleSet = loaded;
        synchronized (LOCK) {
            HIT_COUNTS.clear();
        }
        // 规则变了等于重新评估：把“已跳过”的失败记录一起清掉，让改动后的规则有机会重试
        FailureLedger.clear();
        Log.LOGGER.info("已加载 {} 条配置改写规则（来源：{}）", loaded.rules().size(), loaded.source());
        logPreflight(loaded);
    }

    public static RuleSet rules() {
        return ruleSet;
    }

    /** 再打一次预检（mod 列表完全就绪后调用，确保日志反映最终状态）。 */
    public static void summarize() {
        logPreflight(ruleSet);
    }

    /** 配置加载 / 重载事件入口。 */
    public static void onConfigEvent(ModConfigEvent event) {
        ModConfig config = event == null ? null : event.getConfig();
        if (config == null) {
            return;
        }
        applyTo(config);
    }

    /**
     * 对单个配置应用规则。返回 null 表示这个配置没有任何规则命中（大多数情况）。
     */
    public static PatchReport applyTo(ModConfig config) {
        RuleSet current = ruleSet;
        if (!current.enabled() || current.isEmpty() || config == null) {
            return null;
        }
        // 自己的配置不参与改写，避免自锁
        if (dev.configpatcher.ConfigPatcher.MOD_ID.equalsIgnoreCase(config.getModId())) {
            return null;
        }
        List<PatchRule> rules = current.forMod(config.getModId());
        if (rules.isEmpty()) {
            return null;
        }

        String version = ModPresence.versionOf(config.getModId());
        PatchReport report = new PatchReport(config.getModId() + "/" + config.getFileName());
        for (PatchRule rule : rules) {
            if (!rule.enabled()) {
                continue;
            }
            if (!rule.matchesConfig(config.getFileName(), config.getType().name())) {
                continue;
            }
            if (!rule.matchesVersion(version)) {
                report.add(new PatchOutcome(rule.id(), rule.targetMod(), "*",
                        PatchOutcome.Status.VERSION_MISMATCH,
                        "目标版本 " + version + " 不在 " + rule.versionRange() + " 内"));
                continue;
            }
            report.addAll(runHandler(rule, config, version));
        }

        if (report.isEmpty()) {
            return null;
        }
        remember(report);
        logReport(report);
        if (report.hasApplied()) {
            save(config);
        }
        return report;
    }

    /** 把当前所有已加载配置过一遍（{@code /configpatcher apply} 与服务器启动时使用）。 */
    public static List<PatchReport> applyAll() {
        List<PatchReport> reports = new ArrayList<>();
        for (ModConfig.Type type : ModConfig.Type.values()) {
            try {
                for (ModConfig config : ModConfigs.getConfigSet(type)) {
                    PatchReport report = applyTo(config);
                    if (report != null) {
                        reports.add(report);
                    }
                }
            } catch (Throwable throwable) {
                Log.LOGGER.warn("遍历 {} 配置时出错：{}", type, throwable.toString());
            }
        }
        return reports;
    }

    public static Map<String, PatchReport> lastReports() {
        synchronized (LOCK) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(LAST_REPORTS));
        }
    }

    public static Map<String, Integer> hitCounts() {
        synchronized (LOCK) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(HIT_COUNTS));
        }
    }

    /**
     * “从来没命中过”的规则 id：目标 mod 明明装了就绪，但规则一次都没生效。
     * 这类规则最可能因为目标 mod 改了配置项而失效，值得人工看一眼。
     */
    public static Set<String> idleRuleIds() {
        RuleSet current = ruleSet;
        Set<String> idle = new LinkedHashSet<>();
        Map<String, Integer> counts = hitCounts();
        for (PatchRule rule : current.rules()) {
            if (!rule.enabled()) {
                continue;
            }
            if (!ModPresence.isLoaded(rule.targetMod())) {
                continue;
            }
            Integer count = counts.get(rule.id());
            if (count == null || count == 0) {
                idle.add(rule.id());
            }
        }
        return idle;
    }

    private static List<PatchOutcome> runHandler(PatchRule rule, ModConfig config, String version) {
        PatchRule effective = withoutSkippedEntries(rule);
        if (effective == null) {
            // 这条规则的条目都已经失败过并放弃，直接跳过，不再重试
            return List.of();
        }

        PatchContext context = new PatchContext(config, effective, version);
        PatchHandler handler = HandlerRegistry.getOrDefault(effective.handler());
        if (handler == null) {
            return List.of(PatchOutcome.error(effective.id(), effective.targetMod(), "*", "没有可用的处理方式"));
        }
        List<PatchOutcome> outcomes = safeApply(handler, context);

        PatchReport probe = new PatchReport("probe");
        probe.addAll(outcomes);
        if (handler.fallbackId() != null && probe.hasNoEffect()) {
            PatchHandler fallback = HandlerRegistry.get(handler.fallbackId());
            if (fallback != null) {
                Log.LOGGER.info("规则 {} 在内存改写上没拿到结果，降级到 {} 处理", effective.id(), fallback.id());
                List<PatchOutcome> merged = new ArrayList<>(outcomes);
                merged.addAll(safeApply(fallback, context));
                outcomes = merged;
            }
        }

        recordFailures(effective, config, outcomes);
        return outcomes;
    }

    /**
     * 去掉“已经失败并放弃”的条目 —— 这就是“改不动就跳过这个 mod 的这一项”：
     * 失败过的条目不再重试，等 {@code /configpatcher reload} 或清空失败账本后才会重新尝试。
     */
    private static PatchRule withoutSkippedEntries(PatchRule rule) {
        List<PatchEntry> kept = new ArrayList<>(rule.entries().size());
        for (PatchEntry entry : rule.entries()) {
            if (!FailureLedger.isSkipped(rule.id(), entry.path())) {
                kept.add(entry);
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        if (kept.size() == rule.entries().size()) {
            return rule;
        }
        return new PatchRule(rule.id(), rule.comment(), rule.targetMod(), rule.enabled(), rule.versionRange(),
                rule.configFile(), rule.configType(), rule.handler(), rule.required(), kept);
    }

    /** 把这一轮的失败项记进账本（同一路径以最后一次结果为准），成功项则从账本里划掉。 */
    private static void recordFailures(PatchRule rule, ModConfig config, List<PatchOutcome> outcomes) {
        Map<String, PatchOutcome> lastByPath = new LinkedHashMap<>();
        for (PatchOutcome outcome : outcomes) {
            lastByPath.put(outcome.path(), outcome);
        }
        for (PatchOutcome outcome : lastByPath.values()) {
            switch (outcome.status()) {
                case MISSING_ENTRY, ERROR -> FailureLedger.record(rule.id(), rule.targetMod(),
                        config.getFileName(), outcome.path(), outcome.detail());
                case APPLIED, UNCHANGED -> FailureLedger.forget(rule.id(), outcome.path());
                default -> {
                    // VERSION_MISMATCH / SKIPPED / MOD_ABSENT 属于“按设计不处理”，不进失败账本
                }
            }
        }
    }

    private static List<PatchOutcome> safeApply(PatchHandler handler, PatchContext context) {
        try {
            List<PatchOutcome> outcomes = handler.apply(context);
            return outcomes == null ? List.of() : outcomes;
        } catch (Throwable throwable) {
            return List.of(PatchOutcome.error(context.rule().id(), context.targetMod(), "*",
                    "处理时抛出异常：" + throwable.getClass().getSimpleName()
                            + (throwable.getMessage() == null ? "" : "：" + throwable.getMessage())));
        }
    }

    private static void save(ModConfig config) {
        if (!ReflectSupport.call(config, "save")) {
            Log.LOGGER.warn("配置已在内存中改写，但落盘调用失败：{}/{}",
                    config.getModId(), config.getFileName());
        }
    }

    private static void remember(PatchReport report) {
        synchronized (LOCK) {
            LAST_REPORTS.put(report.subject(), report);
            for (PatchOutcome outcome : report.outcomes()) {
                if (outcome.status() == PatchOutcome.Status.APPLIED
                        || outcome.status() == PatchOutcome.Status.UNCHANGED) {
                    HIT_COUNTS.merge(outcome.ruleId(), 1, Integer::sum);
                }
            }
        }
    }

    private static void logReport(PatchReport report) {
        Log.LOGGER.info("{}", report.summary());
        for (String line : report.lines()) {
            Log.LOGGER.debug("  {}", line);
        }
        int problems = report.count(PatchOutcome.Status.MISSING_ENTRY)
                + report.count(PatchOutcome.Status.ERROR);
        if (problems > 0) {
            Log.LOGGER.warn("{} 里有 {} 项没对上，可能是目标 mod 更新后改了配置项；"
                            + "用 /configpatcher dump {} 导出实际配置项，或看 /configpatcher check",
                    report.subject(), problems, report.subject().split("/")[0]);
            for (PatchOutcome outcome : report.outcomes()) {
                if (outcome.status() == PatchOutcome.Status.MISSING_ENTRY
                        || outcome.status() == PatchOutcome.Status.ERROR) {
                    Log.LOGGER.warn("  {}", outcome.line());
                }
            }
        }
    }

    /** 启动预检：把“目标 mod 不存在所以跳过”这类情况一次性说清楚。 */
    private static void logPreflight(RuleSet loaded) {
        if (loaded.isEmpty()) {
            Log.LOGGER.info("当前没有任何改写规则；规则文件：{}", RuleLoader.rulesPath());
            return;
        }
        int present = 0;
        int absent = 0;
        for (String modId : loaded.targetMods()) {
            List<PatchRule> rules = loaded.forMod(modId);
            if (ModPresence.isLoaded(modId)) {
                present++;
                Log.LOGGER.info("目标 mod 已安装：{} {} —— 命中 {} 条规则",
                        modId, ModPresence.versionOf(modId), rules.size());
            } else {
                absent++;
                boolean required = rules.stream().anyMatch(PatchRule::required);
                if (required) {
                    Log.LOGGER.warn("目标 mod 不存在，相关规则按设计跳过：{}（{} 条规则）", modId, rules.size());
                } else {
                    Log.LOGGER.info("目标 mod 不存在，按设计跳过：{}（{} 条规则）", modId, rules.size());
                }
            }
        }
        Log.LOGGER.info("规则预检完成：已安装目标 {} 个 / 未安装 {} 个，共 {} 条规则",
                present, absent, loaded.rules().size());
    }
}
