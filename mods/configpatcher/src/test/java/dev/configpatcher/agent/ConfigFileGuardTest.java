package dev.configpatcher.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置文件内容的统一检查（导出方向与注入方向共用）。
 *
 * <p>这组用例守的是两类真实事故：
 * <ul>
 *     <li>2026-09-24：崩溃退出时实例里只剩一个换行符，被整份回写成样本，样本从 17 条键位退化成 1 字节；</li>
 *     <li>2026-09-26：新实例首次启动时目标文件不存在，合并器输出 1 个换行符写进目标，
 *         目标 mod 读不懂，键位被整体打回默认配置。</li>
 * </ul>
 */
class ConfigFileGuardTest {

    /** 造一份 keys 行数达标的正常键位文件。 */
    private static String healthyKeybindSample() {
        StringBuilder sb = new StringBuilder("{\n  \"GenericHotkeys\": {\n");
        sb.append("    \"first\": {\n      \"keys\": \"LEFT_CONTROL\"\n    }");
        for (int i = 0; i < 120; i++) {
            sb.append(",\n    \"pad").append(i).append("\": {\n      \"keys\": \"\"\n    }");
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }

    @Test
    void blocksTinyOrBlankContent() {
        assertNotNull(ConfigFileGuard.rejectReason("config/tweakeroo.json", true, "\n"));
        assertNotNull(ConfigFileGuard.rejectReason("config/tweakeroo.json", true, ""));
        assertNotNull(ConfigFileGuard.rejectReason("config/tweakeroo.json", true, "   \n  "));
    }

    @Test
    void blocksBrokenJson() {
        assertNotNull(ConfigFileGuard.rejectReason("config/tweakeroo.json", true, "这不是 JSON"));
        assertNotNull(ConfigFileGuard.rejectReason("config/tweakeroo.json", true, "{\"a\": 1"));
        assertNotNull(ConfigFileGuard.rejectReason("config/tweakeroo.json", true, "{\"a\": \"未闭合}"));
    }

    @Test
    void blocksKeybindJsonWithTooFewKeys() {
        String thin = "{\n  \"GenericHotkeys\": {\n    \"first\": {\n      \"keys\": \"LEFT_CONTROL\"\n    }\n  }\n}\n";
        String reason = ConfigFileGuard.rejectReason("config/tweakeroo.json", true, thin);
        assertNotNull(reason);
        assertTrue(reason.contains("keys"), reason);
    }

    @Test
    void allowsSmallNonKeybindJson() {
        // recipe_type_names.json 只有 1.8 KB、也没有 keys 行，不能被 keys 检查误伤
        String recipe = "{\n  \"extendedae_plus:assembler\": \"组装机\",\n  \"gtceu:assembler\": \"组装机\"\n}\n";
        assertNull(ConfigFileGuard.rejectReason("config/extendedae_plus/recipe_type_names.json", false, recipe));
        // 同一份内容当成键位文件就该被拦下
        assertNotNull(ConfigFileGuard.rejectReason("config/recipe_type_names.json", true, recipe));
    }

    @Test
    void allowsHealthyKeybindSample() {
        String healthy = healthyKeybindSample();
        assertNull(ConfigFileGuard.rejectReason("config/tweakeroo.json", true, healthy));
        assertTrue(ConfigFileGuard.isUsable("config/tweakeroo.json", true, healthy));
        assertFalse(ConfigFileGuard.isUsable("config/tweakeroo.json", true, "\n"));
    }

    @Test
    void mergedResultCheckAllowsSmallTargets() {
        // 目标本来就是个小文件：合并结果只有 2 行 keys，也不能被「≥100 行」的门槛误伤
        String small = "{\n  \"GenericHotkeys\": {\n    \"a\": {\n      \"keys\": \"LEFT_CONTROL\"\n    },\n"
                + "    \"b\": {\n      \"keys\": \"\"\n    }\n  }\n}\n";
        assertNull(ConfigFileGuard.rejectMergedResult("config/tweakeroo.json", small));
        // 结构坏了 / 一条 keys 行都没有，就要拦下
        assertNotNull(ConfigFileGuard.rejectMergedResult("config/tweakeroo.json", "\n"));
        assertNotNull(ConfigFileGuard.rejectMergedResult("config/tweakeroo.json", "{\n  \"other\": 1\n}\n"));
        assertNotNull(ConfigFileGuard.rejectMergedResult("config/tweakeroo.json", "{\"a\": 1"));
    }

    @Test
    void mergedResultCheckIgnoresKeyRuleForNonJson() {
        // options.txt 这类文本文件只做「非空」检查
        assertNull(ConfigFileGuard.rejectMergedResult("options.txt", "key_key.attack:key.keyboard.f\n"));
        assertNotNull(ConfigFileGuard.rejectMergedResult("options.txt", "   \n"));
    }

    @Test
    void balancedDetectsTruncatedJson() {
        assertTrue(ConfigFileGuard.balanced("{\"a\": [1, 2], \"b\": \"含\\\"转义\"}"));
        assertFalse(ConfigFileGuard.balanced("{\"a\": [1, 2"));
        assertFalse(ConfigFileGuard.balanced("{\"a\": \"未闭合}"));
        assertFalse(ConfigFileGuard.balanced("}"));
    }
}
