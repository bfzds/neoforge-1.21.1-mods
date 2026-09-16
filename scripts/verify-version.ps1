#!/usr/bin/env pwsh
# 校验 mods/ 下每个 mod 是否符合“本仓库只收 NeoForge 1.21.1 mod”的约束。
# 本地与 CI 共用：pwsh ./scripts/verify-version.ps1
$ErrorActionPreference = 'Stop'

$root    = Split-Path -Parent $PSScriptRoot
$modsDir = Join-Path $root 'mods'

$requiredMinecraft = '1.21.1'
$neoforgePattern   = '^21\.1\.'

if (-not (Test-Path -LiteralPath $modsDir)) {
    Write-Host "找不到 mods/ 目录：$modsDir" -ForegroundColor Red
    exit 1
}

$errors  = [System.Collections.Generic.List[string]]::new()
$checked = 0

foreach ($mod in (Get-ChildItem -LiteralPath $modsDir -Directory | Sort-Object Name)) {
    $props = Join-Path $mod.FullName 'gradle.properties'
    if (-not (Test-Path -LiteralPath $props)) {
        $errors.Add("[$($mod.Name)] 缺少 gradle.properties")
        continue
    }

    $map = @{}
    foreach ($line in (Get-Content -LiteralPath $props -Encoding UTF8)) {
        if ($line -match '^\s*([A-Za-z0-9_.]+)\s*=\s*(.+?)\s*$') {
            $map[$Matches[1]] = $Matches[2]
        }
    }

    $minecraft = $map['minecraft_version']
    $neoforge  = $map['neoforge_version']
    $ok = $true

    if ($minecraft -ne $requiredMinecraft) {
        $errors.Add("[$($mod.Name)] minecraft_version=$minecraft，必须是 $requiredMinecraft")
        $ok = $false
    }
    if ($neoforge -notmatch $neoforgePattern) {
        $errors.Add("[$($mod.Name)] neoforge_version=$neoforge，必须是 21.1.x")
        $ok = $false
    }

    if ($ok) {
        Write-Host ("[OK]   {0}  minecraft={1}  neoforge={2}" -f $mod.Name, $minecraft, $neoforge) -ForegroundColor Green
    }
    $checked++
}

Write-Host ""
if ($errors.Count -gt 0) {
    Write-Host "版本约束校验失败 —— 本仓库只收 NeoForge $requiredMinecraft 的 mod：" -ForegroundColor Red
    foreach ($error in $errors) {
        Write-Host "  - $error" -ForegroundColor Red
    }
    exit 1
}

Write-Host "版本约束校验通过：$checked 个 mod 全部是 NeoForge $requiredMinecraft（neoforge 21.1.x）" -ForegroundColor Green
exit 0
