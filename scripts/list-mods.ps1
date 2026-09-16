#!/usr/bin/env pwsh
# 列出 mods/ 下所有 mod 目录（只认含 gradle.properties 的目录）。
#
# 两种用法：
#   本地： pwsh ./scripts/list-mods.ps1          → 每行一个 mod 名
#   CI ： 由 GitHub Actions 调用，会额外往 $env:GITHUB_OUTPUT 写 mods=["a","b"]，
#          供 matrix 动态并行构建使用 —— 新增 mod 不需要改任何 CI 文件。
$ErrorActionPreference = 'Stop'

$root    = Split-Path -Parent $PSScriptRoot
$modsDir = Join-Path $root 'mods'

$mods = @()
if (Test-Path -LiteralPath $modsDir) {
    $mods = @(Get-ChildItem -LiteralPath $modsDir -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'gradle.properties') } |
        Sort-Object Name |
        Select-Object -ExpandProperty Name)
}

# 手工拼 JSON，避免 PowerShell 版本差异把单元素数组序列化成对象
$json = '[' + (($mods | ForEach-Object { '"' + $_ + '"' }) -join ',') + ']'

if ($env:GITHUB_OUTPUT) {
    "mods=$json" | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8
    Write-Host "发现 $($mods.Count) 个 mod：$json"
} else {
    foreach ($mod in $mods) { Write-Output $mod }
}
