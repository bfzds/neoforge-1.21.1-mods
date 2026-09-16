package dev.configpatcher.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 「注入的 mod 与整合包自带版本冲突」这一整类防范措施的测试。 */
class InjectionConflictTest {

    private static Path fakeModJar(Path dir, String fileName, String modId, int padding) throws IOException {
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
            zip.putNextEntry(new ZipEntry("pad.bin"));
            zip.write(new byte[padding]);
            zip.closeEntry();
        }
        return jar;
    }

    private static AgentInjector.Settings settings(List<Path> modSources) {
        return new AgentInjector.Settings(modSources, List.of(), false, List.of(), List.of(), null, "options.txt",
                List.of());
    }

    @Test
    void ledgerRoundTrips(@TempDir Path dir) {
        Path file = dir.resolve("injected.log");
        InjectionLedger.Entry entry = new InjectionLedger.Entry("a.jar", "modernui", 123L, "C:\\src\\a.jar",
                "2026-01-01 00:00:00");
        InjectionLedger.save(file, List.of(entry));
        List<InjectionLedger.Entry> loaded = InjectionLedger.load(file);
        assertEquals(1, loaded.size());
        assertEquals("a.jar", loaded.get(0).fileName());
        assertEquals("modernui", loaded.get(0).modId());
        assertEquals(123L, loaded.get(0).size());
    }

    @Test
    void ledgerIgnoresCommentsAndBrokenLines() {
        assertNull(InjectionLedger.parse("# comment"));
        assertNull(InjectionLedger.parse(""));
        assertNull(InjectionLedger.parse("just-one-field"));
    }

    /** 核心场景：上次注入过，这次整合包自己带了同一个 mod → 删掉自己那份，避免重复 mod 崩游戏。 */
    @Test
    void previouslyInjectedCopyIsRemovedWhenBundleShipsTheMod(@TempDir Path mods, @TempDir Path ledgerDir)
            throws IOException {
        Path mine = fakeModJar(mods, "ModernUI-3.13.jar", "modernui", 16);
        fakeModJar(mods, "ModernUI-3.12-bundled.jar", "modernui", 32);

        Path ledgerFile = ledgerDir.resolve("injected.log");
        InjectionLedger.save(ledgerFile, List.of(InjectionLedger.Entry.of("ModernUI-3.13.jar", "modernui",
                Files.size(mine), "C:\\src\\ModernUI-3.13.jar")));

        List<AgentInjector.Action> actions = new ArrayList<>();
        List<InjectionLedger.Entry> kept = AgentInjector.reconcile(mods, ledgerFile, actions);

        assertFalse(Files.exists(mine), "整合包已自带 modernui，自己注入的副本必须删除");
        assertTrue(Files.exists(mods.resolve("ModernUI-3.12-bundled.jar")), "整合包自带的文件绝不能动");
        assertTrue(kept.isEmpty(), "已删除的文件不该再留在账本里");
        assertTrue(actions.stream().anyMatch(a -> a.changed() && a.kind().equals("cleanup")));
    }

    /** 只有自己注入的那一份时，不能删。 */
    @Test
    void uniqueInjectedCopyIsKept(@TempDir Path mods, @TempDir Path ledgerDir) throws IOException {
        Path mine = fakeModJar(mods, "ModernUI-3.13.jar", "modernui", 16);
        Path ledgerFile = ledgerDir.resolve("injected.log");
        InjectionLedger.save(ledgerFile, List.of(InjectionLedger.Entry.of("ModernUI-3.13.jar", "modernui",
                Files.size(mine), "src")));

        List<AgentInjector.Action> actions = new ArrayList<>();
        List<InjectionLedger.Entry> kept = AgentInjector.reconcile(mods, ledgerFile, actions);

        assertTrue(Files.exists(mine), "只有自己这一份时不能删");
        assertEquals(1, kept.size());
    }

    /** 文件被改动过（大小不符）就不再由本工具管理，更不能删。 */
    @Test
    void modifiedFileIsHandedBackToTheUser(@TempDir Path mods, @TempDir Path ledgerDir) throws IOException {
        Path mine = fakeModJar(mods, "ModernUI-3.13.jar", "modernui", 16);
        fakeModJar(mods, "ModernUI-3.12-bundled.jar", "modernui", 32);

        Path ledgerFile = ledgerDir.resolve("injected.log");
        InjectionLedger.save(ledgerFile, List.of(InjectionLedger.Entry.of("ModernUI-3.13.jar", "modernui",
                Files.size(mine) + 999, "src")));

        List<AgentInjector.Action> actions = new ArrayList<>();
        List<InjectionLedger.Entry> kept = AgentInjector.reconcile(mods, ledgerFile, actions);

        assertTrue(Files.exists(mine), "大小不符说明文件被改过，不能删");
        assertTrue(kept.isEmpty(), "该文件不再由本工具管理");
    }

    /** 源目录里同一个 mod 有多个版本 → 只注入一个（版本号更大的）。 */
    @Test
    void duplicateVersionsInSourceAreDeduplicated(@TempDir Path source) throws IOException {
        fakeModJar(source, "ModernUI-3.12.jar", "modernui", 8);
        fakeModJar(source, "ModernUI-3.13.jar", "modernui", 16);
        fakeModJar(source, "unrelated-1.0.jar", "unrelated", 8);

        List<AgentInjector.Action> actions = new ArrayList<>();
        Map<String, Path> plan = AgentInjector.planCandidates(settings(List.of(source)), null, actions);

        assertEquals(2, plan.size(), "同一个 mod 只应保留一个候选");
        assertEquals("ModernUI-3.13.jar", plan.get("modernui").getFileName().toString());
        assertTrue(actions.stream().anyMatch(a -> a.detail().contains("只注入")));
    }

    @Test
    void naturalOrderPicksHigherVersion() {
        assertTrue(AgentInjector.compareNatural("ModernUI-3.13.jar", "ModernUI-3.12.jar") > 0);
        assertTrue(AgentInjector.compareNatural("mod-1.10.0.jar", "mod-1.9.0.jar") > 0);
        assertTrue(AgentInjector.compareNatural("mod-2.0.jar", "mod-10.0.jar") < 0);
        assertEquals(0, AgentInjector.compareNatural("same.jar", "same.jar"));
    }

    /** mods 里已有同名文件但属于别的 mod → 跳过，不覆盖别人的东西。 */
    @Test
    void sameFileNameButDifferentModIsNotOverwritten(@TempDir Path source, @TempDir Path instance)
            throws IOException {
        fakeModJar(source, "shared-name.jar", "mod_a", 8);
        Path modsDir = instance.resolve("mods");
        Files.createDirectories(modsDir);
        Path existing = fakeModJar(modsDir, "shared-name.jar", "mod_b", 8);

        List<AgentInjector.Action> actions = new ArrayList<>();
        AgentInjector.injectMods(instance, settings(List.of(source)), null, actions);

        assertEquals("mod_b", AgentInjector.readModId(existing), "同名文件不该被别的 mod 覆盖");
        assertTrue(actions.stream().anyMatch(a -> a.detail().contains("同名文件")));
    }

    /** 自己那份允许覆盖：保证 mod 侧版本与正在运行的 Agent 一致。 */
    @Test
    void selfJarOverwritesOlderCopy(@TempDir Path instance) throws IOException {
        Path modsDir = instance.resolve("mods");
        Files.createDirectories(modsDir);
        Path old = fakeModJar(modsDir, "configpatcher-0.1.0.jar", "configpatcher", 8);
        long oldSize = Files.size(old);

        Path self = fakeModJar(instance, "configpatcher-0.2.0.jar", "configpatcher", 64);
        AgentInjector.Settings cfg = new AgentInjector.Settings(List.of(), List.of(), true,
                List.of(), List.of(), null, "options.txt", List.of());
        List<AgentInjector.Action> actions = new ArrayList<>();
        AgentInjector.injectMods(instance, cfg, self, actions);

        Path injected = modsDir.resolve("configpatcher-0.2.0.jar");
        assertTrue(Files.exists(injected), "Agent 自己应该被注入");
        assertTrue(Files.size(injected) > oldSize);
    }

    /** 改文件前先备份一次，且不会覆盖已有的备份。 */
    @Test
    void filesAreBackedUpBeforeFirstWrite(@TempDir Path instance) throws IOException {
        Path options = instance.resolve("options.txt");
        Files.writeString(options, "version:1\n", StandardCharsets.UTF_8);

        AgentInjector.Settings cfg = new AgentInjector.Settings(List.of(), List.of(), false, List.of(),
                List.of(), "preset:options.txt", "options.txt", List.of());
        AgentInjector.applyFileOverrides(instance, cfg, new ArrayList<>());

        Path backup = instance.resolve("options.txt.configpatcher.bak");
        assertTrue(Files.isRegularFile(backup), "第一次修改前应留下备份");
        assertEquals("version:1\n", Files.readString(backup, StandardCharsets.UTF_8));
        assertTrue(Files.readString(options, StandardCharsets.UTF_8).contains("key_key.forward"));
    }
}
