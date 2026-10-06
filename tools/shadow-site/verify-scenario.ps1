# Shadow-site acceptance run: execute the six chains against the real stand-ins and assert the
# outcome field by field. Expectations come from the seed data (seed/*.sql) plus the mapping rules
# in seed-scenario.ps1 -- both verified by the trial endpoint before this script existed.
#
# ASCII-only on purpose (Windows PowerShell 5.1 parses BOM-less files as ANSI).
#
# Usage (repo root):
#   powershell -File tools/shadow-site/verify-scenario.ps1
#   powershell -File tools/shadow-site/verify-scenario.ps1 -SkipWrite   # only A2/A3 (SQL Server writes) are
#                                                                       # skipped; every other chain still
#                                                                       # sends / writes (B6, A2MQ, A4PUSH,
#                                                                       # A7BIG and the #143 batch sections)
#
# What it checks:
#   A5  SQL Server read (3 pages of 2) -> MES purchase-order payloads, watermark advances, re-run adds nothing
#   A2  MySQL read -> SQL Server MERGE: 5 rows, re-run idempotent, update path really updates (not inserts)
#   A3  MySQL read -> SQL Server MFG_WorkOrder: 3 rows
#   A4  SQL Server read -> MES work-report payloads with composite key flattened (reportKey)
#   A7  240 QC detail rows flattened, read in 5 pages of 50
#   B6  EXPRESSION-mode mapping -> id-register payloads, handles resolvable, FHandle written back
#       from the chain response (#123)
#   B6-BATCH  the whole read batch in ONE request (#143): 20 codes, per-row handle write-back, and the
#       failure path (outlet 500 -> whole batch FAILED -> one click resends once)
#   A2-HL one report -> one head row + N line rows (#143): head row, split children, genealogy + batch
#   B6-BULK  the same batch shape at 1000-row scale (#143): one request, whole payload in history
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$MesMock = 'http://localhost:9100',
    [string]$MysqlContainer = 'edge-gateway-mysql',
    [string]$MssqlContainer = 'edge-shadow-mssql',
    [string]$SqlPassword = 'EdgeShadow#2026',
    [switch]$SkipWrite
)

$ErrorActionPreference = 'Continue'
$script:pass = 0
$script:fail = 0
$script:notes = @()

function Check {
    param([string]$Name, $Ok, [string]$Detail = '')
    # $Ok is deliberately untyped: `$x -match ...` on an array yields an array, and [bool] cannot bind that.
    if ($Ok) { $script:pass++; Write-Host ("  PASS  {0}" -f $Name) }
    else { $script:fail++; Write-Host ("  FAIL  {0}  {1}" -f $Name, $Detail) }
}

function Api {
    param([string]$Method, [string]$Path, $Body)
    $uri = "$BaseUrl$Path"
    if ($null -eq $Body) { return Invoke-RestMethod -Method $Method -Uri $uri -TimeoutSec 600 }
    $json = $Body | ConvertTo-Json -Depth 12 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    return Invoke-RestMethod -Method $Method -Uri $uri -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 600
}

function JobIdByName {
    param([string]$Name)
    $hit = @((Api GET "/api/jobs?name=$Name").data) | Where-Object { $_.name -eq $Name } | Select-Object -First 1
    if (-not $hit) { throw "job not found: $Name (run seed-scenario.ps1 first)" }
    return $hit.id
}

function Execute {
    param([long]$JobId)
    try { Api POST "/api/jobs/$JobId/execute" @{} | Out-Null; return 'ok' }
    catch { return ("failed: " + $_.Exception.Message) }
}

function MySql {
    param([string]$Sql)
    $out = docker exec $MysqlContainer mysql -uroot -proot -N -B -e $Sql 2>&1
    return @($out | Where-Object { "$_" -notmatch 'Warning' })
}

function Mssql {
    param([string]$Sql)
    if ($null -eq $script:tools18) { $script:tools18 = Test-MssqlTools18 }
    $sq = if ($script:tools18) { '/opt/mssql-tools18/bin/sqlcmd' } else { '/opt/mssql-tools/bin/sqlcmd' }
    $out = docker exec $MssqlContainer $sq -S localhost -U sa -P $SqlPassword -C -h -1 -W -Q $Sql 2>&1
    return @($out | Where-Object { "$_" -notmatch '^\s*$' })
}

function Test-MssqlTools18 {
    $probe = docker exec $MssqlContainer sh -c 'if [ -x /opt/mssql-tools18/bin/sqlcmd ]; then echo yes; fi' 2>&1
    return ("$probe" -match 'yes')
}

function Received {
    param([string]$PathFilter)
    $r = Invoke-RestMethod "$MesMock/__received?path=$PathFilter&limit=2000" -TimeoutSec 60
    return @($r.records)
}

function ClearReceived {
    Invoke-RestMethod -Method Delete "$MesMock/__received" -TimeoutSec 30 | Out-Null
}

function ClearFault {
    Invoke-RestMethod -Method Delete "$MesMock/__fault" -TimeoutSec 30 | Out-Null
}

# The latest run of a job (the batch sections read the run back instead of trusting the execute call).
function LatestRun {
    param([long]$JobId)
    return @((Api GET "/api/history/runs?jobId=$JobId&page=1&size=1").data.records)[0]
}

# Full message-step rows of a run (snapshots included). LIST_MAX_SIZE is 100, so small runs need one page.
function RunSteps {
    param([long]$RunId, [int]$Size, [int]$MaxPages)
    $all = @()
    for ($p = 1; $p -le $MaxPages; $p++) {
        $page = @((Api GET "/api/history/steps?runId=$RunId&page=$p&size=$Size").data.records)
        $all += $page
        if ($page.Count -lt $Size) { break }
    }
    return $all
}

# Index rows of a run (no snapshots, INDEX_MAX_SIZE is 1000 per page). Used where a run is too big to pull
# whole rows (the 1000-code batch has thousands of rows).
function RunIndex {
    param([long]$RunId, [int]$MaxPages = 6)
    $all = @()
    for ($p = 1; $p -le $MaxPages; $p++) {
        $page = @((Api GET "/api/history/runs/$RunId/steps?page=$p&size=1000").data.records)
        $all += $page
        if ($page.Count -lt 1000) { break }
    }
    return $all
}

# Resetting a batch scenario before it runs: drop the watermark (or the read finds nothing), clear FHandle
# (or the write-back is invisible) and forget what the stand-in received. The fault switch is NOT touched
# here -- the failure section has to inject its 500 first.
function Reset-BatchJob {
    param([long]$JobId)
    MySql "delete from edge_gateway.watermark where job_key='job:$JobId'" | Out-Null
    Mssql 'UPDATE CustomerERP.dbo.MDM_Code SET FHandle = NULL' | Out-Null
    ClearReceived
}

# Publish through the RabbitMQ management API (default exchange routes by queue name), so this script
# does not depend on tools/mq-publish.ps1 being parseable by Windows PowerShell 5.1.
function PublishMq {
    param([string]$Queue, [string]$Payload)
    $auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('guest:guest'))
    $body = @{
        properties       = @{ content_type = 'application/json' }
        routing_key      = $Queue
        payload          = $Payload
        payload_encoding = 'string'
    } | ConvertTo-Json -Depth 5 -Compress
    $resp = Invoke-RestMethod -Method Post -Uri 'http://localhost:15672/api/exchanges/%2F/amq.default/publish' `
        -Headers @{ Authorization = "Basic $auth" } -ContentType 'application/json' -Body $body -TimeoutSec 30
    if (-not $resp.routed) { throw "message not routed to $Queue" }
}

Write-Host ("== shadow-site acceptance ==  base={0} mock={1}" -f $BaseUrl, $MesMock)

# ---------------------------------------------------------------- SQL Server reachable?
$probe = Mssql 'SELECT COUNT(*) FROM CustomerERP.dbo.POOrder'
if (-not ($probe -match '^\s*5\s*$')) {
    Write-Host ("SQL Server not seeded or not reachable (POOrder count -> '{0}')." -f ($probe -join '|'))
    Write-Host 'Start it:  docker compose -f tools/shadow-site/docker-compose.yml up -d'
    Write-Host 'Seed it :  docker cp tools/shadow-site/seed/sqlserver-customer-erp.sql edge-shadow-mssql:/tmp/seed.sql ; docker exec edge-shadow-mssql /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "EdgeShadow#2026" -C -f 65001 -i /tmp/seed.sql'
    exit 2
}

# #143 fixtures: the 1000-row bulk section inserts and drops its own rows, and registers 1000 codes with
# the stand-in. Clean both here too, so an aborted run (rows left behind / 1000 handles in the ledger)
# cannot break the 20-code sections below — this script has to give the same result on a second run.
Mssql "DELETE FROM CustomerERP.dbo.MDM_Code WHERE FCode LIKE 'BULK-%'" | Out-Null
Invoke-RestMethod -Method Delete "$MesMock/__handles" -TimeoutSec 30 | Out-Null

# ---------------------------------------------------------------- A5: SQL Server read -> MES
Write-Host 'A5  customer ERP purchase orders (SQL Server) -> MES'
$j = JobIdByName 'SH-A5-PO-to-MES'
MySql "delete from edge_gateway.watermark where job_key='job:$j'" | Out-Null
ClearReceived
Execute $j | Out-Null
$got = Received 'purchase-order'
Check 'A5 sends 5 orders' ($got.Count -eq 5) ("got {0}" -f $got.Count)
$expected = @(
    @{ no = 'PO-SH-0001'; vendor = 'V-SH-001'; date = '2026-09-01'; status = '1' },
    @{ no = 'PO-SH-0002'; vendor = 'V-SH-002'; date = '2026-09-01'; status = '1' },
    @{ no = 'PO-SH-0003'; vendor = 'V-SH-001'; date = '2026-09-02'; status = '1' },
    @{ no = 'PO-SH-0004'; vendor = 'V-SH-003'; date = '2026-09-03'; status = '2' },
    @{ no = 'PO-SH-0005'; vendor = 'V-SH-002'; date = '2026-09-04'; status = '1' }
)
$mismatch = @()
foreach ($e in $expected) {
    $row = $got | Where-Object { $_.body.sheet_no -eq $e.no } | Select-Object -First 1
    if (-not $row) { $mismatch += "$($e.no):missing"; continue }
    if ("$($row.body.vendor_code)" -ne $e.vendor) { $mismatch += "$($e.no):vendor=$($row.body.vendor_code)" }
    if ("$($row.body.sheet_date)" -ne $e.date) { $mismatch += "$($e.no):date=$($row.body.sheet_date)" }
    if ("$($row.body.status)" -ne $e.status) { $mismatch += "$($e.no):status=$($row.body.status)" }
    if ("$($row.body.source_ref)" -ne ("ERP-" + $e.no)) { $mismatch += "$($e.no):source_ref=$($row.body.source_ref)" }
}
Check 'A5 payload fields match seed data' ($mismatch.Count -eq 0) ($mismatch -join ', ')
$wm = MySql "select wm_value from edge_gateway.watermark where job_key='job:$j'"
Check 'A5 watermark advanced to 5' ($wm -match '^5$') ("wm=$($wm -join ',')")
ClearReceived
Execute $j | Out-Null
$again = Received 'purchase-order'
Check 'A5 re-run is incremental (0 new)' ($again.Count -eq 0) ("got {0}" -f $again.Count)

# ---------------------------------------------------------------- A2: MySQL read -> SQL Server MERGE
if (-not $SkipWrite) {
    Write-Host 'A2  MES master data (MySQL) -> CustomerERP.dbo.t_Item (MERGE)'
    $j2 = JobIdByName 'SH-A2-Item-to-CustomerERP'
    Mssql 'DELETE FROM CustomerERP.dbo.t_Item' | Out-Null
    MySql "delete from edge_gateway.watermark where job_key='job:$j2'" | Out-Null
    Execute $j2 | Out-Null
    $n1 = Mssql 'SELECT COUNT(*) FROM CustomerERP.dbo.t_Item'
    Check 'A2 inserts 5 items' ($n1 -match '^\s*5\s*$') ("count={0}" -f ($n1 -join '|'))
    Execute $j2 | Out-Null
    $n2 = Mssql 'SELECT COUNT(*) FROM CustomerERP.dbo.t_Item'
    Check 'A2 re-run is idempotent (still 5)' ($n2 -match '^\s*5\s*$') ("count={0}" -f ($n2 -join '|'))
    # update path: touch one source row, re-run, expect UPDATE not a second row
    MySql "update mes_db.mes_item set FName='IT-SH-001-REV2', FModifyDate='2026-09-18 23:30:00' where FNumber='IT-SH-001'" | Out-Null
    Execute $j2 | Out-Null
    $n3 = Mssql 'SELECT COUNT(*) FROM CustomerERP.dbo.t_Item'
    $name = Mssql "SELECT FName FROM CustomerERP.dbo.t_Item WHERE FNumber='IT-SH-001'"
    Check 'A2 MERGE updated in place (still 5 rows)' ($n3 -match '^\s*5\s*$') ("count={0}" -f ($n3 -join '|'))
    Check 'A2 MERGE carried the new value' (("$name" -match 'IT-SH-001-REV2')) ("FName={0}" -f ($name -join '|'))

    Write-Host 'A3  MES work orders (MySQL) -> CustomerERP.dbo.MFG_WorkOrder'
    $j3 = JobIdByName 'SH-A3-WorkOrder-to-CustomerERP'
    Mssql 'DELETE FROM CustomerERP.dbo.MFG_WorkOrder' | Out-Null
    MySql "delete from edge_gateway.watermark where job_key='job:$j3'" | Out-Null
    Execute $j3 | Out-Null
    $w1 = Mssql 'SELECT COUNT(*) FROM CustomerERP.dbo.MFG_WorkOrder'
    Check 'A3 inserts 3 work orders' ($w1 -match '^\s*3\s*$') ("count={0}" -f ($w1 -join '|'))
}

# ---------------------------------------------------------------- A4: SQL Server read -> MES
Write-Host 'A4  work reports (SQL Server) -> MES'
$j4 = JobIdByName 'SH-A4-Report-to-MES'
MySql "delete from edge_gateway.watermark where job_key='job:$j4'" | Out-Null
ClearReceived
Execute $j4 | Out-Null
$rep = Received 'mes/api/report'
Check 'A4 sends 6 reports' ($rep.Count -eq 6) ("got {0}" -f $rep.Count)
$keys = @($rep | ForEach-Object { "$($_.body.reportKey)" } | Sort-Object)
$expectedKeys = @('WO-SH-0001#10', 'WO-SH-0001#20', 'WO-SH-0002#10', 'WO-SH-0002#30', 'WO-SH-0003#10', 'WO-SH-0003#40') | Sort-Object
Check 'A4 composite business keys flattened' (($keys -join ',') -eq ($expectedKeys -join ',')) ($keys -join ',')
$one = $rep | Where-Object { "$($_.body.reportKey)" -eq 'WO-SH-0001#10' } | Select-Object -First 1
Check 'A4 date/quantity fields' (("$($one.body.reportTime)" -eq '2026-09-05 08:35:00') -and ("$($one.body.operationSeq)" -eq '10') -and ("$($one.body.equipment)" -eq 'CNC-01')) ("reportTime=$($one.body.reportTime) seq=$($one.body.operationSeq) eq=$($one.body.equipment)")

# ---------------------------------------------------------------- A7: 240 detail rows, 5 pages
Write-Host 'A7  QC/test report detail rows (SQL Server) -> MES'
$j7 = JobIdByName 'SH-A7-QC-to-MES'
MySql "delete from edge_gateway.watermark where job_key='job:$j7'" | Out-Null
ClearReceived
Execute $j7 | Out-Null
$qc = Received 'qc-report'
Check 'A7 sends 240 detail rows' ($qc.Count -eq 240) ("got {0}" -f $qc.Count)
$first = $qc | Where-Object { "$($_.body.detailKey)" -eq 'QC-SH-0001#1' } | Select-Object -First 1
Check 'A7 first detail row mapped' ($null -ne $first -and "$($first.body.itemCode)" -eq 'DIM-001' -and "$($first.body.judge)" -eq 'OK') ("detailKey hit={0}" -f ($null -ne $first))
$bothReports = (@($qc | ForEach-Object { "$($_.body.reportNo)" } | Sort-Object -Unique) -join ',')
Check 'A7 covers both reports' ($bothReports -eq 'QC-SH-0001,QC-SH-0002') $bothReports

# ---------------------------------------------------------------- B6: registration + write-back
Write-Host 'B6  one-object-one-code registration (EXPRESSION mode) -> MES -> MDM_Code.FHandle'
$j6 = JobIdByName 'SH-B6-ID-Register'
MySql "delete from edge_gateway.watermark where job_key='job:$j6'" | Out-Null
Mssql 'UPDATE CustomerERP.dbo.MDM_Code SET FHandle = NULL' | Out-Null
ClearReceived
Execute $j6 | Out-Null
$reg = Received 'id-register'
Check 'B6 sends 20 registrations' ($reg.Count -eq 20) ("got {0}" -f $reg.Count)
$bad = @($reg | Where-Object { -not $_.body.codes -or $_.body.codes.Count -ne 1 -or -not $_.body.codes[0].FCode })
Check 'B6 body shape is codes[] with one code' ($bad.Count -eq 0) ("bad={0}" -f $bad.Count)
$handles = @($reg | ForEach-Object { $_.body.codes[0].FCode } | Sort-Object -Unique)
Check 'B6 covers 20 distinct codes' ($handles.Count -eq 20) ("distinct={0}" -f $handles.Count)
$hs = Invoke-RestMethod "$MesMock/__handles" -TimeoutSec 30
Check 'B6 registered 20 handles' ($hs.total -eq 20) ("total={0}" -f $hs.total)
if ($hs.total -ge 1) {
    $first = $hs.handles[0]
    $resolve = Invoke-RestMethod "$MesMock/mes/api/id/resolve/$($first.handle)" -TimeoutSec 30
    Check 'B6 handle resolves back to its code' (("$($resolve.code)" -eq '0') -and ("$($resolve.fCode)" -eq "$($first.fCode)")) ("handle={0} -> {1}" -f $first.handle, ($resolve | ConvertTo-Json -Compress))
}

# #123 acceptance: the registration response is addressable on the chain, so the SAME chain writes the
# handle back. Read the write-back from the chain (message history, last step) instead of treating the
# stand-in's own /__handles bookkeeping as evidence.
$latestRun = @((Api GET "/api/history/runs?jobId=$j6&page=1&size=1").data.records)[0].id
$runSteps = @((Api GET "/api/history/steps?runId=$latestRun&status=SUCCESS&page=1&size=100").data.records)
$writeback = @($runSteps | Where-Object { "$($_.step)" -eq '5' })
Check 'B6 write-back step succeeded for all 20 rows' ($writeback.Count -eq 20) ("writeback={0} steps={1}" -f $writeback.Count, $runSteps.Count)
$chainHandles = @($writeback | ForEach-Object { ($_.dataSnapshot | ConvertFrom-Json).FHandle } | Sort-Object -Unique)
Check 'B6 chain carried a handle for every row' ($chainHandles.Count -eq 20) ("distinct={0}" -f $chainHandles.Count)
# sqlcmd prints a "(N rows affected)" summary line unless NOCOUNT is set, so the row COUNT comes from SQL
# itself (same style as the A2/A3 checks above) and the value list filters that summary out.
$writtenCount = Mssql 'SELECT COUNT(*) FROM CustomerERP.dbo.MDM_Code WHERE FHandle IS NOT NULL'
Check 'B6 FHandle written back to MDM_Code (20 rows)' ($writtenCount -match '^\s*20\s*$') ("count={0}" -f ($writtenCount -join '|'))
$written = @(Mssql 'SELECT FHandle FROM CustomerERP.dbo.MDM_Code WHERE FHandle IS NOT NULL' |
    ForEach-Object { "$_".Trim() } |
    Where-Object { $_ -and $_ -notmatch 'rows affected' })
$missing = @($chainHandles | Where-Object { $h = $_; @($written | Where-Object { $_ -eq "$h" }).Count -eq 0 })
Check 'B6 written-back handles are the ones the chain received' ($missing.Count -eq 0) ("missing={0}" -f ($missing -join ','))

# ---------------------------------------------------------------- entry variant: MQ -> SQL Server
Write-Host 'A2MQ  MES pushes master data on MQ -> CustomerERP.dbo.t_Item'
$jmq = JobIdByName 'SH-A2MQ-Item-to-CustomerERP'
Mssql "DELETE FROM CustomerERP.dbo.t_Item WHERE FNumber LIKE 'IT-SH-MQ-%'" | Out-Null
PublishMq 'sh.mes.item.to.edge' '{"FNumber":"IT-SH-MQ-001","FName":"MQ-ITEM-1","FSpec":"SPEC-MQ","FUnit":"PCS","FMaterial":"718H","FVersion":"V1.0"}'
$row = @()
for ($i = 0; $i -lt 10; $i++) { Start-Sleep -Seconds 2; $row = Mssql "SELECT FName FROM CustomerERP.dbo.t_Item WHERE FNumber='IT-SH-MQ-001'"; if ($row -match 'MQ-ITEM-1') { break } }
Check 'A2MQ MQ entry reaches the SQL Server MERGE' ($row -match 'MQ-ITEM-1') ("row={0}" -f ($row -join '|'))
PublishMq 'sh.mes.item.to.edge' '{"FNumber":"IT-SH-MQ-001","FName":"MQ-ITEM-1-REV2","FSpec":"SPEC-MQ","FUnit":"PCS","FMaterial":"718H","FVersion":"V1.0"}'
$cnt = @(); $row2 = @()
for ($i = 0; $i -lt 10; $i++) { Start-Sleep -Seconds 2; $cnt = Mssql "SELECT COUNT(*) FROM CustomerERP.dbo.t_Item WHERE FNumber='IT-SH-MQ-001'"; $row2 = Mssql "SELECT FName FROM CustomerERP.dbo.t_Item WHERE FNumber='IT-SH-MQ-001'"; if ($row2 -match 'REV2') { break } }
Check 'A2MQ same key updates in place (1 row)' (($cnt -match '^\s*1\s*$') -and ($row2 -match 'REV2')) ("count={0} name={1}" -f ($cnt -join '|'), ($row2 -join '|'))

# ---------------------------------------------------------------- entry variant: terminal push
Write-Host 'A4PUSH  work terminal POSTs straight to the gateway (external trigger)'
$jpush = JobIdByName 'SH-A4PUSH-Report-to-MES'
# 明文只返回这一次（库里只存哈希），所以每次验收都重新签发一把——会作废上一把。
# 偶发拿到空响应时重试；三次都拿不到就让断言显式失败，而不是带着 null 往下走。
$key = $null
for ($attempt = 1; $attempt -le 3 -and -not $key; $attempt++) {
    $keyWeb = Invoke-WebRequest -Method Post -Uri "$BaseUrl/api/sites/1/api-key" -TimeoutSec 30 -UseBasicParsing
    if ($keyWeb -and $keyWeb.Content) { $key = ($keyWeb.Content | ConvertFrom-Json).data.apiKey }
    if (-not $key) { Start-Sleep -Seconds 2 }
}
Check 'A4PUSH issues an API key for the site' (-not [string]::IsNullOrEmpty($key)) ("len={0}" -f ("$key").Length)
ClearReceived
$pushBody = @{ FBillNo = 'WO-SH-0009'; FSeq = 10; FQtyOK = 2; FWorker = 'E1'; FEquipment = 'CNC-01' } | ConvertTo-Json -Compress
$pushBytes = [System.Text.Encoding]::UTF8.GetBytes($pushBody)
$triggerResult = ''
try {
    $tr = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/external/jobs/$jpush/trigger" -Headers @{ 'X-API-Key' = $key } -ContentType 'application/json; charset=utf-8' -Body $pushBytes -TimeoutSec 120
    $triggerResult = "$($tr.data.lastRunStatus)"
} catch {
    $triggerResult = "ERR $($_.Exception.Response.StatusCode.value__) $($_.ErrorDetails.Message)"
}
Write-Host ("    (key len={0}, trigger -> {1})" -f "$key".Length, $triggerResult)
$rep2 = Received 'mes/api/report'
Write-Host ("    (received {0} report payloads: {1})" -f @($rep2).Count, (@($rep2 | ForEach-Object { "$($_.body.reportKey)" }) -join ','))
$hit = $rep2 | Where-Object { "$($_.body.reportKey)" -eq 'WO-SH-0009#10' } | Select-Object -First 1
Check 'A4PUSH trigger body becomes the chain-head input (sourceStep 0)' ($null -ne $hit -and "$($hit.body.workOrderNo)" -eq 'WO-SH-0009' -and "$($hit.body.operationSeq)" -eq '10') ("hit={0}" -f ($null -ne $hit))
$http = ''
try {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/external/jobs/$jpush/trigger" -Headers @{ 'X-API-Key' = 'wrong-key' } -ContentType 'application/json' -Body '{}' -TimeoutSec 30 | Out-Null
    $http = '200'
} catch { $http = "$($_.Exception.Response.StatusCode.value__)" }
Check 'A4PUSH wrong API key is rejected (401)' ($http -eq '401') ("http=$http")

# ---------------------------------------------------------------- A7BIG: whole report in one message
Write-Host 'A7BIG  whole QC/test report in one message (SQL JSON aggregation + $parse/$map)'
$jbig = JobIdByName 'SH-A7BIG-Whole-Report'
MySql "delete from edge_gateway.watermark where job_key='job:$jbig'" | Out-Null
ClearReceived
Execute $jbig | Out-Null
$whole = Received 'qc-report'
Check 'A7BIG sends one message per report (3)' ($whole.Count -eq 3) ("got {0}" -f $whole.Count)
$big = $whole | Where-Object { "$($_.body.reportNo)" -eq 'QC-SH-0003' } | Select-Object -First 1
Check 'A7BIG carries all 3000 detail lines' ($null -ne $big -and "$($big.body.lineCount)" -eq '3000' -and @($big.body.lines).Count -eq 3000) ("lineCount={0} lines={1}" -f $(if ($big) { $big.body.lineCount } else { 'n/a' }), $(if ($big) { @($big.body.lines).Count } else { 0 }))
$bigKb = 0
if ($big) { $bigKb = [math]::Round((($big | ConvertTo-Json -Depth 8 -Compress).Length / 1KB), 1) }
Check 'A7BIG payload is a real big one (>300 KB)' ($bigKb -gt 300) ("payloadKB={0}" -f $bigKb)
if ($big) { $item5 = @($big.body.lines)[4]; Check 'A7BIG line fields mapped' ("$($item5.itemSeq)" -eq '5' -and "$($item5.itemCode)" -eq 'DIM-0005') ("line5={0}" -f ($item5 | ConvertTo-Json -Compress)) }

# ---------------------------------------------------------------- B6 batch: ONE request for the whole read batch (#143 AC1)
# #142 made this shape possible: read batch -> pack -> ONE outlet request -> fan the response back to the
# same messages -> write each row back. The read batch size IS the request size (ADR-0017 decision 5), so
# the 20 shadow-site codes go out in one request here.
Write-Host 'B6-BATCH  the whole read batch in ONE request -> per-row handle write-back'
$jbatch = JobIdByName 'SH-B6-Register-Batch'
$jbatchOutlet = 4
$jbatchFanout = 5
Reset-BatchJob -JobId $jbatch
ClearFault
$batchResult = Execute $jbatch
Check 'B6-BATCH run succeeded' ($batchResult -eq 'ok') $batchResult
$batchReqs = @(Received 'id-register')
Check 'B6-BATCH sends 20 codes in exactly ONE request' ($batchReqs.Count -eq 1) ("requests={0}" -f $batchReqs.Count)
$batchCodes = @()
if ($batchReqs.Count -ge 1) { $batchCodes = @($batchReqs[0].body.codes) }
Check 'B6-BATCH that one request carries all 20 codes' ($batchCodes.Count -eq 20) ("codes={0}" -f $batchCodes.Count)

$bRun = LatestRun $jbatch
Check 'B6-BATCH the run is recorded SUCCESS' ($bRun -and $bRun.status -eq 'SUCCESS') ("status={0}" -f $bRun.status)
$bIdx = RunIndex $bRun.id 2
$bSummary = @($bIdx | Where-Object { "$($_.messageId)" -like 'batch:*' })
Check 'B6-BATCH the outlet keeps exactly ONE summary row (whole request + whole response)' ($bSummary.Count -eq 1) ("rows={0}" -f $bSummary.Count)
$bFan = @($bIdx | Where-Object { $_.step -eq $jbatchFanout })
Check 'B6-BATCH the fan-out records one row per code (20 rows, all SUCCESS)' ($bFan.Count -eq 20 -and @($bFan | Where-Object { $_.status -ne 'SUCCESS' }).Count -eq 0) ("rows={0}" -f $bFan.Count)
$bKeys = @($bFan | ForEach-Object { "$($_.businessKey)" })
Check 'B6-BATCH per-row attribution: every fan-out row carries its own business key (20 distinct)' ((@($bKeys | Select-Object -Unique)).Count -eq 20) ("distinct={0}" -f (@($bKeys | Select-Object -Unique)).Count)
$bBatchIds = @($bIdx | Where-Object { $_.step -ge 3 } | ForEach-Object { $_.batchId } | Select-Object -Unique)
$bReadBatch = @($bIdx | Where-Object { $_.step -eq 1 } | ForEach-Object { $_.batchId } | Select-Object -Unique)
Check 'B6-BATCH batch identity: the packed batch shares ONE batch id (= the summary row), handed over at the merge step' `
    ($bBatchIds.Count -eq 1 -and "$($bBatchIds[0])" -and $bBatchIds[0] -eq $bSummary[0].batchId -and $bReadBatch.Count -eq 1 -and $bReadBatch[0] -ne $bBatchIds[0]) `
    ("packedBatch={0} readBatch={1}" -f ($bBatchIds -join ','), ($bReadBatch -join ','))

# per-row attribution with the snapshots: which code got which handle
$bRows = RunSteps $bRun.id 100 2
$bFanRows = @($bRows | Where-Object { $_.step -eq $jbatchFanout })
$bBad = @($bFanRows | Where-Object {
        $snap = $null
        try { $snap = "$($_.dataSnapshot)" | ConvertFrom-Json } catch { }
        (-not $snap) -or ("$($snap.FCode)" -ne "$($_.businessKey)") -or (-not "$($snap.handle)")
    })
Check 'B6-BATCH every fan-out row is attributable (own code + own handle in its snapshot)' ($bFanRows.Count -eq 20 -and $bBad.Count -eq 0) ("rows={0} bad={1}" -f $bFanRows.Count, $bBad.Count)

$bWrite = @($bIdx | Where-Object { $_.step -eq 7 })
Check 'B6-BATCH the write-back step ran for every code (20 rows)' ($bWrite.Count -eq 20) ("rows={0}" -f $bWrite.Count)
$dbPairs = @(Mssql "SELECT FCode + '=' + FHandle FROM CustomerERP.dbo.MDM_Code WHERE FHandle IS NOT NULL" |
        ForEach-Object { "$_".Trim() } | Where-Object { $_ -and $_ -notmatch 'rows affected' })
Check 'B6-BATCH wrote back exactly one handle per code (20 rows)' ($dbPairs.Count -eq 20) ("rows={0}" -f $dbPairs.Count)
$hsBatch = Invoke-RestMethod "$MesMock/__handles" -TimeoutSec 30
$mockPairs = @($hsBatch.handles | ForEach-Object { "$($_.fCode)=$($_.handle)" })
$missingPairs = @($mockPairs | Where-Object { $dbPairs -notcontains $_ })
Check 'B6-BATCH the written-back handles are the ones the id service returned' ($missingPairs.Count -eq 0) ("missing={0}" -f ($missingPairs -join ', '))

# ---------------------------------------------------------------- A2 head-and-lines: one report -> head row + N line rows (#143 AC2)
Write-Host 'A2-HL  one report -> one head row + N line rows (split, genealogy + batch in history)'
# The two landing tables are created by seed/mysql-mes-and-board.sql (a fresh machine needs them to exist
# before the job is created); this section only clears them, so the same DDL does not live in three places
# (the other two: that seed file and verify-split-140.ps1).
MySql 'DELETE FROM board_db.bom_head_t; DELETE FROM board_db.bom_line_t;' | Out-Null
$jhl = JobIdByName 'SH-A2-Bom-Head-Lines'
MySql "delete from edge_gateway.watermark where job_key='job:$jhl'" | Out-Null
$hlResult = Execute $jhl
Check 'A2-HL run succeeded' ($hlResult -eq 'ok') $hlResult
$headRows = @(MySql 'select FBOMNo, FLineCount from board_db.bom_head_t order by FBOMNo;')
Check 'A2-HL wrote the head rows (one per report = 2)' ($headRows.Count -eq 2) ("rows={0} -> {1}" -f $headRows.Count, ($headRows -join ' | '))
$lineRows = @(MySql 'select FLineKey, FItemNumber from board_db.bom_line_t order by FLineKey;')
Check 'A2-HL wrote one row per line (4 + 2 = 6)' ($lineRows.Count -eq 6) ("rows={0} -> {1}" -f $lineRows.Count, ($lineRows -join ' | '))
Check 'A2-HL the head row carries the line count from the source (BOM-SH-0001 -> 4)' (($headRows -join "`n") -match 'BOM-SH-0001\s+4') ($headRows -join ' | ')

$hlRun = LatestRun $jhl
$hlRows = RunSteps $hlRun.id 100 2
$splitRows = @($hlRows | Where-Object { $_.step -eq 5 })
$headStepRows = @($hlRows | Where-Object { $_.step -eq 4 })
Check 'A2-HL the head row is written by the report message itself (one row per report)' ($headStepRows.Count -eq 2) ("rows={0}" -f $headStepRows.Count)
Check 'A2-HL the split step records one row per report (the parent message)' ($splitRows.Count -eq 2) ("rows={0}" -f $splitRows.Count)
$split1 = @($splitRows | Where-Object { "$($_.businessKey)" -eq 'BOM-SH-0001' })
Check 'A2-HL BOM-SH-0001 has its own split row (business key = the report number)' ($split1.Count -eq 1) ("rows={0}" -f $split1.Count)
if ($split1.Count -eq 1) {
    Check 'A2-HL the split row keeps the parent input and the child output' `
        (("$($split1[0].inputSnapshot)" -match 'BOM-SH-0001') -and ("$($split1[0].dataSnapshot)" -match 'IT-SH-001')) 'input = parent report, output = the line array'
    $kids = @($hlRows | Where-Object { $_.step -eq 6 -and "$($_.parentMessageId)" -eq "$($split1[0].messageId)" })
    Check 'A2-HL the 4 line rows are the children of that split row (genealogy)' ($kids.Count -eq 4) ("children={0}" -f $kids.Count)
    $kidBatch = @($kids | ForEach-Object { $_.batchId } | Select-Object -Unique)
    Check 'A2-HL one batch id for those 4 children, handed over at the split step' ($kidBatch.Count -eq 1 -and "$($kidBatch[0])" -and $kidBatch[0] -ne $split1[0].batchId) ("childBatch={0} splitRowBatch={1}" -f ($kidBatch -join ','), $split1[0].batchId)
    $kidSeq = @($kids | ForEach-Object { $_.batchSeq } | Sort-Object)
    Check 'A2-HL the batch sequence runs 1..4 (position inside the report)' (($kidSeq -join ',') -eq '1,2,3,4') ("batchSeq={0}" -f ($kidSeq -join ','))
    $head1 = @($headStepRows | Where-Object { "$($_.businessKey)" -eq 'BOM-SH-0001' })
    Check 'A2-HL head row and line rows share the same lineage parent message' ($head1.Count -eq 1 -and "$($head1[0].messageId)" -eq "$($split1[0].messageId)") ("headMsg={0} parent={1}" -f $head1[0].messageId, $split1[0].messageId)
}
$hlAgain = Execute $jhl
$lineAfter = @(MySql 'select FLineKey from board_db.bom_line_t;')
$headAfter = @(MySql 'select FBOMNo from board_db.bom_head_t;')
Check 'A2-HL re-run is idempotent (watermark advanced: still 2 head rows + 6 line rows)' `
    (($hlAgain -eq 'ok') -and $lineAfter.Count -eq 6 -and $headAfter.Count -eq 2) `
    ("head={0} lines={1}" -f $headAfter.Count, $lineAfter.Count)

# ---------------------------------------------------------------- B6 batch failure path (#143 AC3)
# The outlet answers 500 -> the whole batch fails with ONE cause, every code keeps its own row and its own
# business key, nothing is written back, the watermark does not move. Then one click on "resend this batch"
# (POST /api/history/batches/{summary row id}/redeliver) sends exactly ONE more request.
Write-Host 'B6-BATCH-FAIL  outlet 500 -> whole batch FAILED with one cause -> one click = one request'
$fault = Invoke-RestMethod -Method Post "$MesMock/__fault" -ContentType 'application/json' -Body '{"mode":"500","count":10}' -TimeoutSec 30
Check 'B6-BATCH-FAIL the target-side 500 is injected' ("$($fault.fault.mode)" -eq '500') ("fault={0}" -f ($fault.fault | ConvertTo-Json -Compress))
Reset-BatchJob -JobId $jbatch
Execute $jbatch | Out-Null
$fRun = LatestRun $jbatch
Check 'B6-BATCH-FAIL the run is recorded FAILED' ($fRun -and $fRun.status -eq 'FAILED') ("status={0}" -f $fRun.status)
$fRows = RunSteps $fRun.id 100 2
$fSummary = @($fRows | Where-Object { "$($_.messageId)" -like 'batch:*' })
Check 'B6-BATCH-FAIL the outlet leaves exactly ONE FAILED summary row' ($fSummary.Count -eq 1 -and $fSummary[0].status -eq 'FAILED') ("rows={0} status={1}" -f $fSummary.Count, $fSummary[0].status)
$fMembers = @($fRows | Where-Object { $_.step -eq $jbatchOutlet -and "$($_.messageId)" -notlike 'batch:*' })
Check 'B6-BATCH-FAIL every code in that batch is recorded FAILED (20 rows)' ($fMembers.Count -eq 20 -and @($fMembers | Where-Object { $_.status -ne 'FAILED' }).Count -eq 0) ("rows={0}" -f $fMembers.Count)
$fCauses = @($fMembers | ForEach-Object { "$($_.error)" } | Select-Object -Unique)
Check 'B6-BATCH-FAIL all 20 rows share the same cause (attribution: one reason, 20 rows)' ($fCauses.Count -eq 1 -and $fCauses[0] -match '500') ("causes={0}" -f $fCauses.Count)
$fKeys = @($fMembers | ForEach-Object { "$($_.businessKey)" } | Select-Object -Unique)
Check 'B6-BATCH-FAIL each failed row keeps its own business key (which code failed)' ($fKeys.Count -eq 20) ("distinct={0}" -f $fKeys.Count)
$fWm = MySql "select count(*) from edge_gateway.watermark where job_key='job:$jbatch'"
Check 'B6-BATCH-FAIL the watermark did NOT advance' ("$fWm" -match '^0$') ("watermark rows={0}" -f $fWm)
$fWritten = Mssql 'SELECT COUNT(*) FROM CustomerERP.dbo.MDM_Code WHERE FHandle IS NOT NULL'
Check 'B6-BATCH-FAIL nothing was written back while the outlet was down' ($fWritten -match '^\s*0\s*$') ("rows={0}" -f ($fWritten -join '|'))

ClearFault
$sentBefore = @(Received 'id-register').Count
$resend = Api POST "/api/history/batches/$($fSummary[0].id)/redeliver" @{}
$sentAfter = @(Received 'id-register').Count
Check 'B6-BATCH-FAIL resending the batch sends exactly ONE more request' (($sentAfter - $sentBefore) -eq 1) ("before={0} after={1}" -f $sentBefore, $sentAfter)
$resendRun = LatestRun $jbatch
Check 'B6-BATCH-FAIL the resend run is recorded SUCCESS (the button works, not just shows)' ($resendRun.status -eq 'SUCCESS') ("status={0} runId={1}" -f $resendRun.status, $resend.data.runId)
# Real-machine note (#143): the POST answers the run object it created, so its `status` field still says
# RUNNING even though the run finished inside that same call. The run row is the truth; the console reads
# the run back, so the button behaves correctly.
Write-Host ("    (note: POST answered status={0}; the run row for runId={1} says {2})" -f $resend.data.status, $resend.data.runId, $resendRun.status)
$fRows2 = RunSteps $fRun.id 100 2
$fSummary2 = @($fRows2 | Where-Object { "$($_.messageId)" -like 'batch:*' })
Check 'B6-BATCH-FAIL the old batch is marked REDELIVERED after the resend' ($fSummary2.Count -eq 1 -and $fSummary2[0].status -eq 'REDELIVERED') ("status={0}" -f $fSummary2[0].status)

# ---------------------------------------------------------------- field scale: 1000 codes in ONE request (#143 AC5)
Write-Host 'B6-BULK  1000 codes -> ONE request, the whole payload lands in history'
$bulkCount = 1000
Mssql "DELETE FROM CustomerERP.dbo.MDM_Code WHERE FCode LIKE 'BULK-%'" | Out-Null
Mssql "INSERT INTO CustomerERP.dbo.MDM_Code (FCode, FObjectType, FObjectNo, FBatch, FStatus) SELECT 'BULK-' + RIGHT('0000' + CAST(n AS VARCHAR(4)), 4), 'PART', 'OBJ-' + CAST(n AS VARCHAR(4)), 'B-2026', 'NEW' FROM (SELECT TOP ($bulkCount) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n FROM sys.all_objects a CROSS JOIN sys.all_objects b) t" | Out-Null
$bulkSeeded = Mssql "SELECT COUNT(*) FROM CustomerERP.dbo.MDM_Code WHERE FCode LIKE 'BULK-%'"
Check 'B6-BULK 1000 fixture rows are in MDM_Code' ($bulkSeeded -match '^\s*1000\s*$') ("rows={0}" -f ($bulkSeeded -join '|'))
$jbulk = JobIdByName 'SH-B6-Register-Bulk'
Reset-BatchJob -JobId $jbulk
ClearFault
$bulkResult = Execute $jbulk
Check 'B6-BULK run succeeded at 1000-row scale' ($bulkResult -eq 'ok') $bulkResult
$bulkReqs = @(Received 'id-register')
Check 'B6-BULK sends the whole read batch in exactly ONE request' ($bulkReqs.Count -eq 1) ("requests={0}" -f $bulkReqs.Count)
$bulkCodesSent = 0
if ($bulkReqs.Count -ge 1) { $bulkCodesSent = @($bulkReqs[0].body.codes).Count }
Check 'B6-BULK that one request carries all 1000 codes' ($bulkCodesSent -eq 1000) ("codes={0}" -f $bulkCodesSent)
$bRun2 = LatestRun $jbulk
Check 'B6-BULK the run is recorded SUCCESS' ($bRun2 -and $bRun2.status -eq 'SUCCESS') ("status={0}" -f $bRun2.status)

# The summary row of a 4000-row run: walk the index (no snapshots, 1000 rows per page) until the batch row shows up.
$bulkSummaryId = 0
for ($p = 1; $p -le 6 -and $bulkSummaryId -eq 0; $p++) {
    $pageRows = @((Api GET "/api/history/runs/$($bRun2.id)/steps?page=$p&size=1000").data.records)
    $hit = @($pageRows | Where-Object { "$($_.messageId)" -like 'batch:*' }) | Select-Object -First 1
    if ($hit) { $bulkSummaryId = $hit.id }
    if ($pageRows.Count -lt 1000) { break }
}
Check 'B6-BULK the outlet keeps one summary row for the whole batch' ($bulkSummaryId -gt 0) ("summaryId={0}" -f $bulkSummaryId)
if ($bulkSummaryId -gt 0) {
    # exactly the call the snapshot panel makes when you open that step in the console
    $whole = (Api GET "/api/history/steps/$bulkSummaryId").data
    $payload = "$($whole.inputSnapshot)"
    $payloadItems = @()
    try { $payloadItems = @(($payload | ConvertFrom-Json).codes) } catch { }
    Check 'B6-BULK the whole request is stored in history (1000 items, one row)' ($payloadItems.Count -eq 1000) ("items={0}" -f $payloadItems.Count)
    Check 'B6-BULK the stored payload is a real big one (> 50 KB)' ($payload.Length -gt 50000) ("chars={0}" -f $payload.Length)
    $responseItems = @()
    try { $responseItems = @(("$($whole.dataSnapshot)" | ConvertFrom-Json).results) } catch { }
    Check 'B6-BULK the whole response is stored on the same row (1000 results)' ($responseItems.Count -eq 1000) ("results={0}" -f $responseItems.Count)
}
$bulkWritten = Mssql "SELECT COUNT(*) FROM CustomerERP.dbo.MDM_Code WHERE FCode LIKE 'BULK-%' AND FHandle IS NOT NULL"
Check 'B6-BULK wrote a handle back for every one of the 1000 rows' ($bulkWritten -match '^\s*1000\s*$') ("rows={0}" -f ($bulkWritten -join '|'))
# The 1000 rows are this section's fixture, not part of the seeded scenario: drop them and clear the
# stand-in's handle ledger so a second run of this script sees exactly the same numbers.
Mssql "DELETE FROM CustomerERP.dbo.MDM_Code WHERE FCode LIKE 'BULK-%'" | Out-Null
MySql "delete from edge_gateway.watermark where job_key='job:$jbulk'" | Out-Null
Invoke-RestMethod -Method Delete "$MesMock/__handles" -TimeoutSec 30 | Out-Null
Write-Host '    (1000-row fixture dropped, handle ledger cleared -> the run is repeatable)'

Write-Host ''
Write-Host ("== result: {0} passed, {1} failed ==" -f $script:pass, $script:fail)
if ($script:fail -gt 0) { exit 1 }
exit 0
