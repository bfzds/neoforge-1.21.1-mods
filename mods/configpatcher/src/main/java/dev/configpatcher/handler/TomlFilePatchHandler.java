package dev.configpatcher.handler;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.configpatcher.Log;
import dev.configpatcher.engine.PatchContext;
import dev.configpatcher.engine.PatchOutcome;
import dev.configpatcher.rule.PatchEntry;
import dev.configpatcher.rule.PatchRule;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 兜底落地方式：直接按行改写目标 mod 的 TOML 配置文件。
 *
 * <p>适用于目标 mod 不用 ModConfigSpec（因此拿不到 ConfigValue）的情况。
 * 只替换“命中的那一行的值”，保留缩进与行尾注释；改完后需要重启才生效，
 * 因此这里的结果说明里会显式标注。
 */
public final class TomlFilePatchHandler implements PatchHandler {

    public static final String ID = "toml-file";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<PatchOutcome> apply(PatchContext context) {
        return patch(context.rule(), context.config().getFileName());
    }

    /**
     * 不依赖 ModConfig 的入口：直接按文件名改写配置文件。
     *
     * <p>给「目标 mod 用自己那套配置系统」的情况用 —— 例如 JEI 的 {@code config/jei/jei-client.ini}，
     * 它不会触发 NeoForge 的 {@code ModConfigEvent}，所以只能由启动阶段的补跑按文件名直接执行。
     */
    public static List<PatchOutcome> applyToFile(PatchRule rule, String fileName) {
        return patch(rule, fileName);
    }

    private static List<PatchOutcome> patch(PatchRule rule, String fileName) {
        Path file = FMLPaths.CONFIGDIR.get().resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return List.of(PatchOutcome.missing(rule.id(), rule.targetMod(), fileName,
                    "配置文件不存在：" + file));
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return List.of(PatchOutcome.error(rule.id(), rule.targetMod(), fileName,
                    "读取失败：" + ex.getMessage()));
        }

        List<PatchOutcome> outcomes = new ArrayList<>(rule.entries().size());
        List<String> original = List.copyOf(lines);
        for (PatchEntry entry : rule.entries()) {
            Replacement replacement = replace(lines, entry, rule);
            outcomes.add(replacement.outcome());
        }

        if (!lines.equals(original)) {
            try {
                Files.write(file, lines, StandardCharsets.UTF_8);
                Log.LOGGER.info("已按文件方式改写配置：{}（需重启游戏后生效）", file);
            } catch (IOException ex) {
                outcomes.add(PatchOutcome.error(rule.id(), rule.targetMod(), fileName,
                        "写回失败：" + ex.getMessage()));
            }
        }
        return outcomes;
    }

    private static Replacement replace(List<String> lines, PatchEntry entry, PatchRule rule) {
        List<String> segments = entry.segments();
        String key = segments.get(segments.size() - 1);
        String section = segments.size() > 1
                ? String.join(".", segments.subList(0, segments.size() - 1))
                : "";

        int from = 0;
        int to = lines.size();
        if (!section.isEmpty()) {
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    if (found) {
                        to = i;
                        break;
                    }
                    String name = trimmed.substring(1, trimmed.length() - 1).trim();
                    if (name.equalsIgnoreCase(section)) {
                        found = true;
                        from = i + 1;
                    }
                }
            }
            if (!found) {
                return new Replacement(PatchOutcome.missing(rule.id(), rule.targetMod(), entry.path(),
                        "文件里没有 [" + section + "] 段"), false);
            }
        }

        String wanted = toToml(entry.value());
        for (int i = from; i < to; i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            String foundKey = trimmed.substring(0, equals).trim();
            if (!foundKey.equalsIgnoreCase(key)) {
                continue;
            }
            lines.set(i, rewriteLine(line, foundKey, wanted));

            // section 为空时只认文件顶层（遇到第一个 [xxx] 就停）
            return new Replacement(PatchOutcome.applied(rule.id(), rule.targetMod(), entry.path(),
                    "写入 " + wanted + "（文件方式，重启后生效）"), true);
        }

        return new Replacement(PatchOutcome.missing(rule.id(), rule.targetMod(), entry.path(),
                "文件里没有键 " + key), false);
    }

    /** 保留缩进与前后的对齐空格，尽量只替换值本身，并保留行尾注释。 */
    private static String rewriteLine(String line, String key, String wanted) {
        int keyStart = line.indexOf(key);
        if (keyStart < 0) {
            return key + " = " + wanted;
        }
        String beforeKey = line.substring(0, keyStart);
        String afterKey = line.substring(keyStart + key.length());
        int equals = afterKey.indexOf('=');
        if (equals < 0) {
            return beforeKey + key + " = " + wanted;
        }
        String rest = afterKey.substring(equals + 1);
        String trailing = "";
        int comment = rest.indexOf('#');
        if (comment >= 0) {
            trailing = " " + rest.substring(comment).trim();
        }
        return beforeKey + key + " = " + wanted + trailing;
    }

    private static String toToml(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "\"\"";
        }
        if (element.isJsonArray()) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (JsonElement item : element.getAsJsonArray()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(toToml(item));
                first = false;
            }
            return sb.append(']').toString();
        }
        if (element.isJsonObject()) {
            return element.toString();
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return String.valueOf(primitive.getAsBoolean());
        }
        if (primitive.isNumber()) {
            return primitive.getAsNumber().toString();
        }
        return '"' + escape(primitive.getAsString()) + '"';
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record Replacement(PatchOutcome outcome, boolean changed) {
    }
}
