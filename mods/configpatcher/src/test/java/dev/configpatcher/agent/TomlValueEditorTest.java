package dev.configpatcher.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomlValueEditorTest {

    private static final String AE2 = """
            [client]
            	# 一些注释
            	somethingElse = true

            [terminals]
            	# The vertical margin to apply when sizing terminals.
            	# Default: 25
            	# Range: > -2147483648
            	terminalMargin = 25

            [search]
            	terminalMargin = 99
            """;

    private static final String JEI_INI = """
            [appearance]
            	someOption = true

            [cheating]
            	# 给予模式
            	giveMode = MOUSE_PICKUP
            	cheatToHotbarUsingHotkeysEnabled = false
            """;

    @Test
    void rewritesOnlyTheValueInsideTheSection() {
        TomlValueEditor.Result result = TomlValueEditor.set(AE2, "terminals.terminalMargin", "0");
        assertTrue(result.changed());
        assertTrue(result.content().contains("\tterminalMargin = 0"), "应保留 Tab 缩进并改值");
        assertTrue(result.content().contains("terminalMargin = 99"), "[search] 段里的同名键不能被误改");
        assertTrue(result.content().contains("somethingElse = true"), "其它内容必须保留");
        assertTrue(result.content().contains("# Default: 25"), "注释必须保留");
    }

    @Test
    void rewritesIniStyleKeyInBracketedSection() {
        TomlValueEditor.Result result = TomlValueEditor.set(JEI_INI, "cheating.giveMode", "INVENTORY");
        assertTrue(result.changed());
        assertTrue(result.content().contains("giveMode = INVENTORY"));
        assertTrue(result.content().contains("cheatToHotbarUsingHotkeysEnabled = false"));
    }

    @Test
    void unknownSectionOrKeyIsReportedAsUnchanged() {
        assertFalse(TomlValueEditor.set(AE2, "nope.terminalMargin", "0").changed());
        assertFalse(TomlValueEditor.set(AE2, "terminals.nope", "0").changed());
    }

    @Test
    void sameValueCountsAsUnchanged() {
        assertFalse(TomlValueEditor.set(AE2, "terminals.terminalMargin", "25").changed());
    }

    @Test
    void trailingCommentIsKept() {
        String content = "[a]\n\tvalue = 1 # keep me\n";
        TomlValueEditor.Result result = TomlValueEditor.set(content, "a.value", "2");
        assertTrue(result.changed());
        assertEquals("[a]\n\tvalue = 2 # keep me\n", result.content());
    }

    @Test
    void topLevelKeyWithoutSectionIsFound() {
        String content = "giveMode = MOUSE_PICKUP\nother = 1\n";
        TomlValueEditor.Result result = TomlValueEditor.set(content, "giveMode", "INVENTORY");
        assertTrue(result.changed());
        assertTrue(result.content().contains("giveMode = INVENTORY"));
    }

    @Test
    void multipleValueEditsCanBeAppliedInSequence() {
        TomlValueEditor.Result first = TomlValueEditor.set(AE2, "terminals.terminalMargin", "0");
        TomlValueEditor.Result second = TomlValueEditor.set(first.content(), "client.somethingElse", "false");
        assertTrue(second.changed());
        assertTrue(second.content().contains("\tterminalMargin = 0"));
        assertTrue(second.content().contains("\tsomethingElse = false"));
    }

    @Test
    void parseValueEditsFromSettings() {
        AgentInjector.Settings parsed = AgentInjector.parseSettings(List.of(
                "modSources=C:\\a",
                "valueEdits=config/ae2-client.toml:terminals.terminalMargin=0;config/jei/jei-client.ini:cheating.giveMode=INVENTORY"
        ));
        assertEquals(2, parsed.valueEdits().size());
        assertEquals("config/ae2-client.toml", parsed.valueEdits().get(0).file());
        assertEquals("terminals.terminalMargin", parsed.valueEdits().get(0).key());
        assertEquals("0", parsed.valueEdits().get(0).value());
        assertEquals("INVENTORY", parsed.valueEdits().get(1).value());
    }
}
