# #146 任务串行真机验收 · 第二轮 A：定时调度入口「到点时已有运行 → 跳过」
#
# 断言：间隔档（scheduleWithFixedDelay）到点时任务正在运行 → 调度跳过，不产生运行行，只记一条日志。
# 做法：建一个间隔 10s 的自建任务（链首 HTTP_CALL 打本机桩端点），启用即登记调度（初始延迟 5s）；
#       随手动跑一次（桩端点 8s），让第一次到点落在这次运行里。
#
# 前置：网关 8080、桩端点 18080 且 **delay 为 8000ms**（restart：node tools/shadow-site/slow-http.cjs 18080 8000）。
# 用法：powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\shadow-site\verify-146-schedule.ps1 `
#           [-AppLog <网关 stdout 日志文件>]

param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$SiteId = '1',
    [string]$DsName = 'verify-146-http',
    [string]$AppLog = 'D:\workspace\programs\edge-gateway\.scratch\146\app.log'
)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0
$script:BaseUrl = $BaseUrl

# 共用的 Check / Api（显式 HttpWebRequest，见 helper 里的说明）
. (Join-Path $PSScriptRoot 'verify-146-http-helper.ps1')

function RunCount([string]$jobId) {
    return ((Api GET "/api/history/runs?jobId=$jobId" $null).data.total)
}

Write-Host '#146 验收 · 调度入口到点跳过'

$dsId = ((Api GET '/api/data-sources' $null).data | Where-Object { $_.name -eq $DsName } | Select-Object -First 1).id
Check '取到桩端点数据源' ($null -ne $dsId) ("dsId=$dsId")

$job = Api POST '/api/jobs' @{
    name = "verify-146-sched-$([guid]::NewGuid().ToString('N').Substring(0,8))"
    siteId = [long]$SiteId; direction = 'NORTH'; scheduleType = 'INTERVAL'
    intervalValue = 1; intervalUnit = 'MINUTES'; retryCount = 0; backoffMs = 200; remarks = 'verify #146 schedule'
}
$jobId = $job.data.id
Check '夹具任务已建（拿到 id）' ($null -ne $jobId) ("resp=$($job | ConvertTo-Json -Compress -Depth 4)")
if ($null -eq $jobId) { throw '夹具任务没建起来，后续断言没有意义' }
Api POST "/api/jobs/$jobId/actions" @{
    name = 'slow-call'; type = 'HTTP_CALL'; index = 1
    addition = @{ dataSourceId = [long]$dsId; path = '/hook/146'; method = 'POST' }
} | Out-Null

$logMark = 0
if (Test-Path $AppLog) { $logMark = @(Get-Content $AppLog -ErrorAction SilentlyContinue).Count }

# 启用即登记调度（JobScheduler.INITIAL_DELAY_MS = 5s）
$enabled = Api PUT "/api/jobs/$jobId/status" @{ status = 1 }
Check '调度夹具任务已启用' ($enabled.data.status -eq 1) ("resp=$($enabled | ConvertTo-Json -Compress)")

$before = RunCount $jobId
$start = Get-Date

# 手动跑一次（桩端点 8s）：异步发、不等待，让它的运行窗口盖住调度的第一次到点。
# 这一处刻意用 WebClient.UploadStringAsync：它是「不阻塞地发一次 POST 并让它在后台跑完」，
# 而 helper 那套同步读取要拿错误响应体，会在派发点就把 8 秒等完（那就盖不住调度到点了）。
$client = New-Object System.Net.WebClient
$client.Encoding = [System.Text.Encoding]::UTF8
$client.Headers['Content-Type'] = 'application/json; charset=utf-8'
$client.UploadStringAsync([Uri]"$BaseUrl/api/jobs/$jobId/execute", 'POST', '{}') | Out-Null

# 等到 t=9s：调度初始延迟 5s 已过，若它没跳过，这里就该看到第二行运行
while (((Get-Date) - $start).TotalSeconds -lt 9) { Start-Sleep -Milliseconds 300 }
$mid = RunCount $jobId
Check '调度到点时任务正在运行：job_run 未因调度多出一行' ($mid -eq ($before + 1)) ("before=$before mid=$mid")

# 等到 t=16s：手动那次（8s）早已结束，第二个 10s 到点还没到，仍应只有一行
while (((Get-Date) - $start).TotalSeconds -lt 16) { Start-Sleep -Milliseconds 300 }
$final = RunCount $jobId
Check '整段窗口内没有第二个运行' ($final -eq ($before + 1)) ("final=$final")

# 等到 t=22s：下一次到点按固定延迟在 t≈65s（间隔档最小单位是分钟），此刻仍应只有一行
while (((Get-Date) - $start).TotalSeconds -lt 22) { Start-Sleep -Milliseconds 300 }
$mid2 = RunCount $jobId
Check 't=22s 仍只有一行（第二次到点在 t≈65s，尚未到）' ($mid2 -eq ($before + 1)) ("mid2=$mid2")

# 等到 t=80s：第二次到点应当真的跑起来 —— 反向证明调度没被改坏，跳过只发生在「已有运行」那一刻
while (((Get-Date) - $start).TotalSeconds -lt 80) { Start-Sleep -Milliseconds 500 }
$nextFire = RunCount $jobId
Check '调度并未被改坏：下一次到点（t≈65s）照常执行' ($nextFire -ge ($before + 2)) ("mid2=$mid2 nextFire=$nextFire")

if (Test-Path $AppLog) {
    $tail = @(Get-Content $AppLog -ErrorAction SilentlyContinue | Select-Object -Skip $logMark)
    $skip = @($tail | Where-Object { $_ -match '跳过本次调度触发' -and $_ -match "job=$jobId" })
    Check '日志里有一条「跳过本次调度触发」（含 jobId）' ($skip.Count -ge 1) ("matched=$($skip.Count)")
    foreach ($line in $skip | Select-Object -First 2) { Write-Host ("    日志：{0}" -f $line.Trim()) }
} else {
    Write-Host ("    （未提供网关日志文件 {0}，跳过日志断言）" -f $AppLog)
}

Api DELETE "/api/jobs/$jobId" $null | Out-Null
Write-Host ("  已删测试任务 id={0}" -f $jobId)
Write-Host ''
Write-Host ("结果：通过 {0}，失败 {1}" -f $script:pass, $script:fail)
if ($script:fail -gt 0) { exit 1 }
