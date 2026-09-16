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
| [blockdetector](mods/blockdetector) | 高亮玩家周围指定范围内被配置的方块 | `pwsh ./scripts/build-mod.ps1 blockdetector` |
| [floatingexcavation](mods/floatingexcavation) | 移除完全浸没在水中或悬空时的挖掘速度惩罚 | `pwsh ./scripts/build-mod.ps1 floatingexcavation` |
| [fluixcompat](mods/fluixcompat) | AE2 水晶（fluix）材料兼容：配方 / 标签 + 材料 Mixin | `pwsh ./scripts/build-mod.ps1 fluixcompat` |
| [constructionwand](mods/constructionwand) | Construction Wand 的 1.21.1 构建：可扩展的建造 / 破坏法杖 | `pwsh ./scripts/build-mod.ps1 constructionwand` |
| [configpatcher](mods/configpatcher/README.md) | 条件式改写其它 mod 的配置；同时是 Java Agent，启动前把 mod / 资源包 / 键位注入到实例 | `pwsh ./scripts/build-mod.ps1 configpatcher` |

## 构建

```bash
cd mods/<modname>
./gradlew build        # 编译 + 单元测试，产物在 build/libs/
```

要求 JDK 21。首次构建需要联网拉取 NeoForge 依赖。

## 只操作某一个 mod

多个 mod 之间互不干扰：改 A 的代码不会碰到 B，构建和测试也可以只跑 A。

```powershell
# 只构建某一个 mod（含单元测试）
pwsh ./scripts/build-mod.ps1 configpatcher

# 只跑单元测试
pwsh ./scripts/build-mod.ps1 configpatcher -TestOnly

# 只编译打包，跳过测试
pwsh ./scripts/build-mod.ps1 configpatcher -SkipTests

# 不填名字就是构建全部 mod
pwsh ./scripts/build-mod.ps1
```

也可以直接进目录用 Gradle：

```bash
cd mods/configpatcher
./gradlew build
```

提交时只暂存那一个 mod 的改动：

```bash
git add mods/configpatcher
git commit -m "fix(configpatcher): 修正 xxx"
```

CI 侧同样按 mod 隔离：

| Job | 做什么 |
|---|---|
| `verify-version` | 快速校验所有 mod 的版本约束（十几秒）|
| `list-mods` | 自动发现 `mods/` 下有哪些 mod（**新增 mod 不用改 CI 文件**）|
| `build` | 每个 mod 一个**并行** job，`fail-fast: false` —— 某个 mod 编译失败不会掩盖其它 mod 的结果 |

## 新增一个 mod

见 [docs/adding-a-mod.md](docs/adding-a-mod.md)。核心三条：

1. 在 `mods/` 下新建目录，放一个**独立**的 Gradle 工程（不要嵌套共用 `settings.gradle`）；
2. `gradle.properties` 必须写 `minecraft_version=1.21.1`、`neoforge_version=21.1.x`；
3. 跑 `pwsh ./scripts/verify-version.ps1` 自查通过后再提交。

## 许可

默认 MIT（见 [LICENSE](LICENSE)）；某个 mod 若在其目录内单独声明了许可，以该声明为准。
