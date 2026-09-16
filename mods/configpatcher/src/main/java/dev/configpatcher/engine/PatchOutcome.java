package dev.configpatcher.engine;

/**
 * 一条改写项的执行结果。
 *
 * @param ruleId   规则 id
 * @param targetMod 目标 modId
 * @param path     配置路径
 * @param status   结果分类
 * @param detail   人类可读的补充说明（旧值 → 新值、失败原因等）
 */
public record PatchOutcome(String ruleId, String targetMod, String path, Status status, String detail) {

    public enum Status {
        APPLIED("已改写"),
        UNCHANGED("无需改写"),
        MISSING_ENTRY("找不到配置项"),
        MOD_ABSENT("目标 mod 不存在"),
        VERSION_MISMATCH("版本不匹配"),
        SKIPPED("已跳过"),
        ERROR("执行出错");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static PatchOutcome applied(String ruleId, String targetMod, String path, String detail) {
        return new PatchOutcome(ruleId, targetMod, path, Status.APPLIED, detail);
    }

    public static PatchOutcome unchanged(String ruleId, String targetMod, String path, String detail) {
        return new PatchOutcome(ruleId, targetMod, path, Status.UNCHANGED, detail);
    }

    public static PatchOutcome missing(String ruleId, String targetMod, String path, String detail) {
        return new PatchOutcome(ruleId, targetMod, path, Status.MISSING_ENTRY, detail);
    }

    public static PatchOutcome error(String ruleId, String targetMod, String path, String detail) {
        return new PatchOutcome(ruleId, targetMod, path, Status.ERROR, detail);
    }

    /** 单行日志文本。 */
    public String line() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(status.label()).append("] ");
        sb.append(targetMod).append('.').append(path);
        if (detail != null && !detail.isBlank()) {
            sb.append(" —— ").append(detail);
        }
        return sb.toString();
    }
}
