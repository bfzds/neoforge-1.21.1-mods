package dev.configpatcher.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 「关闭游戏自动导出」的回写逻辑：实例 → 样本。 */
class SampleExporterTest {

    private static AgentInjector.Settings settings(String keybindSource, String keybindTarget,
                                                   List<AgentInjector.FileOverride> keybindOverrides,
                                                   List<AgentInjector.FileOverride> fileOverrides) {
        return new AgentInjector.Settings(List.of(), List.of(), false, fileOverrides, keybindOverrides,
                keybindSource, keybindTarget, List.of(), true);
    }

    /**
     * 造一份「看起来像真样本」的键位 JSON：{@code "keys"} 行数足够多，能过回写前的健康检查。
     * 真实样本有 247 行 keys，这里造 121 行。
     */
    private static String keybindSample(String firstKey) {
        StringBuilder sb = new StringBuilder("{\n  \"GenericHotkeys\": {\n");
        sb.append("    \"first\": {\n      \"keys\": \"").append(firstKey).append("\"\n    }");
        for (int i = 0; i < 120; i++) {
            sb.append(",\n    \"pad").append(i).append("\": {\n      \"keys\": \"\"\n    }");
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }

    @Test
    void optionsExportKeepsOnlyKeyLines(@TempDir Path instance, @TempDir Path library) throws IOException {
        Path sample = library.resolve("options.txt");
        Files.writeString(sample, "key_key.attack:key.keyboard.a\n", StandardCharsets.UTF_8);
        Files.writeString(instance.resolve("options.txt"),
                "fov:0.5\nkey_key.attack:key.keyboard.f\nkey_key.jump:key.keyboard.space\nlang:zh_cn\n",
                StandardCharsets.UTF_8);

        var actions = SampleExporter.export(instance,
                settings(sample.toString(), "options.txt", List.of(), List.of()));

        assertEquals("key_key.attack:key.keyboard.f\nkey_key.jump:key.keyboard.space\n",
                Files.readString(sample, StandardCharsets.UTF_8));
        assertEquals(1, actions.size());
        assertTrue(actions.get(0).changed());
    }

    @Test
    void fileOverrideIsCopiedBack(@TempDir Path instance, @TempDir Path library) throws IOException {
        String content = keybindSample("LEFT_CONTROL");
        Path sample = library.resolve("tweakeroo.json");
        Files.writeString(sample, "{\"old\":1}\n", StandardCharsets.UTF_8);
        Path targetDir = instance.resolve("config");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("tweakeroo.json"), content, StandardCharsets.UTF_8);

        SampleExporter.export(instance, settings(null, "options.txt",
                List.of(new AgentInjector.FileOverride(sample.toString(), "config/tweakeroo.json")),
                List.of()));

        assertEquals(content, Files.readString(sample, StandardCharsets.UTF_8));
    }

    @Test
    void unchangedSampleIsNotRewritten(@TempDir Path instance, @TempDir Path library) throws IOException {
        String content = keybindSample("LEFT_CONTROL");
        Path sample = library.resolve("tweakeroo.json");
        Files.writeString(sample, content, StandardCharsets.UTF_8);
        Path targetDir = instance.resolve("config");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("tweakeroo.json"), content, StandardCharsets.UTF_8);

        var actions = SampleExporter.export(instance, settings(null, "options.txt",
                List.of(new AgentInjector.FileOverride(sample.toString(), "config/tweakeroo.json")),
                List.of()));

        assertEquals(1, actions.size());
        assertFalse(actions.get(0).changed());
        // 没有真正写入，就不该留下备份目录
        assertFalse(Files.isDirectory(library.resolve(".backup")));
    }

    @Test
    void presetSourceIsSkipped(@TempDir Path instance) throws IOException {
        Files.writeString(instance.resolve("options.txt"),
                "key_key.attack:key.keyboard.f\n", StandardCharsets.UTF_8);

        var actions = SampleExporter.export(instance,
                settings("preset:options.txt", "options.txt", List.of(), List.of()));

        assertTrue(actions.isEmpty());
    }

    @Test
    void oldSampleIsBackedUpBeforeOverwrite(@TempDir Path instance, @TempDir Path library) throws IOException {
        Path sample = library.resolve("tweakeroo.json");
        Files.writeString(sample, "old\n", StandardCharsets.UTF_8);
        Path targetDir = instance.resolve("config");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("tweakeroo.json"), keybindSample("LEFT_CONTROL"), StandardCharsets.UTF_8);

        SampleExporter.export(instance, settings(null, "options.txt",
                List.of(new AgentInjector.FileOverride(sample.toString(), "config/tweakeroo.json")),
                List.of()));

        try (Stream<Path> walk = Files.walk(library.resolve(".backup"))) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            assertEquals(1, files.size());
            assertEquals("old\n", Files.readString(files.get(0), StandardCharsets.UTF_8));
        }
    }

    @Test
    void missingTargetIsReportedNotThrown(@TempDir Path instance, @TempDir Path library) {
        var actions = SampleExporter.export(instance,
                settings(library.resolve("options.txt").toString(), "options.txt", List.of(), List.of()));

        assertEquals(1, actions.size());
        assertFalse(actions.get(0).changed());
    }

    /** 复刻 2026-09-24 的事故：实例里只剩一个换行符，绝不能让它覆盖样本。 */
    @Test
    void brokenInstanceFileNeverOverwritesSample(@TempDir Path instance, @TempDir Path library) throws IOException {
        String good = keybindSample("LEFT_CONTROL");
        Path sample = library.resolve("tweakeroo.json");
        Files.writeString(sample, good, StandardCharsets.UTF_8);

        Path targetDir = instance.resolve("config");
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("tweakeroo.json"), "\n", StandardCharsets.UTF_8);

        var actions = SampleExporter.export(instance, settings(null, "options.txt",
                List.of(new AgentInjector.FileOverride(sample.toString(), "config/tweakeroo.json")),
                List.of()));

        assertEquals(good, Files.readString(sample, StandardCharsets.UTF_8), "样本被半成品覆盖了");
        assertEquals(1, actions.size());
        assertFalse(actions.get(0).changed());
        assertTrue(actions.get(0).detail().contains("内容异常"), actions.get(0).detail());
        assertFalse(Files.isDirectory(library.resolve(".backup")), "跳过时不该留下备份");
    }
}
