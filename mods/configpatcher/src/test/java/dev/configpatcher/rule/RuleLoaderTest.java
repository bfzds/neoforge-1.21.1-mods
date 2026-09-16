package dev.configpatcher.rule;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleLoaderTest {

    private static final String SHORT_FORM = """
            {
              "schemaVersion": 1,
              "enabled": true,
              "rules": [
                {
                  "id": "demo",
                  "target": "othermod",
                  "versionRange": "[1.0.0,)",
                  "configFile": "othermod-common.toml",
                  "configType": "COMMON",
                  "patches": {
                    "features.enableThing": false,
                    "limits.maxMachines": 256
                  }
                }
              ]
            }
            """;

    @Test
    void parsesShortFormPatches() {
        RuleSet ruleSet = RuleLoader.parse(SHORT_FORM, "test");
        assertEquals(1, ruleSet.rules().size());
        PatchRule rule = ruleSet.rules().get(0);
        assertEquals("demo", rule.id());
        assertEquals("othermod", rule.targetMod());
        assertTrue(rule.enabled());
        assertEquals(2, rule.entries().size());
        assertEquals(List.of("features", "enableThing"), rule.entries().get(0).segments());
        assertTrue(rule.matchesConfig("othermod-common.toml", "COMMON"));
        assertFalse(rule.matchesConfig("othermod-server.toml", "SERVER"));
        assertTrue(rule.matchesVersion("1.2.0"));
        assertFalse(rule.matchesVersion("0.9.0"));
    }

    @Test
    void parsesAliasesAndOptionalFlags() {
        String json = """
                {
                  "rules": [
                    {
                      "id": "renamed",
                      "target": "othermod",
                      "patches": [
                        {
                          "path": "features.enableThing",
                          "aliases": ["features.enable_thing", "features.enableThingNew"],
                          "optional": true,
                          "value": false
                        }
                      ]
                    }
                  ]
                }
                """;
        RuleSet ruleSet = RuleLoader.parse(json, "test");
        PatchEntry entry = ruleSet.rules().get(0).entries().get(0);
        assertTrue(entry.optional());
        assertTrue(entry.hasAliases());
        assertEquals(List.of("features.enableThing", "features.enable_thing", "features.enableThingNew"),
                entry.candidates());
    }

    @Test
    void ruleLevelSwitchWorks() {
        String json = """
                { "rules": [ { "id": "off", "target": "othermod", "enabled": false,
                  "patches": { "a.b": 1 } } ] }
                """;
        RuleSet ruleSet = RuleLoader.parse(json, "test");
        assertFalse(ruleSet.rules().get(0).enabled());
    }

    @Test
    void brokenRuleIsSkippedInsteadOfFailingEverything() {
        String json = """
                {
                  "rules": [
                    { "id": "no-target", "patches": { "a.b": 1 } },
                    { "id": "no-entries", "target": "othermod", "patches": {} },
                    { "id": "good", "target": "othermod", "patches": { "a.b": 1 } }
                  ]
                }
                """;
        RuleSet ruleSet = RuleLoader.parse(json, "test");
        assertEquals(1, ruleSet.rules().size());
        assertEquals("good", ruleSet.rules().get(0).id());
    }

    @Test
    void wholeFileSwitchIsRespected() {
        RuleSet ruleSet = RuleLoader.parse("{ \"enabled\": false, \"rules\": [] }", "test");
        assertFalse(ruleSet.enabled());
        assertTrue(ruleSet.isEmpty());
    }

    @Test
    void invalidJsonDegradesToEmptyRuleSet() {
        assertTrue(RuleLoader.parse("{ this is not json", "test").isEmpty());
    }

    @Test
    void targetModIndexIsCaseInsensitive() {
        RuleSet ruleSet = RuleLoader.parse(SHORT_FORM, "test");
        assertEquals(1, ruleSet.forMod("OtherMod").size());
        assertTrue(ruleSet.targetMods().contains("othermod"));
    }
}
