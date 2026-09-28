package dev.configpatcher.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 构建版本标识：测试类路径上应能读到打包时生成的 build-info.properties。 */
class VersionTest {

    @Test
    void describeContainsVersionAndBuildTime() {
        String describe = Version.describe();
        assertTrue(describe.contains("构建"), describe);
        assertFalse(describe.startsWith("unknown"), "build-info.properties 缺失？" + describe);
    }
}
