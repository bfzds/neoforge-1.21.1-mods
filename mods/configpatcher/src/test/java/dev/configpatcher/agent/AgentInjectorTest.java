package dev.configpatcher.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentInjectorTest {

    /** 造一个带 neoforge.mods.toml 的最小 mod jar，用来验证 modId 读取与去重。 */
    private static Path fakeModJar(Path dir, String fileName, String modId) throws IOException {
        Path jar = dir.resolve(fileName);
        String toml = """
                modLoader = "javafml"
                loaderVersion = "[4,)"
                license = "MIT"

                [[mods]]
                modId = "%s"
                version = "1.0.0"

                [[dependencies.%s]]
                modId = "neoforge"
                type = "required"
                """.formatted(modId, modId);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(toml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("dev/example/Dummy.class"));
            zip.write(new byte[] {1, 2, 3});
            zip.closeEntry();
        }
        return jar;
    }

    private static AgentInjector.Settings settings(
            List<Path> modSources,
            List<Path> packSources,
            boolean injectSelf,
            List<AgentInjector.FileOverride> overrides,
            List<AgentInjector.FileOverride> keybindOverrides,
            String keybindSource
    ) {
        return new AgentInjector.Settings(modSources, packSources, injectSelf, overrides, keybindOverrides,
                keybindSource, "options.txt", List.of());
    }

    @Test
    void readsModIdFromModsSectionNotDependencies() throws IOException {
        Path dir = Files.createTempDirectory("cp-inject-id");
        Path jar = fakeModJar(dir, "modernui.jar", "modernui");
        assertEquals("modernui", AgentInjector.readModId(jar));
    }

    @Test
    void missingManifestYieldsNullModId(@TempDir Path dir) throws IOException {
        Path zip = dir.resolve("plain.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("a.txt"));
            out.write("hi".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        assertNull(AgentInjector.readModId(zip));
    }

    @Test
    void modAlreadyPresentIsNotInjectedAgain(@TempDir Path source, @TempDir Path instance) throws IOException {
        fakeModJar(source, "modernui-3.13.jar", "modernui");
        fakeModJar(source, "extendedae_plus-1.6.2.jar", "extendedae_plus");

        Path modsDir = instance.resolve("mods");
        Files.createDirectories(modsDir);
        // 整合包自带一个旧版 ModernUI
        fakeModJar(modsDir, "ModernUI-NeoForge-1.21.1-3.12.0.jar", "modernui");

        List<AgentInjector.Action> actions = new ArrayList<>();
        AgentInjector.injectMods(instance, settings(List.of(source), List.of(), false, List.of(), List.of(), null),
                null, actions);

        assertFalse(Files.exists(modsDir.resolve("modernui-3.13.jar")), "已有 modernui 就不该再注入");
        assertTrue(Files.exists(modsDir.resolve("extendedae_plus-1.6.2.jar")), "没有的 mod 应该被注入");
        assertTrue(actions.stream().anyMatch(a -> a.name().contains("modernui") && !a.changed()));
    }

    @Test
    void selfJarIsInjectedWhenEnabled(@TempDir Path instance) throws IOException {
        Path self = fakeModJar(instance, "configpatcher-0.1.0.jar", "configpatcher");
        List<AgentInjector.Action> actions = new ArrayList<>();
        AgentInjector.injectMods(instance, settings(List.of(), List.of(), true, List.of(), List.of(), null),
                self, actions);
        assertTrue(Files.exists(instance.resolve("mods").resolve("configpatcher-0.1.0.jar")));
    }

    @Test
    void resourcePacksAreCopiedAndEnabledInOptions(@TempDir Path source, @TempDir Path instance) throws IOException {
        Files.writeString(source.resolve("Faithful.zip"), "pack", StandardCharsets.UTF_8);
        Files.writeString(instance.resolve("options.txt"),
                "version:3953\nresourcePacks:[\"vanilla\"]\nincompatibleResourcePacks:[\"file/Faithful.zip\"]\n",
                StandardCharsets.UTF_8);

        List<AgentInjector.Action> actions = new ArrayList<>();
        List<String> packs = AgentInjector.injectResourcePacks(instance,
                settings(List.of(), List.of(source), false, List.of(), List.of(), null), actions);
        assertEquals(List.of("file/Faithful.zip"), packs);

        AgentInjector.enableResourcePacks(instance, packs, actions);
        String options = Files.readString(instance.resolve("options.txt"), StandardCharsets.UTF_8);
        assertTrue(Files.exists(instance.resolve("resourcepacks").resolve("Faithful.zip")));
        assertTrue(options.contains("file/Faithful.zip"));
        assertTrue(options.contains("incompatibleResourcePacks:[]"), "被注入的包必须从 incompatible 里移除：" + options);
    }

    @Test
    void settingsParsingHandlesCommentsAndSemicolons() {
        AgentInjector.Settings parsed = AgentInjector.parseSettings(List.of(
                "# 注释行",
                "modSources=C:\\a\\mods;C:\\b\\mods",
                "resourcePackSources=C:\\a\\材质包",
                "injectSelf=false",
                "fileOverrides=preset:recipe_type_names.json>config/extendedae_plus/recipe_type_names.json",
                "keybindFileOverrides=preset:tweakeroo.json>config/tweakeroo.json",
                "keybindSource=preset:options.txt"
        ));
        assertFalse(parsed.injectSelf());
        assertEquals(2, parsed.modSources().size());
        assertEquals(1, parsed.resourcePackSources().size());
        assertEquals("preset:recipe_type_names.json", parsed.fileOverrides().get(0).from());
        assertEquals("config/extendedae_plus/recipe_type_names.json", parsed.fileOverrides().get(0).to());
        assertEquals("config/tweakeroo.json", parsed.keybindFileOverrides().get(0).to());
        assertEquals("preset:options.txt", parsed.keybindSource());
    }

    @Test
    void embeddedPresetsAreReadable() throws IOException {
        String recipeNames = AgentInjector.readSource("preset:recipe_type_names.json");
        assertTrue(recipeNames.contains("minecraft:smelting"));
        String tweakeroo = AgentInjector.readSource("preset:tweakeroo.json");
        assertTrue(tweakeroo.contains("\"keys\""));
        String options = AgentInjector.readSource("preset:options.txt");
        assertTrue(options.contains("key_key.forward"));
    }

    @Test
    void fileOverrideWritesPresetContent(@TempDir Path instance) throws IOException {
        AgentInjector.Settings parsed = settings(List.of(), List.of(), false,
                List.of(new AgentInjector.FileOverride("preset:recipe_type_names.json",
                        "config/extendedae_plus/recipe_type_names.json")),
                List.of(), null);
        AgentInjector.applyFileOverrides(instance, parsed, new ArrayList<>());
        Path target = instance.resolve("config/extendedae_plus/recipe_type_names.json");
        assertTrue(Files.isRegularFile(target));
        assertTrue(Files.readString(target, StandardCharsets.UTF_8).contains("gtceu:assembler"));
    }

    @Test
    void keybindOnlyMergeKeepsOtherOptions(@TempDir Path instance) throws IOException {
        Files.writeString(instance.resolve("options.txt"),
                "version:3953\nrenderDistance:12\nkey_key.forward:key.keyboard.up\n", StandardCharsets.UTF_8);

        AgentInjector.Settings parsed = settings(List.of(), List.of(), false, List.of(), List.of(),
                "preset:options.txt");
        AgentInjector.applyFileOverrides(instance, parsed, new ArrayList<>());

        String result = Files.readString(instance.resolve("options.txt"), StandardCharsets.UTF_8);
        assertTrue(result.contains("key_key.forward:key.keyboard.w"), "键位应来自预设");
        assertTrue(result.contains("renderDistance:12"), "非键位设置必须保留");
        assertTrue(result.contains("version:3953"), "非键位设置必须保留");
    }

    @Test
    void jsonKeybindMergeThroughOverride(@TempDir Path instance) throws IOException {
        Files.createDirectories(instance.resolve("config"));
        // 预设里 GenericHotkeys>flexibleBlockPlacementOffset>keys = LEFT_CONTROL，flyPreset1 = ""
        Files.writeString(instance.resolve("config/tweakeroo.json"), """
                {
                  "GenericHotkeys": {
                    "flexibleBlockPlacementOffset": {
                      "keys": "LEFT_SHIFT"
                    },
                    "flyPreset1": {
                      "keys": "P"
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        AgentInjector.Settings parsed = settings(List.of(), List.of(), false, List.of(),
                List.of(new AgentInjector.FileOverride("preset:tweakeroo.json", "config/tweakeroo.json")),
                null);
        AgentInjector.applyFileOverrides(instance, parsed, new ArrayList<>());

        String result = Files.readString(instance.resolve("config/tweakeroo.json"), StandardCharsets.UTF_8);
        assertTrue(result.contains("\"keys\": \"LEFT_CONTROL\""), "应按预设改写 tweakeroo 键位");
        assertTrue(result.contains("\"keys\": \"\""), "预设里为空的热键应对齐成空");
        assertFalse(result.contains("\"keys\": \"P\""), "预设里为空的热键不应保留实例旧值");
    }

    /**
     * 复刻 2026-09-26 的事故：新实例首次启动时目标文件还不存在。
     * 当时合并器对空目标输出一个换行符，目标 mod 读不懂，键位被整体打回默认配置。
     */
    @Test
    void missingJsonTargetGetsUsableConfig(@TempDir Path instance) throws IOException {
        AgentInjector.Settings parsed = settings(List.of(), List.of(), false, List.of(),
                List.of(new AgentInjector.FileOverride("preset:tweakeroo.json", "config/tweakeroo.json")),
                null);
        AgentInjector.applyFileOverrides(instance, parsed, new ArrayList<>());

        Path target = instance.resolve("config/tweakeroo.json");
        String written = Files.readString(target, StandardCharsets.UTF_8);
        assertTrue(written.stripLeading().startsWith("{"), "目标不存在时不能写出坏文件：" + written);
        long keys = written.lines().filter(line -> line.trim().startsWith("\"keys\"")).count();
        assertTrue(keys >= 100, "写出来的应该是完整键位文件，实际 keys 行=" + keys);
        assertTrue(written.contains("\"keys\": \"LEFT_CONTROL\""), "预设里的键位应该被注入");
    }

    /** 样本不可用时（这里用没有 keys 行的 recipe 预设当样本），宁可什么都不做，也不能往目标里写坏内容。 */
    @Test
    void unusableSampleLeavesTargetUntouched(@TempDir Path instance) throws IOException {
        Files.createDirectories(instance.resolve("config"));
        String original = "{\n  \"keep\": 1\n}\n";
        Files.writeString(instance.resolve("config/tweakeroo.json"), original, StandardCharsets.UTF_8);

        AgentInjector.Settings parsed = settings(List.of(), List.of(), false, List.of(),
                List.of(new AgentInjector.FileOverride("preset:recipe_type_names.json", "config/tweakeroo.json")),
                null);
        List<AgentInjector.Action> actions = new ArrayList<>();
        AgentInjector.applyFileOverrides(instance, parsed, actions);

        assertEquals(original, Files.readString(instance.resolve("config/tweakeroo.json"), StandardCharsets.UTF_8));
        assertTrue(actions.stream().anyMatch(a -> a.detail().contains("没有任何 keys 行")),
                "应明确报出样本无效：" + actions);
    }
}
