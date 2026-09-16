package dev.configpatcher.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 一次加载得到的全部规则，并按目标 modId 建索引，方便配置事件里快速查找。 */
public final class RuleSet {

    private final String source;
    private final boolean enabled;
    private final List<PatchRule> rules;
    private final Map<String, List<PatchRule>> byMod;

    public RuleSet(String source, boolean enabled, List<PatchRule> rules) {
        this.source = source == null ? "(未知来源)" : source;
        this.enabled = enabled;
        this.rules = List.copyOf(rules);
        Map<String, List<PatchRule>> index = new LinkedHashMap<>();
        for (PatchRule rule : this.rules) {
            index.computeIfAbsent(rule.targetMod().toLowerCase(java.util.Locale.ROOT), key -> new ArrayList<>()).add(rule);
        }
        this.byMod = Collections.unmodifiableMap(index);
    }

    public static RuleSet empty(String source) {
        return new RuleSet(source, true, List.of());
    }

    /** 规则文件路径（用于日志）。 */
    public String source() {
        return source;
    }

    /** 总开关，对应 rules.json 顶层的 {@code enabled}。 */
    public boolean enabled() {
        return enabled;
    }

    public List<PatchRule> rules() {
        return rules;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** 某个 mod 命中的规则（modId 大小写不敏感）。 */
    public List<PatchRule> forMod(String modId) {
        if (modId == null) {
            return List.of();
        }
        return byMod.getOrDefault(modId.toLowerCase(java.util.Locale.ROOT), List.of());
    }

    /** 规则里出现过的全部目标 modId（去重、保持出现顺序）。 */
    public Set<String> targetMods() {
        Set<String> mods = new LinkedHashSet<>();
        for (PatchRule rule : rules) {
            mods.add(rule.targetMod());
        }
        return mods;
    }
}
