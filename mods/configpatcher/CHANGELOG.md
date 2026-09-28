# 更新日志（Changelog）

本文件基于 [Keep a Changelog 1.1.0](https://keepachangelog.com/zh-CN/1.1.0/) 格式维护，
版本号遵循[语义化版本（SemVer）](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added
- JEI 查询历史栏（`lookupHistory.displaySide`）纳入启动注入，固定为靠右显示（JEI 默认左侧）。
- `options.txt` 的键位与声音（`soundCategory_*` / `soundDevice`）退出回流改为「只增改」合并：实例没有的键位保留全局原值，键位少的实例退出不再把全局样本砍短。
- 声音设置进入样本循环：注入方向与回流方向都携带 `soundCategory_*` / `soundDevice` 行。
- 构建版本标识：打包时生成 `build-info.properties`，Agent 启动日志与游戏内 `/configpatcher version` 显示构建版本与时间。
- 样本库备份保留策略：`.backup` 只保留最近 20 份，写备份时自动清理更旧的。
- `sync-presets.ps1`：发布前把样本库同步成 jar 内置预设。

### Changed
- 键位 JSON（`keybindFileOverrides`，如 tweakeroo.json）的退出回流从整份覆盖改为路径感知合并：以样本为基准，只更新实例改过的 keys 值；样本不存在或损坏时才整份采用实例（与旧行为一致的兜底）。
- jar 内置注入清单模板回归中性 `preset:` 口径，不再携带个人绝对路径；个人样本库路径只留在各实例的清单文件里。

## [0.1.0] - 2026-09-24

### Added
- 首个打包版本：Java Agent + NeoForge mod 双侧——启动前注入 mod / 资源包 / 键位 / 单键配置，退出时回流样本，`rules.json` 配置改写与游戏内 `/configpatcher` 命令。

[Unreleased]: https://github.com/bfzds/neoforge-1.21.1-mods/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/bfzds/neoforge-1.21.1-mods/releases/tag/v0.1.0
