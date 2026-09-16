package dev.configpatcher.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureLedgerTest {

    @AfterEach
    void tearDown() {
        FailureLedger.clear();
    }

    @Test
    void recordedFailureIsSkippedAfterwards() {
        assertFalse(FailureLedger.isSkipped("rule-a", "features.enableThing"));
        FailureLedger.record("rule-a", "othermod", "othermod-common.toml", "features.enableThing",
                "目标配置里没有这个路径");
        assertTrue(FailureLedger.isSkipped("rule-a", "features.enableThing"));
        assertEquals(1, FailureLedger.size());
    }

    @Test
    void forgetRemovesSkipSoItCanBeRetried() {
        FailureLedger.record("rule-a", "othermod", "othermod-common.toml", "a.b", "找不到");
        FailureLedger.forget("rule-a", "a.b");
        assertFalse(FailureLedger.isSkipped("rule-a", "a.b"));
        assertEquals(0, FailureLedger.size());
    }

    @Test
    void failuresAreGroupedByMod() {
        FailureLedger.record("rule-a", "othermod", "f.toml", "a.b", "找不到");
        FailureLedger.record("rule-b", "anothermod", "g.toml", "c.d", "写入失败");
        List<FailureLedger.Failure> forOther = FailureLedger.forMod("OtherMod");
        assertEquals(1, forOther.size());
        assertEquals("a.b", forOther.get(0).path());
        assertTrue(forOther.get(0).shortLine().contains("othermod"));
    }

    @Test
    void repeatedFailureKeepsSingleEntryWithLatestReason() {
        FailureLedger.record("rule-a", "othermod", "f.toml", "a.b", "第一次");
        FailureLedger.record("rule-a", "othermod", "f.toml", "a.b", "第二次");
        assertEquals(1, FailureLedger.size());
        assertEquals("第二次", FailureLedger.failures().get(0).reason());
    }

    @Test
    void longReasonIsTruncatedForChat() {
        FailureLedger.record("rule-a", "othermod", "f.toml", "a.b", "x".repeat(500));
        String reason = FailureLedger.failures().get(0).reason();
        assertTrue(reason.length() <= 141, "聊天栏一行放不下：" + reason.length());
        assertTrue(reason.endsWith("…"));
    }

    @Test
    void clearRemovesBothFailureAndSkipState() {
        FailureLedger.record("rule-a", "othermod", "f.toml", "a.b", "找不到");
        FailureLedger.clear();
        assertEquals(0, FailureLedger.size());
        assertFalse(FailureLedger.isSkipped("rule-a", "a.b"));
    }
}
