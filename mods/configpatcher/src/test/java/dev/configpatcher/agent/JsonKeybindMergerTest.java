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
}
