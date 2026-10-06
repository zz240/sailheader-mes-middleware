# 拆分动作（#140）真机验收：一份报文 -> 头一行 + 子 N 行（A2 那个场景的形状）
#
# 环境要求（都在跑）：网关 8080 + MySQL 3306（容器 edge-gateway-mysql）。
# 本脚本不需要 SQL Server（1433）与 MES 替身（9100）——A2 是 MySQL 轮询源，落点用同容器的 board_db。
#
# 与 seed-scenario.ps1 / verify-scenario.ps1 的差别：那两个文件是纯 ASCII（PS 5.1 把无 BOM 文件按
# ANSI 解析，中文会毁语法）。本文件按 README 允许的方式存成 UTF-8 with BOM，所以能写中文。
#
# 干什么：
#   1. 在 board_db 建落点两张表（头行 / 子行）——「头行分表」的落点形态;
#   2. 建一条链并启用：READ_SQL（头表 join 子表、JSON_ARRAYAGG 聚成子件数组）
#      -> FIELD_MAP（$parse 把数组还原成真数组）
#      -> SPLIT（#140 拆分动作：一条报文摊成 N 条子消息）
#      -> FIELD_MAP（子件字段 + 摊平进来的父字段 -> 目标列）
#      -> WRITE_UPSERT（逐条落库，幂等键 FLineKey）;
#   3. 执行两次：头跑验「逐条落库 + 五样齐全 + 批号换手」，再跑一次验「水位推进 + 幂等重跑不涨行数」。
#
# 跑法（仓库根）：
#   powershell -File tools/shadow-site/verify-split-140.ps1
#   powershell -File tools/shadow-site/verify-split-140.ps1 -KeepJob     # 跑完留着任务，便于去界面看消息历史
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [long]$SiteId = 1,
    [string]$MysqlContainer = 'edge-gateway-mysql',
    [switch]$KeepJob
)

$ErrorActionPreference = 'Continue'
$script:pass = 0
$script:fail = 0

function Check {
    param([string]$Name, $Ok, [string]$Detail = '')
    # $Ok 故意不定类型：`$x -match ...` 作用在数组上得到的是数组，[bool] 绑不上去
    if ($Ok) { $script:pass++; Write-Host ("  PASS  {0}" -f $Name) }
    else { $script:fail++; Write-Host ("  FAIL  {0}  {1}" -f $Name, $Detail) }
}

function Api {
    param([string]$Method, [string]$Path, $Body)
    $uri = "$BaseUrl$Path"
    if ($null -eq $Body) { return Invoke-RestMethod -Method $Method -Uri $uri -TimeoutSec 600 }
    $json = $Body | ConvertTo-Json -Depth 12 -Compress
    # PS 5.1 用字符串 body 时按 Latin-1 编码；显式发 UTF-8 字节，中文才不会写成乱码
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    return Invoke-RestMethod -Method $Method -Uri $uri -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 600
}

function MySql {
    param([string]$Sql)
    $out = docker exec $MysqlContainer mysql -uroot -proot -N -B -e $Sql 2>&1
    return @($out | Where-Object { "$_" -notmatch 'Warning' })
}

function Remove-JobByName {
    param([string]$Name)
    foreach ($j in @((Api GET "/api/jobs?name=$Name").data)) {
        if ($j.name -eq $Name) { Api DELETE "/api/jobs/$($j.id)" | Out-Null }
    }
}

function DataSourceIdByName {
    param([string]$Name)
    $hit = @((Api GET '/api/data-sources').data) | Where-Object { $_.name -eq $Name } | Select-Object -First 1
    if (-not $hit) { throw "找不到数据源 $Name（先跑 seed-scenario.ps1）" }
    return $hit.id
}

$JobName = 'SH-SPLIT-BomHead-Lines'
$HeadKey = 'BOM-SH-0001'
$ExpectedLines = 4

Write-Host "== #140 拆分动作真机验收（一份报文 -> 头一行 + 子 N 行）=="

# ---------- 0. 落点两张表 ----------
$ddl = @"
CREATE TABLE IF NOT EXISTS board_db.bom_head_t (
  FBOMNo VARCHAR(64) NOT NULL PRIMARY KEY,
  FMoldNo VARCHAR(64), FVersion VARCHAR(32),
  FLineCount INT, FBatchId VARCHAR(64), FModifyDate DATETIME);
CREATE TABLE IF NOT EXISTS board_db.bom_line_t (
  FLineKey VARCHAR(128) NOT NULL PRIMARY KEY,
  FBOMNo VARCHAR(64), FSeq INT, FItemNumber VARCHAR(64),
  FQty DECIMAL(18,4), FUnit VARCHAR(16),
  FLineCount INT, FBatchId VARCHAR(64), FBatchSeq INT, FParentMessageId VARCHAR(64));
DELETE FROM board_db.bom_head_t; DELETE FROM board_db.bom_line_t;
"@
MySql $ddl | Out-Null
$t = MySql "select count(*) from information_schema.tables where table_schema='board_db' and table_name in ('bom_head_t','bom_line_t');"
Check '落点两张表已就绪（头行 / 子行）' ("$t" -match '2') "tables=$t"

# 源数据核对：这一步不做，后面的断言就没有独立的事实依据
$src = MySql "select count(*) from mes_db.mes_bom_line where FBOMNo='$HeadKey';"
Check "源里有子件 N 行（$HeadKey）" ("$src" -match "$ExpectedLines") "source_lines=$src"

# ---------- 1. 建链并启用 ----------
Remove-JobByName $JobName
$dsMesDb = DataSourceIdByName 'sh-mes-db'
$dsBoardDb = DataSourceIdByName 'sh-board-db'

# 头表 join 子表、JSON_ARRAYAGG 聚成一列字符串（"一单多行 -> 一条报文"的现成做法）；
# JSON_UNQUOTE 是为了拿回**字符串**而不是 MySQL 的 JSON 类型（后者到 JDBC 可能是 byte[]）
$readSql = "select h.FBOMNo, h.FMoldNo, h.FVersion, h.FModifyDate, " +
           "count(l.FSeq) as FLineCount, " +
           "JSON_UNQUOTE(JSON_ARRAYAGG(JSON_OBJECT('FSeq', l.FSeq, 'FItemNumber', l.FItemNumber, " +
           "'FQty', l.FQty, 'FUnit', l.FUnit))) as lines_json " +
           "from mes_bom_head h join mes_bom_line l on l.FBOMNo = h.FBOMNo " +
           "where h.FBOMNo = '$HeadKey' and {watermark}   /* 占位符替换的是**整个谓词**：框架会写成 h.FModifyDate >= ?，别自己再写列名与比较符 */ " +
           "group by h.FBOMNo, h.FMoldNo, h.FVersion, h.FModifyDate"

# 装配（JSONata）：把 lines_json 那一列字符串还原成**真数组**，并在报文上留下父字段。
# 用 $merge 拼而不是在表达式里写大括号对象——省掉一层引号，PS 的字符串拼装不容易出错
$assemble = '$merge([$s_1, {"lines": $parse($s_1.lines_json)}])'

$job = Api POST '/api/jobs' @{
    name = $JobName; siteId = $SiteId; status = 0; direction = 'SOUTH'
    scheduleType = 'NONE'; retryCount = 1; backoffMs = 500
    timezone = 'Asia/Shanghai'; remarks = 'split action real-machine check (#140): one report -> head row + N line rows'
}
# 网关的响应统一包在 data 里（ApiEnvelopeAdvice）：取实体要走 .data
$jobId = $job.data.id
if (-not $jobId) { throw "建任务没拿到 id：$($job | ConvertTo-Json -Compress)" }

$actions = @(
    @{ name = 'read-bom'; type = 'READ_SQL'; addition = @{
        dataSourceId = $dsMesDb; sql = $readSql
        watermarkType = 'TIMESTAMP'; watermarkColumn = 'FModifyDate'
        businessKey = 'FBOMNo'; batchSize = 10 } },
    @{ name = 'assemble-report'; type = 'FIELD_MAP'; addition = @{
        mode = 'EXPRESSION'; expression = $assemble } },
    @{ name = 'split-lines'; type = 'SPLIT'; addition = @{
        sourceStep = 2; sourcePath = 'lines'; elementKey = 'FItemNumber'; keepParent = $true } },
    @{ name = 'map-line'; type = 'FIELD_MAP'; addition = @{ rules = @(
        @{ sourceStep = 3; source = 'FBOMNo'; target = 'FBOMNo'; transform = 'STRING' },
        @{ sourceStep = 3; source = 'FSeq'; target = 'FSeq'; transform = 'STRING' },
        @{ sourceStep = 3; source = 'FItemNumber'; target = 'FItemNumber'; transform = 'STRING' },
        @{ sourceStep = 3; source = 'FQty'; target = 'FQty'; transform = 'STRING' },
        @{ sourceStep = 3; source = 'FUnit'; target = 'FUnit'; transform = 'STRING' },
        @{ sourceStep = 3; source = 'FLineCount'; target = 'FLineCount'; transform = 'STRING' },
        @{ sourceStep = 3; source = 'FSeq'; target = 'FLineKey'; transform = 'CONCAT';
           parts = @(@{ path = 'FBOMNo' }, '#', @{ path = 'FSeq' }) }
    ) } },
    @{ name = 'write-lines'; type = 'WRITE_UPSERT'; addition = @{
        dataSourceId = $dsBoardDb; tableName = 'bom_line_t'; idempotencyKey = 'FLineKey' } }
)

$i = 1
foreach ($a in $actions) {
    Api POST "/api/jobs/$jobId/actions" @{ name = $a.name; type = $a.type; index = $i; addition = $a.addition } | Out-Null
    $i++
}
$validate = Api POST "/api/jobs/$jobId/validate"
$validateErrors = @($validate.data.errors)
if ($validateErrors.Count -gt 0) {
    foreach ($e in $validateErrors) { Write-Host ("    ! validate: [{0}] {1}" -f $e.scope, $e.message) }
}
Check '链配置期校验通过（含拆分来源步骤引用）' ($validateErrors.Count -eq 0) "errors=$($validateErrors | ConvertTo-Json -Compress)"
$enabled = Api PUT "/api/jobs/$jobId/status" @{ status = 1 }
Check "任务已启用（$JobName / id=$jobId，5 个动作）" ($enabled.data.status -eq 1) "status=$($enabled.data.status)"

# ---------- 2. 头跑 ----------
$firstRun = (Api POST "/api/jobs/$jobId/execute" @{}).data
Check '头跑执行完成（SUCCESS）' ($firstRun.lastRunStatus -eq 'SUCCESS') "lastRunStatus=$($firstRun.lastRunStatus) lastError=$($firstRun.lastError)"

$lineRows = MySql "select FLineKey, FSeq, FItemNumber, FQty, FLineCount from board_db.bom_line_t order by FSeq;"
Check "库里落了 N 条子行（N=$ExpectedLines）" ($lineRows.Count -eq $ExpectedLines) "rows=$($lineRows.Count) -> $($lineRows -join ' | ')"
Check '子行逐条对上源库的子件（编号 / 序号 / 数量）' `
    (($lineRows -join "`n") -match 'IT-SH-001' -and ($lineRows -join "`n") -match 'IT-SH-005') `
    ($lineRows -join ' | ')
Check '外层字段（头表的 FLineCount）摊平进了每条子行' `
    (@($lineRows | Where-Object { "$_" -match "\s$ExpectedLines\s*$" }).Count -eq $ExpectedLines) `
    ($lineRows -join ' | ')

# ---------- 3. 消息历史：拆分那一行 + N 条子行 ----------
$runId = @((Api GET "/api/history/runs?jobId=$jobId&page=1&size=1").data.records)[0].id
$steps = @((Api GET "/api/history/steps?runId=$runId&page=1&size=200").data.records)
Write-Host ("    (诊断) runId=$runId 步骤行数=" + $steps.Count)
$splitRows = @($steps | Where-Object { $_.step -eq 3 })
$mapRows = @($steps | Where-Object { $_.step -eq 4 })

Check '拆分动作**自己记了一行**消息步（输入父消息、输出子消息们）' ($splitRows.Count -eq 1) "step3_rows=$($splitRows.Count)"
if ($splitRows.Count -eq 1) {
    $s = $splitRows[0]
    Check '拆分那一行的输出是**那片数组**（两条以上子件）' ("$($s.dataSnapshot)" -match 'IT-SH-001' -and "$($s.dataSnapshot)" -match 'IT-SH-002') 'dataSnapshot 里应有全部子件'
    Check '拆分那一行的输入是**父消息**（本行 SELECT 的输出）' ("$($s.inputSnapshot)" -match 'BOM-SH-0001') 'inputSnapshot 应含 BOM 号'
}
Check "拆出来的 N 条子消息**各记一行**（N=$ExpectedLines）" ($mapRows.Count -eq $ExpectedLines) "step4_rows=$($mapRows.Count)"

if ($mapRows.Count -eq $ExpectedLines) {
    $batchIds = @($mapRows | ForEach-Object { $_.batchId } | Select-Object -Unique)
    $seqs = @($mapRows | ForEach-Object { $_.batchSeq } | Sort-Object)
    $parents = @($mapRows | ForEach-Object { $_.parentMessageId } | Select-Object -Unique)
    $keys = @($mapRows | ForEach-Object { $_.businessKey })

    Check '五样齐全①：每条子消息有自己的消息标识' ((@($mapRows | ForEach-Object { $_.messageId } | Select-Object -Unique)).Count -eq $ExpectedLines) '消息标识应互不相同'
    Check '五样齐全②：业务键取自元素里的 FItemNumber' (($keys -join ',') -match 'IT-SH-001') "businessKeys=$($keys -join ',')"
    Check '五样齐全③：批内序号按元素顺序（1..N）' (($seqs -join ',') -eq ((1..$ExpectedLines) -join ',')) "batchSeq=$($seqs -join ',')"
    Check '五样齐全④：父消息标识指向同一条（同级关系）' ($parents.Count -eq 1 -and $splitRows.Count -eq 1 -and $parents[0] -eq $splitRows[0].messageId) "parents=$($parents -join ',') splitRowMsgId=$($splitRows[0].messageId)"
    Check '五样齐全⑤：一次拆分 = 一个批次（同批号）' ($batchIds.Count -eq 1 -and $batchIds[0]) "batchIds=$($batchIds -join ',')"
    Check '批号在拆分这一步**换手**（与读库那一批不同）' ($batchIds[0] -ne $splitRows[0].batchId) "子消息批号=$($batchIds[0]) 拆分那一行批号=$($splitRows[0].batchId)"

    # 批号与批内序号那两格在**消息历史**里验（上面已断言）；落点表这边验「行序与源行一致」
    $dbSeq = MySql "select group_concat(FSeq order by FSeq) from board_db.bom_line_t;"
    $srcSeq = MySql "select group_concat(FSeq order by FSeq) from mes_db.mes_bom_line where FBOMNo='$HeadKey';"
    Check '落库的子行序号与源库子件行号逐一对应' ("$dbSeq" -eq "$srcSeq" -and "$srcSeq") "db=$dbSeq source=$srcSeq"
}

# ---------- 4. 再跑一次：水位推进 + 幂等 ----------
$secondRun = (Api POST "/api/jobs/$jobId/execute" @{}).data
$after = MySql "select count(*) from board_db.bom_line_t;"
Check '重跑仍是成功' ($secondRun.lastRunStatus -eq 'SUCCESS') "lastRunStatus=$($secondRun.lastRunStatus)"
Check "重跑不涨行数（水位推进后 0 条新增，仍 $ExpectedLines 行）" ("$after" -match "$ExpectedLines") "rows=$after"

if (-not $KeepJob) {
    Api DELETE "/api/jobs/$jobId" | Out-Null
    Write-Host "  （已删除 $JobName；加 -KeepJob 可以留着它去界面看消息历史）"
}

Write-Host ''
if ($script:fail -eq 0) { Write-Host ("全部通过：{0} 项" -f $script:pass); exit 0 }
Write-Host ("{0} 项没过（共 {1} 项）" -f $script:fail, ($script:pass + $script:fail)); exit 1
