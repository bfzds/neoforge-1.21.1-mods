#!/usr/bin/env pwsh
# 构建（并可测试）指定 mod，不填则构建全部。
#
# 用法：
#   pwsh ./scripts/build-mod.ps1                           构建全部 mod
#   pwsh ./scripts/build-mod.ps1 configpatcher              只构建 configpatcher
#   pwsh ./scripts/build-mod.ps1 configpatcher -TestOnly    只跑单元测试
#   pwsh ./scripts/build-mod.ps1 configpatcher -SkipTests   只编译打包，跳过测试
#
# 说明：PowerShell 变量名大小写不敏感，所以参数用 $ModName、循环变量用 $modDir，
#       避免互相覆盖（也别用 $IsWindows 这类内置只读变量）。
param(
    [string]$ModName = '',
    [switch]$TestOnly,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'

$root    = Split-Path -Parent $PSScriptRoot
$modsDir = Join-Path $root 'mods'

$all = @(Get-ChildItem -LiteralPath $modsDir -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'gradle.properties') } |
    Sort-Object Name)

if ($ModName) {
    $targets = @($all | Where-Object { $_.Name -eq $ModName })
    if ($targets.Count -eq 0) {
        Write-Host "找不到 mod「$ModName」。可用：" -ForegroundColor Red
        $all | ForEach-Object { Write-Host "  - $($_.Name)" }
        exit 1
    }
} else {
    $targets = $all
}

$onWindows  = ($env:OS -eq 'Windows_NT')
$gradlewExe = if ($onWindows) { 'gradlew.bat' } else { 'gradlew' }

# 必须是数组：PowerShell 的 splatting（@tasks）遇到字符串会按字符拆开，
# 曾经因此把 'test' 传成 't'，被 Gradle 判成模糊任务名。
$tasks = @('build')
if ($TestOnly) { $tasks = @('test') }
elseif ($SkipTests) { $tasks = @('build', '-x', 'test') }

$failed = [System.Collections.Generic.List[string]]::new()
foreach ($modDir in $targets) {
    $gradlew = Join-Path $modDir.FullName $gradlewExe
    if (-not (Test-Path -LiteralPath $gradlew)) {
        Write-Host "跳过 $($modDir.Name)：找不到 $gradlewExe" -ForegroundColor Yellow
        $failed.Add($modDir.Name)
        continue
    }
    if (-not $onWindows) {
        & chmod +x $gradlew
    }

    Write-Host ""
    Write-Host "=== $($modDir.Name) ===" -ForegroundColor Cyan
    Push-Location -LiteralPath $modDir.FullName
    try {
        & $gradlew @tasks --console=plain
        if ($LASTEXITCODE -ne 0) { $failed.Add($modDir.Name) }
    } finally {
        Pop-Location
    }
}

Write-Host ""
if ($failed.Count -gt 0) {
    Write-Host "以下 mod 构建失败：$($failed -join '、')" -ForegroundColor Red
    exit 1
}
Write-Host "全部完成：$($targets.Count) 个 mod" -ForegroundColor Green
exit 0
