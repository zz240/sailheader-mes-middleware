# #146 任务串行（并发互斥）真机验收脚本
#
# 口径来源：docs/features/reliability/README.md「任务串行」、docs/flows/reliability-flow.md ①-2。
# 断言四件事：
#   1) 手动执行：第一次运行期间第二次触发 → 409 + JOB_ALREADY_RUNNING，且 job_run 只多一行；
#   2) 外部 API Key 触发与手动执行共用同一张占用表：运行期间同样 409；
#   3) 运行结束后占用被释放，任务能再次正常执行；
#   4) 慢速目标端点恰好被请求三次（被跳过的那两次没有真发出去，不是「跑了两次只改写状态」）。
#
# 前置：docker 容器 edge-gateway-mysql 在跑、网关在 8080、慢速目标端点在 18080
#       （tools/shadow-site/slow-http.cjs [port] [delayMs]；它每一行 stdout 是一次请求记录）。
# 脚本自建一个「HTTP_CALL 打头」的任务与指向桩端点的数据源（数据源按名字复用），跑完删掉自建任务。
#
# 并发怎么造：第一次触发用 curl.exe 起**独立进程**发（本机 PowerShell 5.1 没有 Start-ThreadJob，
# 而 scriptblock 又跑不到 .NET 线程上）；主线程随后发第二次。curl 的 stdout / stderr 落盘后读回。
#
# 用法（本机执行策略会拦脚本文件，须带 Bypass）：
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\shadow-site\verify-146-serial.ps1 `
#       [-StubLog .scratch\146\stub.log]

param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$SiteId = '1',
    [string]$StubLog = '',
    [int]$RunMillis = 3000,
    [int]$LeadMs = 900
)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0
$curl = (Get-Command curl.exe -ErrorAction SilentlyContinue).Source
$workDir = Join-Path $PSScriptRoot '..\..\.scratch\146'

function Check([string]$name, [bool]$ok, [string]$detail = '') {
    if ($ok) {
        $script:pass++
        Write-Host ("  [PASS] {0}" -f $name)
    } else {
        $script:fail++
        Write-Host ("  [FAIL] {0} {1}" -f $name, $detail) -ForegroundColor Red
    }
}

# 统一把非 2xx 也变成可断言的对象（409 本身就是验收对象，不能让异常直接终止脚本）。
# 这里不用 Invoke-RestMethod 的 $_.ErrorDetails：PS 5.1 把错误响应体按 ANSI 解码，
# 信封里的中文会让 ConvertFrom-Json 直接失败（实测 code/message 都读成空）。改用 WebClient
# 显式取字节流并按 UTF-8 解码，状态码与信封两条都拿得到。
function Api([string]$method, [string]$path, $body) {
    $uri = "$BaseUrl$path"
    $json = if ($null -eq $body) { $null } else { $body | ConvertTo-Json -Depth 10 -Compress }
    $client = New-Object System.Net.WebClient
    $client.Encoding = [System.Text.Encoding]::UTF8
    $client.Headers['Content-Type'] = 'application/json; charset=utf-8'
    try {
        $raw = if ($null -eq $json) { $client.DownloadString($uri) } else { $client.UploadString($uri, $method, $json) }
        if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
        $parsed = $raw | ConvertFrom-Json
        if ($null -ne $parsed -and $parsed.PSObject.Properties.Name -contains 'data') {
            $parsed | Add-Member -NotePropertyName raw -NotePropertyValue $raw -Force
        }
        return $parsed
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        $status = if ($resp) { [int]$resp.StatusCode } else { 0 }
        $text = ''
        if ($resp) {
            $stream = $resp.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
            $text = $reader.ReadToEnd()
            $reader.Dispose()
        }
        $payload = $null
        if ($text) { try { $payload = $text | ConvertFrom-Json } catch { } }
        return [pscustomobject]@{
            __error = $true; status = $status
            code = $payload.code; message = $payload.message; raw = $text
        }
    } finally {
        $client.Dispose()
    }
}

# 起一次「后台触发」：curl 独立进程，stdout（-w 写的 HTTP 状态码）/ stderr 都落盘。
# 这里不用 Start-Process：本机环境同时存在 NO_PROXY 与 no_proxy，PS 5.1 的 Start-Process 构造
# 环境块时按大小写不敏感去重会抛 ArgumentException（实测），改用 .NET 的 Process 直接起。
function Invoke-CurlAsync([string]$name, [string]$url, [string[]]$headerPairs, [string]$body) {
    $out = Join-Path $workDir "$name.out.txt"
    $err = Join-Path $workDir "$name.err.txt"
    Remove-Item $out, $err -ErrorAction SilentlyContinue
    $argList = @('-s', '-o', 'NUL', '-w', '%{http_code}', '-X', 'POST', $url,
        '-H', 'Content-Type: application/json', '--max-time', '120')
    foreach ($h in $headerPairs) { $argList += @('-H', $h) }
    $argList += @('--data-binary', $body)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $curl
    $psi.Arguments = (($argList | ForEach-Object {
                if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
            }) -join ' ')
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $psi
    [void]$p.Start()
    # 先把两条流读起来：curl 要等运行结束才回状态码，同步读会一直阻塞到结束
    $stdoutTask = $p.StandardOutput.ReadToEndAsync()
    $stderrTask = $p.StandardError.ReadToEndAsync()
    return [pscustomobject]@{
        Name = $name; Proc = $p; Out = $out; Err = $err
        StdoutTask = $stdoutTask; StderrTask = $stderrTask
    }
}

# 等一次后台触发落地，读回它的 HTTP 状态码
function Wait-CurlAsync($handle) {
    $deadline = (Get-Date).AddSeconds(120)
    while (-not $handle.Proc.HasExited -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 200 }
    $status = "$($handle.StdoutTask.Result)".Trim()
    Set-Content -Path $handle.Out -Value $status -Encoding ASCII
    Set-Content -Path $handle.Err -Value $handle.StderrTask.Result -Encoding UTF8
    return [pscustomobject]@{ httpStatus = $status; curlExit = $handle.Proc.ExitCode }
}

Write-Host '#146 任务串行真机验收'
Write-Host ("base={0} site={1} runMillis={2}" -f $BaseUrl, $SiteId, $RunMillis)
New-Item -ItemType Directory -Force -Path $workDir | Out-Null

# ---------- 夹具 ----------
$dsName = 'verify-146-http'
$existing = (Api GET '/api/data-sources' $null).data | Where-Object { $_.name -eq $dsName } | Select-Object -First 1
if ($existing) {
    $dsId = $existing.id
    Write-Host ("  复用数据源 {0} id={1}" -f $dsName, $dsId)
} else {
    $ds = Api POST '/api/data-sources' @{
        name = $dsName; siteId = [long]$SiteId; type = 'HTTP'; address = 'http://127.0.0.1:18080'; addition = @{}
    }
    $dsId = $ds.data.id
    Write-Host ("  新建数据源 {0} id={1}" -f $dsName, $dsId)
}
Check '夹具数据源可用' ($null -ne $dsId)

$job = Api POST '/api/jobs' @{
    name = "verify-146-serial-$([guid]::NewGuid().ToString('N').Substring(0,8))"
    siteId = [long]$SiteId; direction = 'NORTH'; scheduleType = 'NONE'
    retryCount = 1; backoffMs = 200; remarks = 'verify #146'
}
$jobId = $job.data.id
Check '夹具任务已建' ($null -ne $jobId) ("resp=$($job | ConvertTo-Json -Compress)")

$action = Api POST "/api/jobs/$jobId/actions" @{
    name = 'slow-call'; type = 'HTTP_CALL'; index = 1
    addition = @{ dataSourceId = [long]$dsId; path = '/hook/146'; method = 'POST' }
}
Check '夹具动作已建（HTTP_CALL 打头，不涉及水位书签）' ($null -ne $action.data.id) ("resp=$($action | ConvertTo-Json -Compress)")

$enabled = Api PUT "/api/jobs/$jobId/status" @{ status = 1 }
Check '任务已启用' ($enabled.data.status -eq 1) ("resp=$($enabled | ConvertTo-Json -Compress)")

$runsBefore = ((Api GET "/api/history/runs?jobId=$jobId" $null).data.total)
$stubLinesBefore = 0
if ($StubLog -and (Test-Path $StubLog)) {
    $stubLinesBefore = @(Get-Content $StubLog | Where-Object { $_ -match '"seq"' }).Count
}

try {
    # ---------- 断言 1：运行期间第二次手动触发 → 409 ----------
    Write-Host '--- 断言 1：手动执行并发 ---'
    $execUrl = "$BaseUrl/api/jobs/$jobId/execute"
    $first = Invoke-CurlAsync 'first' $execUrl @() '{}'
    Start-Sleep -Milliseconds $LeadMs        # 让第一次运行真正进入 HTTP 调用
    $second = Api POST "/api/jobs/$jobId/execute" @{}
    $firstResult = Wait-CurlAsync $first

    Check '第一次触发正常完成（HTTP 200）' ($firstResult.httpStatus -eq '200') ("status=$($firstResult.httpStatus)")
    Check '第二次触发返回 409' ($second.status -eq 409) ("status=$($second.status)")
    Check '第二次触发业务码为 JOB_ALREADY_RUNNING' ($second.code -eq 'JOB_ALREADY_RUNNING') ("code=$($second.code)")
    Check '第二次触发文案为「任务正在执行，请稍后再试」' ($second.message -eq '任务正在执行，请稍后再试') ("message=$($second.message)")

    $runsAfter = ((Api GET "/api/history/runs?jobId=$jobId" $null).data.total)
    Check 'job_run 只多一行' (($runsAfter - $runsBefore) -eq 1) ("before=$runsBefore after=$runsAfter")

    # ---------- 断言 2：外部触发共用同一张占用表 ----------
    Write-Host '--- 断言 2：外部触发并发 ---'
    # 注意：每次调用都会重新生成并覆盖旧 key，只能取一次（取两次会让第一次的 key 当场失效）
    $apiKeyResp = Api POST "/api/sites/$SiteId/api-key" @{}
    $apiKey = $apiKeyResp.data.apiKey
    Check '取到站点 API Key' (-not [string]::IsNullOrWhiteSpace($apiKey)) ("resp=$($apiKeyResp.raw)")

    $extUrl = "$BaseUrl/api/external/jobs/$jobId/trigger"
    $third = Invoke-CurlAsync 'third' $extUrl @("X-API-Key: $apiKey") '{}'
    Start-Sleep -Milliseconds $LeadMs
    $fourth = Api POST "/api/jobs/$jobId/execute" @{}
    $thirdResult = Wait-CurlAsync $third

    Check '外部触发正常完成（HTTP 200）' ($thirdResult.httpStatus -eq '200') ("status=$($thirdResult.httpStatus)")
    Check '外部触发运行期间手动触发返回 409（两条入口共用占用表）' ($fourth.status -eq 409) ("status=$($fourth.status) code=$($fourth.code)")

    # ---------- 断言 3：运行结束后占用被释放 ----------
    Write-Host '--- 断言 3：运行结束后可再次执行 ---'
    $again = Api POST "/api/jobs/$jobId/execute" @{}
    Check '空闲后可以再次执行（占用在 finally 释放）' ($again.__error -ne $true) ("resp=$($again | ConvertTo-Json -Compress)")

    $runsFinal = ((Api GET "/api/history/runs?jobId=$jobId" $null).data.total)
    Check '总运行行数 = 3（并发里成功那次 + 外部一次 + 释放后一次）' (($runsFinal - $runsBefore) -eq 3) ("delta=$($runsFinal - $runsBefore)")

    # ---------- 断言 4：慢速端点被请求次数 ----------
    if ($StubLog -and (Test-Path $StubLog)) {
        Write-Host '--- 断言 4：慢速端点被请求次数 ---'
        $hits = @(Get-Content $StubLog | Where-Object { $_ -match '"seq"' }).Count - $stubLinesBefore
        Check '三次成功运行 = 桩端点三次请求（被跳过的那两次没有发出去）' ($hits -eq 3) ("hits=$hits")
    }
} finally {
    Api DELETE "/api/jobs/$jobId" $null | Out-Null
    Write-Host ("  已删测试任务 id={0}" -f $jobId)
}

Write-Host ''
Write-Host ("结果：通过 {0}，失败 {1}" -f $script:pass, $script:fail)
if ($script:fail -gt 0) { exit 1 }
