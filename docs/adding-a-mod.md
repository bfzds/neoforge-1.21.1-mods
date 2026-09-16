# 新增一个 mod

本仓库是「多 mod 单仓库」，每个 mod 都是一个**独立 Gradle 工程**。新增 mod 不需要改动根目录的任何文件。

## 一、目录与命名

```
mods/<modid>/          ← modid 必须与 mods.toml 里的 modId 一致，小写 + 下划线
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}
├── README.md          ← 这个 mod 自己怎么用
└── src/
    ├── main/java/...
    ├── main/resources/META-INF/neoforge.mods.toml
    └── test/java/...
```

要点：

- **不要**在仓库根写 `settings.gradle` 去 include 各个 mod —— 保持每个 mod 能单独构建、单独坏；
- 构建产物（`build/`、`.gradle/`、`runs/`）不提交，根 `.gitignore` 已覆盖；
- 不要复制别的 mod 的 `gradle.properties` 后忘记改 `mod_id` / `mod_group_id`。

## 二、版本约束（硬性）

`gradle.properties` 必须包含：

```properties
minecraft_version=1.21.1
neoforge_version=21.1.216
```

- `minecraft_version` 精确等于 `1.21.1`；
- `neoforge_version` 以 `21.1.` 开头（可换成更新的 21.1.x 补丁版本）。

CI 会读这两个字段，不满足直接失败。

## 三、推荐的最小 `build.gradle`

```groovy
plugins {
    id 'java-library'
    id 'eclipse'
    id 'idea'
    id 'net.neoforged.moddev' version '2.0.107'
}

version = mod_version
group = mod_group_id

base { archivesName = mod_id }

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

repositories {
    mavenLocal()
    mavenCentral()
}

neoForge {
    version = project.neoforge_version
    runs {
        client { client() }
        server { server(); programArgument '--nogui' }
        configureEach { systemProperty 'forge.logging.markers', 'REGISTRIES' }
    }
    mods {
        "${mod_id}" { sourceSet(sourceSets.main) }
    }
}

dependencies {
    testImplementation "org.junit.jupiter:junit-jupiter:5.10.2"
    testRuntimeOnly "org.junit.platform:junit-platform-launcher"
}

tasks.named('processResources', ProcessResources).configure {
    def replaceProperties = [mod_id: mod_id, mod_name: mod_name, mod_version: mod_version,
                             mod_license: mod_license, mod_authors: mod_authors,
                             mod_description: mod_description,
                             minecraft_version: minecraft_version, neoforge_version: neoforge_version]
    inputs.properties replaceProperties
    filesMatching(['META-INF/neoforge.mods.toml']) { expand replaceProperties }
}

tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8' }
tasks.named('test', Test).configure { useJUnitPlatform() }
```

`settings.gradle`：

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven { url = "https://maven.neoforged.net/releases" }
    }
}
rootProject.name = "<modid>"
```

## 四、Gradle wrapper

从已有的 mod 目录复制 `gradlew`、`gradlew.bat`、`gradle/wrapper/` 三样即可，保持 Gradle 版本一致。

## 五、提交前检查清单

1. `gradle.properties` 里 `minecraft_version=1.21.1`、`neoforge_version=21.1.x`；
2. 跑 `pwsh ./scripts/verify-version.ps1` 通过；
3. `cd mods/<modid> && ./gradlew build` 通过（含单元测试）；
4. 该 mod 目录下有 `README.md`，写清楚怎么用、依赖什么；
5. 没有把个人绝对路径（游戏目录、mods 目录）写进代码、文档或模板——用占位符或示例路径；
6. 根 `README.md` 的「现有 mod」表格里补一行。
