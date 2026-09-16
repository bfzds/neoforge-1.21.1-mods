package dev.configpatcher.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeybindMergerTest {

    private static final String SAMPLE = """
            key_key.forward:key.keyboard.w
            key_key.jump:key.keyboard.space
            key_key.inventory:key.keyboard.e
            resourcePacks:["vanilla"]
            """;

    private static final String EXISTING = """
            version:3953
            renderDistance:12
            key_key.forward:key.keyboard.up
            key_key.attack:key.mouse.left
            resourcePacks:["vanilla","file/Old.zip"]
            incompatibleResourcePacks:["file/New.zip"]
            """;

    @Test
    void onlyKeybindLinesAreReplaced() {
        String merged = KeybindMerger.merge(EXISTING, SAMPLE);
        assertTrue(merged.contains("key_key.forward:key.keyboard.w"), "样本键位应覆盖原值");
        assertTrue(merged.contains("key_key.attack:key.mouse.left"), "样本里没有的键位应保留原样");
        assertTrue(merged.contains("renderDistance:12"), "非键位设置必须原样保留");
        assertTrue(merged.contains("version:3953"), "非键位设置必须原样保留");
    }

    @Test
    void missingKeysAreAppended() {
        String merged = KeybindMerger.merge(EXISTING, SAMPLE);
        assertTrue(merged.contains("key_key.jump:key.keyboard.space"), "目标缺失的键位应补上");
        assertTrue(merged.contains("key_key.inventory:key.keyboard.e"), "目标缺失的键位应补上");
    }

    @Test
    void sampleWithoutKeybindsKeepsTargetUntouched() {
        String merged = KeybindMerger.merge(EXISTING, "version:1\n");
        assertEquals(EXISTING, merged);
    }

    @Test
    void resourcePacksAreEnabledAndRemovedFromIncompatible() {
        String merged = KeybindMerger.ensureResourcePacks(EXISTING, List.of("file/New.zip", "file/Faithful.zip"));
        assertTrue(merged.contains("file/New.zip"));
        assertTrue(merged.contains("file/Faithful.zip"));
        // New.zip 原本在 incompatible 列表里，应该被移除，否则材质不会启用
        String incompatibleLine = merged.lines()
                .filter(line -> line.startsWith("incompatibleResourcePacks:"))
                .findFirst()
                .orElseThrow();
        assertFalse(incompatibleLine.contains("New.zip"), "被注入的包必须从 incompatible 里移除：" + incompatibleLine);
        // 原有资源包不能被丢掉
        assertTrue(merged.contains("file/Old.zip"));
    }

    @Test
    void resourcePacksLineIsAddedWhenAbsent() {
        String merged = KeybindMerger.ensureResourcePacks("version:3953\n", List.of("file/New.zip"));
        assertTrue(merged.contains("resourcePacks:[\"file/New.zip\"]"));
    }

    @Test
    void listParsingAndSerialisingRoundTrips() {
        assertEquals(List.of("vanilla", "file/a b.zip"), KeybindMerger.parseList("[\"vanilla\",\"file/a b.zip\"]"));
        assertEquals("[\"a\",\"b\"]", KeybindMerger.toJson(List.of("a", "b")));
    }
}
