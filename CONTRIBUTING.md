# 贡献说明

本仓库的一个硬性前提：**只收 NeoForge 1.21.1 的 mod**。

## 版本约束（会被 CI 拦下）

每个 mod 的 `mods/<名称>/gradle.properties` 必须满足：

```properties
minecraft_version=1.21.1
neoforge_version=21.1.<任意>
```

- `minecraft_version` 必须**精确等于** `1.21.1`（不接受 `1.21`、`1.21.2`、`[1.21,1.21.1)` 之类的区间值）；
- `neoforge_version` 必须以 `21.1.` 开头；
- 不满足时 CI 会失败，并在日志里指出是哪个 mod、哪个字段。

提交前本地自查（Windows / Linux 都可，需要 pwsh）：

```powershell
pwsh ./scripts/verify-version.ps1
```

## 目录约定

- 一个 mod 一个目录：`mods/<modid>/`，目录名用该 mod 的 `mod_id`（小写、下划线）；
- 每个 mod 是**独立 Gradle 工程**：自带 `settings.gradle`、`gradle.properties`、`gradlew`；
- 不要在仓库根放 `settings.gradle` 去 include 各个 mod —— 保持「一个 mod 坏了不影响其它 mod」；
- 构建产物、`.gradle/`、`build/`、`runs/` 一律不提交（见根 `.gitignore`）。

## 只改一个 mod

各 mod 相互独立，改一个不需要动其它 mod，也不需要动根目录或 CI 配置：

```powershell
pwsh ./scripts/build-mod.ps1 <modid>            # 只构建这一个
pwsh ./scripts/build-mod.ps1 <modid> -TestOnly  # 只跑它的单元测试
```

提交时只暂存该 mod 目录：

```bash
git add mods/<modid>
git commit -m "fix(<modid>): 说明改了什么"
```

CI 会自动发现 `mods/` 下所有 mod 并**并行**构建，所以新增或修改单个 mod 都不用编辑 `.github/workflows/` 里的文件。

## 代码与提交

- 提交信息用中文，首行动词前缀（`feat:` / `fix:` / `docs:` / `refactor:` / `test:` / `chore:`），需要时正文补充原因；
- 涉及用户本机路径（游戏目录、mods 目录）的内容，一律用占位符或示例路径，不要把个人绝对路径写进代码、文档或模板；
- 新增或修改功能时，同步更新该 mod 目录下的 `README.md`；
- 单元测试是必须的：纯逻辑（解析、转换、合并、校验）都要能在不开游戏的情况下被测试覆盖。

## 新增 mod

完整步骤见 [docs/adding-a-mod.md](docs/adding-a-mod.md)。
