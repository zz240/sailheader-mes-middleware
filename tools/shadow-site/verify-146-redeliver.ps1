# #146 任务串行真机验收 · 第二轮 B：重投入口（断点重投）在已有运行时 409
#
# 为什么不用真实库里的失败步：本机库中只剩 job 225 有失败步，它链上是 SQL Server 读取 + 真实 MES 投递，
# 让它真跑会改动真实业务数据。所以这里**自建一条会失败的链**，失败步是自己的：
#   链 = READ_SQL(mes_db.mes_work_order，只读) → HTTP_CALL(打桩端点，桩端点恒返回 500)
#   第一次运行：动作 2 失败 → 留下一条可重投的失败消息步（动作 1 是源动作，按口径不可重投）
#   第二次运行：动作 2 仍要等桩端点 3s，运行期间调断点重投接口 → 期望 409；
#   顺带确认整批重发端点已随 #147 删除（404——那条路径整个去掉，失败恢复统一为重跑任务）
# 链首为什么必须是 READ_SQL：只有源动作会 beginMessage，消息步才存在；纯 HTTP_CALL 打头的链
#   跑完只有 job_run、一条消息步都没有（docs/flows/reliability-flow.md 里记的另一条已知缺口）。
#
# 断言同时证明「跳过没有副作用」：被拒的那次重投没有执行，失败步状态仍为 FAILED、也没有多出运行。
#
# 前置：网关 8080、桩端点 18080 且**返 500**（tools/shadow-site/slow-http.cjs 18080 3000 500）、
#       容器 edge-gateway-mysql 里有 mes_db 与数据源 sh-mes-db。
# 用法：powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\shadow-site\verify-146-redeliver.ps1 `
#           [-AppLog <网关 stdout 日志文件>]

param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$SiteId = '1',
    [string]$DsName = 'verify-146-http',
    [string]$MesDbName = 'sh-mes-db',
    [string]$AppLog = 'D:\workspace\programs\edge-gateway\.scratch\146\app.log'
)

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0
$script:BaseUrl = $BaseUrl

# 共用的 Check / Api（显式 HttpWebRequest，见 helper 里的说明）
. (Join-Path $PSScriptRoot 'verify-146-http-helper.ps1')

# 异步发一次触发：不等待它跑完，让它的运行窗口盖住随后的重投请求。
# 这里刻意用 WebClient.UploadStringAsync（helper 那套同步读取会在派发点就把整次运行等完）。
function Start-Execute([string]$jobId) {
    $client = New-Object System.Net.WebClient
    $client.Encoding = [System.Text.Encoding]::UTF8
    $client.Headers['Content-Type'] = 'application/json; charset=utf-8'
    return $client.UploadStringAsync([Uri]"$BaseUrl/api/jobs/$jobId/execute", 'POST', '{}')
}

# 找这个任务最新的 FAILED 消息步
function LatestFailedStep([string]$jobId) {
    $steps = (Api GET "/api/history/steps?jobId=$jobId&status=FAILED&page=1&size=20" $null).data.records
    if (-not $steps) { return $null }
    return $steps | Sort-Object id -Descending | Select-Object -First 1
}

# 等这个任务真的进入「运行中」：重投必须在运行窗口内发，否则验的就不是互斥
function Wait-Running([string]$jobId, [int]$TimeoutSec = 30) {
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $runs = (Api GET "/api/history/runs?jobId=$jobId&page=1&size=5" $null).data.records
        if (@($runs | Where-Object { $_.status -eq 'RUNNING' }).Count -ge 1) { return $true }
        Start-Sleep -Milliseconds 150
    }
    return $false
}

Write-Host '#146 验收 · 重投入口在已有运行时被拒'

$dsId = ((Api GET '/api/data-sources' $null).data | Where-Object { $_.name -eq $DsName } | Select-Object -First 1).id
Check '取到桩端点数据源' ($null -ne $dsId) ("dsId=$dsId")

$jobId = $null
$logMark = 0
if (Test-Path $AppLog) { $logMark = @(Get-Content $AppLog -ErrorAction SilentlyContinue).Count }

try {
    # ---- 夹具：READ_SQL → HTTP_CALL(桩端点恒 500) ----
    # 链首必须是源动作（READ_SQL / MQ_CONSUME）：只有源动作会 beginMessage，消息步才存在，
    # 才有「可重投的失败步」。纯 HTTP_CALL 打头的链跑完只有 job_run。
    $mesDbId = ((Api GET '/api/data-sources' $null).data | Where-Object { $_.name -eq $MesDbName } | Select-Object -First 1).id
    Check '取到只读 SQL 数据源' ($null -ne $mesDbId) ("mesDbId=$mesDbId name=$MesDbName")
    if ($null -eq $mesDbId) { throw '没有可用的 SQL 数据源，夹具无法建立' }

    $job = Api POST '/api/jobs' @{
        name = "verify-146-redeliver-$([guid]::NewGuid().ToString('N').Substring(0,8))"
        siteId = [long]$SiteId; direction = 'NORTH'; scheduleType = 'NONE'
        retryCount = 0; backoffMs = 200; remarks = 'verify #146 redeliver'
    }
    $jobId = $job.data.id
    Check '夹具任务已建（拿到 id）' ($null -ne $jobId) ("resp=$($job | ConvertTo-Json -Compress -Depth 4)")
    if ($null -eq $jobId) { throw '夹具任务没建起来，后续断言没有意义' }
    Api POST "/api/jobs/$jobId/actions" @{
        name = 'read-work-order'; type = 'READ_SQL'; index = 1
        addition = @{ dataSourceId = [long]$mesDbId; sql = 'SELECT FInterID, FBillNo, FQty FROM mes_work_order'; batchSize = 10 }
    } | Out-Null
    Api POST "/api/jobs/$jobId/actions" @{
        name = 'push-500'; type = 'HTTP_CALL'; index = 2
        addition = @{ dataSourceId = [long]$dsId; path = '/hook/146'; method = 'POST' }
    } | Out-Null
    $enabled = Api PUT "/api/jobs/$jobId/status" @{ status = 1 }
    Check '夹具任务已启用（READ_SQL → HTTP_CALL）' ($enabled.data.status -eq 1) ("resp=$($enabled | ConvertTo-Json -Compress -Depth 4)")

    # ---- 第一次运行：动作 2 失败，留下可重投的失败步 ----
    $firstRun = Api POST "/api/jobs/$jobId/execute" @{}
    $failed = LatestFailedStep $jobId
    Check '第一次运行产生了失败消息步' ($null -ne $failed) ("resp=$($firstRun | ConvertTo-Json -Compress -Depth 4)")
    if (-not $failed) { throw '夹具没有产出失败消息步，后续断言无法进行' }
    Write-Host ("    失败步：id={0} step={1} status={2} messageId={3}" -f $failed.id, $failed.step, $failed.status, $failed.messageId)
    Check '失败步在动作 2 上且为 FAILED（可重投的合法前置）' ($failed.status -eq 'FAILED' -and $failed.step -eq 2) ("status=$($failed.status) step=$($failed.step)")

    # ---- 第二次运行：运行期间调断点重投接口（并确认整批重发端点已不存在） ----
    # 第二次运行的动作 2 要等桩端点 3s：先等它真的进入 RUNNING，再发重投请求，
    # 免得请求落在两次运行之间的空隙上（那验的就不是互斥了）。
    $second = Start-Execute $jobId
    $running = Wait-Running $jobId 30
    Check '第二次运行已进入 RUNNING（重投请求确实落在运行窗口内）' $running
    $redeliver = Api POST "/api/history/steps/$($failed.id)/redeliver" @{}
    # #147 删掉了整批重发那条路径：这个端点不该再答 200，也不该打到任何重投逻辑。
    # 注意本工程对**任何未匹配路径**都答全局统一信封的 500（ApiExceptionHandler 的 catch-all 收走了
    # Spring 的 NoResourceFoundException），不是 404 —— 那是既有全局行为，不在本票范围。
    # 这里先记下「跳过本次重投」的条数：那条日志只有真的走到重投服务才会多出来（= 目标端没被多打一次）。
    $skipBeforeGone = if (Test-Path $AppLog) { @(Get-Content $AppLog -ErrorAction SilentlyContinue | Where-Object { $_ -match '任务已有运行，跳过本次重投' }).Count } else { -1 }
    $batchGone = Api POST "/api/history/batches/$($failed.id)/redeliver" @{}
    $skipAfterGone = if (Test-Path $AppLog) { @(Get-Content $AppLog -ErrorAction SilentlyContinue | Where-Object { $_ -match '任务已有运行，跳过本次重投' }).Count } else { -1 }
    $runsAtReject = (Api GET "/api/history/runs?jobId=$jobId" $null).data.total
    $secondDone = $false
    $deadline = (Get-Date).AddSeconds(60)
    while (-not $secondDone -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 300
        $secondDone = $second.IsCompleted
    }
    Start-Sleep -Milliseconds 1500   # 等服务端把这次运行的行落完

    Check '断点重投在运行期间返回 409' ($redeliver.status -eq 409) ("status=$($redeliver.status) code=$($redeliver.code) raw=$($redeliver.raw)")
    Check '断点重投业务码为 JOB_ALREADY_RUNNING' ($redeliver.code -eq 'JOB_ALREADY_RUNNING') ("code=$($redeliver.code)")
    Check '断点重投文案为「任务正在执行，请稍后再试」' ($redeliver.message -eq '任务正在执行，请稍后再试') ("message=$($redeliver.message)")
    Check '整批重发端点已随 #147 删除（不再答 200；本工程未匹配路径答统一信封 500，不是 404）' ($batchGone.status -ne 200) ("status=$($batchGone.status) code=$($batchGone.code)")
    Check '这条路不打到重投服务（没多出「跳过本次重投」→ 目标端没被多打一次）' ($skipBeforeGone -lt 0 -or $skipAfterGone -eq $skipBeforeGone) ("before={0} after={1}" -f $skipBeforeGone, $skipAfterGone)

    # ---- 被拒的重投没有副作用：那一步仍是 FAILED，也没有多出运行 ----
    $stillFailed = Api GET "/api/history/steps/$($failed.id)" $null
    Check '被拒的重投没有改动那一步（仍为 FAILED，不是 REDELIVERED）' ($stillFailed.data.status -eq 'FAILED') ("status=$($stillFailed.data.status)")
    $runsAfter = (Api GET "/api/history/runs?jobId=$jobId" $null).data.total
    Check '被拒的重投没有多出运行（只有第一次 + 第二次两行）' ($runsAfter -eq 2) ("runsAfter=$runsAfter runsAtReject=$runsAtReject")

    # ---- 空闲后重投不再被 409 拒（证明上一条的 409 来自互斥，不是接口本身坏掉）----
    Start-Sleep -Milliseconds 500
    $afterIdle = Api POST "/api/history/steps/$($failed.id)/redeliver" @{}
    Check '空闲后重投不再返回 409（占用确实在 finally 释放）' ($afterIdle.status -ne 409) ("status=$($afterIdle.status) code=$($afterIdle.code)")

    if (Test-Path $AppLog) {
        $tail = @(Get-Content $AppLog -ErrorAction SilentlyContinue | Select-Object -Skip $logMark)
        $rejected = @($tail | Where-Object { $_ -match '任务已有运行，跳过本次重投' })
        Check '服务层日志里记录了「跳过本次重投」' ($rejected.Count -ge 1) ("matched=$($rejected.Count)")
        Check '这条日志带上了当前运行的 runId 与触发来源' (@($rejected | Where-Object { $_ -match 'run=\d+' -and $_ -match 'origin=' }).Count -ge 1) ("lines=$($rejected -join ' | ')")
        foreach ($line in $rejected | Select-Object -First 1) { Write-Host ("    日志：{0}" -f $line.Trim()) }
    }
} finally {
    if ($jobId) {
        Api DELETE "/api/jobs/$jobId" $null | Out-Null
        Write-Host ("  已删测试任务 id={0}" -f $jobId)
    }
}

Write-Host ''
Write-Host ("结果：通过 {0}，失败 {1}" -f $script:pass, $script:fail)
if ($script:fail -gt 0) { exit 1 }
