package dev.configpatcher.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonKeybindMergerTest {

    private static final String SAMPLE = """
            {
              "Generic": {
                "entityDataSync": {
                  "enabled": false,
                  "hotkey": {
                    "keys": "LEFT_CONTROL"
                  }
                },
                "freeCameraPlayerInputs": {
                  "enabled": false,
                  "hotkey": {
                    "keys": ""
                  }
                }
              },
              "Tweaks": {
                "fastPlacement": {
                  "enabled": true,
                  "hotkey": {
                    "keys": "X,C"
                  }
                }
              }
            }
            """;

    private static final String TARGET = """
            {
              "Generic": {
                "entityDataSync": {
                  "enabled": true,
                  "hotkey": {
                    "keys": "LEFT_SHIFT"
                  }
                },
                "freeCameraPlayerInputs": {
                  "enabled": true,
                  "hotkey": {
                    "keys": "Y"
                  }
                },
                "somethingElse": 3
              },
              "Tweaks": {
                "fastPlacement": {
                  "enabled": false,
                  "hotkey": {
                    "keys": "Z"
                  }
                }
              }
            }
            """;

    @Test
    void collectsKeysKeyedByFullPath() {
        Map<String, String> keys = JsonKeybindMerger.collect(SAMPLE);
        assertEquals("LEFT_CONTROL", keys.get("Generic>entityDataSync>hotkey>keys"));
        assertEquals("", keys.get("Generic>freeCameraPlayerInputs>hotkey>keys"));
        assertEquals("X,C", keys.get("Tweaks>fastPlacement>hotkey>keys"));
    }

    @Test
    void mergeOnlyTouchesKeyLines() {
        String merged = JsonKeybindMerger.merge(TARGET, SAMPLE);
        assertTrue(merged.contains("\"keys\": \"LEFT_CONTROL\""), "应按样本改写键位");
        assertTrue(merged.contains("\"keys\": \"X,C\""), "应按样本改写键位");
        assertTrue(merged.contains("\"keys\": \"\""), "样本里为空的热键应被清空");
        assertTrue(merged.contains("\"somethingElse\": 3"), "非键位内容必须保留");
        assertTrue(merged.contains("\"enabled\": true") || merged.contains("\"enabled\": false"),
                "enabled 等开关不能被样本覆盖");
    }

    @Test
    void sameFunctionNameInDifferentSectionsIsDistinguished() {
        String sample = """
                {
                  "A": {
                    "thing": {
                      "hotkey": {
                        "keys": "1"
                      }
                    }
                  },
                  "B": {
                    "thing": {
                      "hotkey": {
                        "keys": "2"
                      }
                    }
                  }
                }
                """;
        String target = sample.replace("\"keys\": \"1\"", "\"keys\": \"x\"")
                .replace("\"keys\": \"2\"", "\"keys\": \"y\"");
        String merged = JsonKeybindMerger.merge(target, sample);
        assertFalse(merged.contains("\"keys\": \"x\""), "A 段应被改成 1");
        assertFalse(merged.contains("\"keys\": \"y\""), "B 段应被改成 2");
        assertTrue(merged.contains("\"keys\": \"1\""));
        assertTrue(merged.contains("\"keys\": \"2\""));
    }

    @Test
    void emptySampleLeavesTargetAlone() {
        assertEquals(TARGET, JsonKeybindMerger.merge(TARGET, "{}\n"));
    }

    /** 复刻 2026-09-26 的事故：新实例首次启动时目标文件还不存在。 */
    @Test
    void missingTargetAdoptsSampleWholesale() {
        String merged = JsonKeybindMerger.merge("", SAMPLE);
        assertEquals(SAMPLE, merged, "目标不存在时应整份采用样本，而不是输出一个空文件");
        assertTrue(merged.lines().filter(line -> line.trim().startsWith("\"keys\"")).count() >= 3,
                "采用样本后必须真的带着 keys 行");
    }

    @Test
    void blankOrBrokenTargetAdoptsSampleWholesale() {
        assertEquals(SAMPLE, JsonKeybindMerger.merge("\n", SAMPLE));
        assertEquals(SAMPLE, JsonKeybindMerger.merge("   ", SAMPLE));
        // 半截 JSON：正是上次事故留下的那种坏文件
        assertEquals(SAMPLE, JsonKeybindMerger.merge("{\n  \"Generic\": {\n", SAMPLE));
    }

    @Test
    void targetWithoutKeyLinesAlsoAdoptsSample() {
        // 合法 JSON、但没有一条 keys 行 —— 同样无从逐行替换
        assertEquals(SAMPLE, JsonKeybindMerger.merge("{\n  \"other\": 1\n}\n", SAMPLE));
    }

    @Test
    void canPatchInPlaceOnlyForHealthyTargets() {
        assertTrue(JsonKeybindMerger.canPatchInPlace(TARGET));
        assertFalse(JsonKeybindMerger.canPatchInPlace(""));
        assertFalse(JsonKeybindMerger.canPatchInPlace("{}\n"));
        assertFalse(JsonKeybindMerger.canPatchInPlace("{\"a\": 1\n"));
    }
}
