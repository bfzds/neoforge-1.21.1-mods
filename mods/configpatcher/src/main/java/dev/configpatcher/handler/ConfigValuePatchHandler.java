package dev.configpatcher.handler;

import dev.configpatcher.engine.PatchContext;
import dev.configpatcher.engine.PatchOutcome;
import dev.configpatcher.engine.PathSuggest;
import dev.configpatcher.engine.ValueCoercion;
import dev.configpatcher.rule.PatchEntry;
import dev.configpatcher.rule.PatchRule;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 首选落地方式：在目标 mod 的配置加载完成时，直接改写它内存里的 {@code ConfigValue}。
 *
 * <p>做法与 NeoForge 自带的配置界面一致：{@code ((ModConfigSpec) modConfig.getSpec()).getValues()}
 * 拿到 {@code List<String> 路径 → ConfigValue} 的映射，按路径定位后写入并让 ModConfig 落盘。
 * 整个过程不引用目标 mod 的任何类，因此不需要编译期依赖。
 *
 * <p>“配置项改名”的应对：
 * <ol>
 *   <li>先按 {@code path} 找；</li>
 *   <li>找不到就依次尝试 {@code aliases}（旧名/新名）；</li>
 *   <li>全都找不到时，用相似度算出最接近的候选路径写进报告，方便直接把规则改对。</li>
 * </ol>
 */
public final class ConfigValuePatchHandler implements PatchHandler {

    public static final String ID = "configvalue";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String fallbackId() {
        return TomlFilePatchHandler.ID;
    }

    @Override
    public List<PatchOutcome> apply(PatchContext context) {
        PatchRule rule = context.rule();
        Object specObject = context.config().getSpec();
        if (!(specObject instanceof ModConfigSpec spec)) {
            return List.of(PatchOutcome.error(rule.id(), rule.targetMod(), "*",
                    "该配置不是 ModConfigSpec 管理的，无法在内存里改写"));
        }
        if (!spec.isLoaded()) {
            return List.of(PatchOutcome.error(rule.id(), rule.targetMod(), "*",
                    "配置尚未加载完成，本次跳过"));
        }

        Map<List<String>, ModConfigSpec.ConfigValue<?>> indexed = index(spec.getValues());
        if (indexed.isEmpty()) {
            return List.of(PatchOutcome.error(rule.id(), rule.targetMod(), "*",
                    "该配置没有暴露任何可改写的 ConfigValue"));
        }

        List<PatchOutcome> outcomes = new ArrayList<>(rule.entries().size());
        for (PatchEntry entry : rule.entries()) {
            outcomes.add(applyEntry(context, indexed, entry));
        }
        return outcomes;
    }

    private static PatchOutcome applyEntry(
            PatchContext context,
            Map<List<String>, ModConfigSpec.ConfigValue<?>> indexed,
            PatchEntry entry
    ) {
        PatchRule rule = context.rule();
        String ruleId = rule.id();
        String modId = rule.targetMod();

        ModConfigSpec.ConfigValue<?> value = null;
        String matchedPath = null;
        for (String candidate : entry.candidates()) {
            value = find(indexed, PatchEntry.split(candidate));
            if (value != null) {
                matchedPath = candidate;
                break;
            }
        }

        if (value == null) {
            List<String> suggestions = PathSuggest.suggest(indexed.keySet(), entry.segments(), 3);
            String detail = PathSuggest.describe(suggestions);
            if (entry.hasAliases()) {
                detail = "已尝试的路径：" + String.join("、", entry.candidates()) + "；" + detail;
            }
            if (entry.optional()) {
                return new PatchOutcome(ruleId, modId, entry.path(), PatchOutcome.Status.SKIPPED,
                        "目标 mod 没有这一项，规则标记为 optional，跳过（" + detail + "）");
            }
            return PatchOutcome.missing(ruleId, modId, entry.path(), detail);
        }

        Object current = value.getRaw();
        Object template = current != null ? current : value.getDefault();
        Object wanted;
        try {
            wanted = ValueCoercion.coerce(template, entry.value());
        } catch (RuntimeException ex) {
            return PatchOutcome.error(ruleId, modId, entry.path(), "值类型转换失败：" + ex.getMessage());
        }

        String viaAlias = matchedPath != null && !matchedPath.equals(entry.path())
                ? "（通过别名 " + matchedPath + " 命中）"
                : "";

        if (Objects.equals(current, wanted)) {
            return PatchOutcome.unchanged(ruleId, modId, entry.path(), "当前值已经是 " + wanted + viaAlias);
        }

        try {
            setRaw(value, wanted);
        } catch (Throwable throwable) {
            String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName()
                    : throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            return PatchOutcome.error(ruleId, modId, entry.path(), "写入失败：" + message);
        }
        return PatchOutcome.applied(ruleId, modId, entry.path(), current + " → " + wanted + viaAlias);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setRaw(ModConfigSpec.ConfigValue<?> value, Object wanted) {
        ((ModConfigSpec.ConfigValue) value).set(wanted);
    }

    /**
     * 把 {@code getValues()} 返回的配置树拍平成“路径 → ConfigValue”。
     *
     * <p>NeoForge 的 {@code ModConfigSpec#getValues()} 返回的是一棵 {@code UnmodifiableConfig}，
     * 叶子节点才是 {@code ConfigValue}，嵌套的 section 需要递归展开（与 NeoForge 自带配置界面同一套做法）。
     */
    public static Map<List<String>, ModConfigSpec.ConfigValue<?>> index(Object values) {
        Map<List<String>, ModConfigSpec.ConfigValue<?>> indexed = new LinkedHashMap<>();
        if (values instanceof UnmodifiableConfig config) {
            collect(config, new ArrayList<>(), indexed);
        }
        return indexed;
    }

    private static void collect(
            UnmodifiableConfig config,
            List<String> prefix,
            Map<List<String>, ModConfigSpec.ConfigValue<?>> indexed
    ) {
        for (UnmodifiableConfig.Entry entry : config.entrySet()) {
            List<String> path = new ArrayList<>(prefix);
            path.add(entry.getKey());
            Object raw = entry.getRawValue();
            if (raw instanceof ModConfigSpec.ConfigValue<?> configValue) {
                indexed.putIfAbsent(path, configValue);
            } else if (raw instanceof UnmodifiableConfig section) {
                collect(section, path, indexed);
            }
        }
    }

    /** 先精确匹配，再后缀匹配（允许规则里省略开头的 section），最后大小写不敏感匹配。 */
    static ModConfigSpec.ConfigValue<?> find(
            Map<List<String>, ModConfigSpec.ConfigValue<?>> indexed,
            List<String> path
    ) {
        ModConfigSpec.ConfigValue<?> exact = indexed.get(path);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<List<String>, ModConfigSpec.ConfigValue<?>> entry : indexed.entrySet()) {
            List<String> key = entry.getKey();
            if (key.size() >= path.size() && key.subList(key.size() - path.size(), key.size()).equals(path)) {
                return entry.getValue();
            }
        }
        for (Map.Entry<List<String>, ModConfigSpec.ConfigValue<?>> entry : indexed.entrySet()) {
            List<String> key = entry.getKey();
            if (key.size() != path.size()) {
                continue;
            }
            boolean same = true;
            for (int i = 0; i < key.size(); i++) {
                if (!key.get(i).toLowerCase(Locale.ROOT).equals(path.get(i).toLowerCase(Locale.ROOT))) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return entry.getValue();
            }
        }
        return null;
    }
}
