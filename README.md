# NeoForge 1.21.1 Mods

只收 **NeoForge 1.21.1** mod 的仓库。每个 mod 是一个**独立 Gradle 工程**，彼此不共享依赖版本、互不影响；新增 mod 不需要改动根目录的任何文件。

## 硬性约束（由 CI 强制）

| 项目 | 要求 |
|---|---|
| Minecraft | **1.21.1** —— `minecraft_version=1.21.1` |
| NeoForge | **21.1.x** —— `neoforge_version=21.1.*` |
| Java | 21 |
| 构建插件 | ModDevGradle（`net.neoforged.moddev`）|

任何 mod 不满足上表，CI（`.github/workflows/verify-neoforge-version.yml` → `scripts/verify-version.ps1`）直接失败。
本地提交前先自查：

```powershell
pwsh ./scripts/verify-version.ps1
```

## 目录结构

```
mods/
└── configpatcher/            一个 NeoForge 1.21.1 mod（独立 Gradle 工程）
    ├── build.gradle
    ├── settings.gradle
    ├── gradle.properties     ← 版本约束就写在这里，CI 读它
    ├── gradlew / gradlew.bat
    └── src/{main,test}/...
docs/adding-a-mod.md          新增 mod 的完整步骤
scripts/verify-version.ps1    版本约束自查脚本（本地与 CI 共用）
```

## 现有 mod

| Mod | 一句话说明 | 构建 |
|---|---|---|
| [configpatcher](mods/configpatcher/README.md) | 条件式改写其它 mod 的配置；同时是一个 Java Agent，在启动前把 mod / 资源包 / 键位注入到实例 | `cd mods/configpatcher && ./gradlew build` |

## 构建

```bash
cd mods/<modname>
./gradlew build        # 编译 + 单元测试，产物在 build/libs/
```

要求 JDK 21。首次构建需要联网拉取 NeoForge 依赖。

## 新增一个 mod

见 [docs/adding-a-mod.md](docs/adding-a-mod.md)。核心三条：

1. 在 `mods/` 下新建目录，放一个**独立**的 Gradle 工程（不要嵌套共用 `settings.gradle`）；
2. `gradle.properties` 必须写 `minecraft_version=1.21.1`、`neoforge_version=21.1.x`；
3. 跑 `pwsh ./scripts/verify-version.ps1` 自查通过后再提交。

## 许可

默认 MIT（见 [LICENSE](LICENSE)）；某个 mod 若在其目录内单独声明了许可，以该声明为准。
