# 一次性安装本仓库的 git 钩子（提交信息守卫）。
#
#   powershell -ExecutionPolicy Bypass -File tools\install-hooks.ps1
#   或在已有会话里：
#       Set-ExecutionPolicy -Scope Process Bypass -Force
#       & .\tools\install-hooks.ps1
#
# 做三件事：
#   1. core.hooksPath 指到 .githooks —— 钩子跟着代码走，clone 下来跑一次即可。
#   2. 去掉 md / yml / sh 的 BOM（write 工具会写 BOM；sh 脚本带 BOM 会解释失败）。
#      **但 .ps1 必须保留 BOM**：本机是 Windows PowerShell 5.1，没有 BOM 的 UTF-8 脚本
#      会被按 GBK 读，中文变乱码、字符串字面量还会被截断（这个坑已经踩过一次）。
#   3. 自检：造一个带 BOM 的提交信息跑一遍钩子；PATH 上没有 sh 就跳过。
#
# 卸载：git config --unset core.hooksPath

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

git config core.hooksPath .githooks
Write-Host "[install-hooks] core.hooksPath = $(git config core.hooksPath)"

function Remove-Bom([string]$path) {
    if (-not (Test-Path $path)) {
        Write-Host "[install-hooks] 跳过（不存在）: $path"
        return
    }
    $bytes = [System.IO.File]::ReadAllBytes($path)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $rest = New-Object byte[] ($bytes.Length - 3)
        [Array]::Copy($bytes, 3, $rest, 0, $rest.Length)
        [System.IO.File]::WriteAllBytes($path, $rest)
        Write-Host "[install-hooks] 已去掉 BOM: $path"
    }
    else {
        Write-Host "[install-hooks] 无 BOM: $path"
    }
}

function Ensure-Bom([string]$path) {
    if (-not (Test-Path $path)) {
        Write-Host "[install-hooks] 跳过（不存在）: $path"
        return
    }
    $bytes = [System.IO.File]::ReadAllBytes($path)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        Write-Host "[install-hooks] BOM 已在: $path"
        return
    }
    $bom = [byte[]](0xEF, 0xBB, 0xBF)
    [System.IO.File]::WriteAllBytes($path, $bom + $bytes)
    Write-Host "[install-hooks] 已补回 BOM（PowerShell 5.1 需要）: $path"
}

# 不该带 BOM 的
$noBom = @(
    '.githooks\commit-msg'
    '.github\workflows\ci.yml'
    'AGENTS.md'
    'CODING_STANDARDS.md'
    'docs\agents\dev-environment.md'
    'docs\agents\token-budget.md'
)
foreach ($rel in $noBom) { Remove-Bom (Join-Path $repo $rel) }

# 必须带 BOM 的
$withBom = @(
    'tools\install-hooks.ps1'
    'tools\run-tests.ps1'
)
foreach ($rel in $withBom) { Ensure-Bom (Join-Path $repo $rel) }

# ---- 自检 ----
$hook = Join-Path $repo '.githooks\commit-msg'
$sh = Get-Command sh -ErrorAction SilentlyContinue
if (-not $sh) {
    Write-Host '[install-hooks] 跳过自检：PATH 上没有 sh（提交时 Git 会用自带的 sh 执行钩子）'
    return
}

$tmp = Join-Path $env:TEMP ('commit-msg-selftest-' + [guid]::NewGuid().ToString('N'))
$bom = [byte[]](0xEF, 0xBB, 0xBF)
$body = [System.Text.Encoding]::UTF8.GetBytes("fix(ci): 自检用的提交信息" + "`n")
[System.IO.File]::WriteAllBytes($tmp, $bom + $body)

& $sh.Source $hook $tmp
if ($LASTEXITCODE -ne 0) { throw '自检失败：钩子拒绝了合法格式的提交信息' }

$after = [System.IO.File]::ReadAllBytes($tmp)
if ($after.Length -ge 3 -and $after[0] -eq 0xEF) { throw '自检失败：BOM 没被剥掉' }
Remove-Item $tmp -Force
Write-Host '[install-hooks] 自检通过：BOM 已剥掉，格式校验通过'
