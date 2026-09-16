package dev.configpatcher.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 一次处理（一个 ModConfig 或一次 /configpatcher 命令）产生的结果集合。 */
public final class PatchReport {

    private final String subject;
    private final List<PatchOutcome> outcomes = new ArrayList<>();

    public PatchReport(String subject) {
        this.subject = subject == null ? "(未知目标)" : subject;
    }

    public String subject() {
        return subject;
    }

    public void add(PatchOutcome outcome) {
        outcomes.add(outcome);
    }

    public void addAll(List<PatchOutcome> list) {
        outcomes.addAll(list);
    }

    public List<PatchOutcome> outcomes() {
        return Collections.unmodifiableList(outcomes);
    }

    public boolean isEmpty() {
        return outcomes.isEmpty();
    }

    public int count(PatchOutcome.Status status) {
        int total = 0;
        for (PatchOutcome outcome : outcomes) {
            if (outcome.status() == status) {
                total++;
            }
        }
        return total;
    }

    public boolean hasApplied() {
        return count(PatchOutcome.Status.APPLIED) > 0;
    }

    /** 是否一条都没真正落地（用于决定要不要降级到文件层处理）。 */
    public boolean hasNoEffect() {
        return count(PatchOutcome.Status.APPLIED) == 0 && count(PatchOutcome.Status.UNCHANGED) == 0;
    }

    /** 一行汇总，例如 {@code othermod-common.toml：已改写 2 / 无需改写 1 / 找不到配置项 1}。 */
    public String summary() {
        Map<PatchOutcome.Status, Integer> counters = new EnumMap<>(PatchOutcome.Status.class);
        for (PatchOutcome outcome : outcomes) {
            counters.merge(outcome.status(), 1, Integer::sum);
        }
        if (counters.isEmpty()) {
            return subject + "：没有命中任何规则";
        }
        StringBuilder sb = new StringBuilder(subject).append("：");
        boolean first = true;
        for (Map.Entry<PatchOutcome.Status, Integer> entry : counters.entrySet()) {
            if (!first) {
                sb.append(" / ");
            }
            sb.append(entry.getKey().label()).append(' ').append(entry.getValue());
            first = false;
        }
        return sb.toString();
    }

    /** 逐条明细文本。 */
    public List<String> lines() {
        List<String> lines = new ArrayList<>(outcomes.size());
        for (PatchOutcome outcome : outcomes) {
            lines.add(outcome.line());
        }
        return lines;
    }
}
