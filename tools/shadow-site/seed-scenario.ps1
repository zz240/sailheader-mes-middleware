# Shadow-site scenario setup: wire the customer-side SQL Server (Kingdee-shaped tables) and the
# MES-side stand-ins into runnable docking chains. REST only; no direct DB writes.
#
# NOTE (Windows PowerShell 5.1): this file is kept ASCII-only on purpose. PS 5.1 parses BOM-less
# files as the ANSI codepage (gb2312 here), so non-ASCII comments can corrupt parsing. If you add
# Chinese text, save the file as UTF-8 with BOM.
#
# Idempotent: a job with the same name is deleted and re-created (new job id => empty watermark,
# so the first run really reads the full set). Data sources are reused by name.
#
# Usage (from the repo root):
#   powershell -File tools/shadow-site/seed-scenario.ps1
#   powershell -File tools/shadow-site/seed-scenario.ps1 -BaseUrl http://localhost:8080
#   powershell -File tools/shadow-site/seed-scenario.ps1 -Teardown
#
# Chains (see docs/design/docking-scenarios-spec.md):
#   SH-A5-PO-to-MES                 north: SQL Server POOrder -> MES mock  (SQL Server read path: paging/watermark/probe/validate)
#   SH-A2-Item-to-CustomerERP       south: mes_db.mes_item -> CustomerERP.dbo.t_Item   (MERGE idempotent upsert)
#   SH-A3-WorkOrder-to-CustomerERP  south: mes_db.mes_work_order -> CustomerERP.dbo.MFG_WorkOrder
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [long]$SiteId = 1,
    [string]$SqlServerHost = 'localhost',
    [int]$SqlServerPort = 1433,
    [string]$SqlServerUser = 'sa',
    [string]$SqlServerPassword = 'EdgeShadow#2026',
    [string]$SqlServerDatabase = 'CustomerERP',
    [string]$MySqlHost = 'localhost',
    [int]$MySqlPort = 3306,
    [string]$MySqlUser = 'gateway',
    [string]$MySqlPassword = 'gateway',
    [string]$MesMockBase = 'http://localhost:9100',
    [string]$RabbitHost = 'localhost',
    [int]$RabbitPort = 5672,
    [switch]$Teardown
)

$ErrorActionPreference = 'Stop'

$dsNames = @{
    SqlServer = 'sh-customer-erp'
    MesHttp   = 'sh-mes-http'
    MesDb     = 'sh-mes-db'
    BoardDb   = 'sh-board-db'
    Mq        = 'sh-mq-local'
}
$jobNames = @('SH-A5-PO-to-MES', 'SH-A2-Item-to-CustomerERP', 'SH-A3-WorkOrder-to-CustomerERP',
              'SH-A4-Report-to-MES', 'SH-A7-QC-to-MES', 'SH-B6-ID-Register',
              'SH-A2MQ-Item-to-CustomerERP', 'SH-A4PUSH-Report-to-MES', 'SH-B1-DEVICE-UP',
              'SH-A7BIG-Whole-Report', 'SH-B6-Register-Batch', 'SH-B6-Register-Bulk',
              'SH-A2-Bom-Head-Lines')

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body)
    $uri = "$BaseUrl$Path"
    try {
        if ($null -eq $Body) {
            $resp = Invoke-RestMethod -Method $Method -Uri $uri -TimeoutSec 60
        } else {
            $json = $Body | ConvertTo-Json -Depth 12 -Compress
            # PS 5.1 encodes a string body as Latin-1; send explicit UTF-8 bytes so non-ASCII survives.
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
            $resp = Invoke-RestMethod -Method $Method -Uri $uri -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 60
        }
    } catch {
        $detail = $_.ErrorDetails.Message
        throw "$Method $Path failed: $($_.Exception.Message) $detail"
    }
    if ($resp -is [System.Management.Automation.PSCustomObject] -and $resp.PSObject.Properties.Name -contains 'data') {
        return $resp.data
    }
    return $resp
}

function Get-DataSources { return @(Invoke-Api GET '/api/data-sources') }

function Ensure-DataSource {
    param([string]$Name, [string]$Type, [string]$Address, $Addition)
    $hit = Get-DataSources | Where-Object { $_.name -eq $Name } | Select-Object -First 1
    if ($hit) {
        Write-Host ("  = data source exists: {0} (id={1}, {2})" -f $Name, $hit.id, $hit.type)
        return $hit.id
    }
    $created = Invoke-Api POST '/api/data-sources' @{
        name = $Name; siteId = $SiteId; type = $Type; address = $Address; addition = $Addition
    }
    Write-Host ("  + data source created: {0} (id={1}, {2})" -f $Name, $created.id, $Type)
    return $created.id
}

function Remove-JobByName {
    param([string]$Name)
    foreach ($j in @(Invoke-Api GET "/api/jobs?name=$Name")) {
        if ($j.name -eq $Name) {
            Invoke-Api DELETE "/api/jobs/$($j.id)" | Out-Null
            Write-Host ("  - old job deleted: {0} (id={1})" -f $Name, $j.id)
        }
    }
}

function New-ScenarioJob {
    param([string]$Name, [string]$Direction, [string]$Remarks, $Actions)
    Remove-JobByName -Name $Name
    $job = Invoke-Api POST '/api/jobs' @{
        name = $Name; siteId = $SiteId; status = 0; direction = $Direction
        scheduleType = 'NONE'; retryCount = 1; backoffMs = 500
        timezone = 'Asia/Shanghai'; remarks = $Remarks
    }
    $i = 1
    foreach ($a in $Actions) {
        Invoke-Api POST "/api/jobs/$($job.id)/actions" @{
            name = $a.name; type = $a.type; index = $i; addition = $a.addition
        } | Out-Null
        $i++
    }
    $validate = Invoke-Api POST "/api/jobs/$($job.id)/validate"
    Invoke-Api PUT "/api/jobs/$($job.id)/status" @{ status = 1 } | Out-Null
    Write-Host ("  + job enabled: {0} (id={1}, {2} actions)" -f $Name, $job.id, $Actions.Count)
    if ($validate -and $validate.errors -and $validate.errors.Count -gt 0) {
        foreach ($e in $validate.errors) { Write-Host ("    ! validate: [{0}] {1}" -f $e.scope, $e.message) }
    }
    return $job.id
}

# ---------- teardown ----------
if ($Teardown) {
    Write-Host 'Teardown:'
    foreach ($n in $jobNames) { Remove-JobByName -Name $n }
    foreach ($n in @($dsNames.Values)) {
        foreach ($d in @(Get-DataSources | Where-Object { $_.name -eq $n })) {
            Invoke-Api DELETE "/api/data-sources/$($d.id)" | Out-Null
            Write-Host ("  - data source deleted: {0} (id={1})" -f $n, $d.id)
        }
    }
    Write-Host 'done.'
    return
}

$sqlServerUrl = "jdbc:sqlserver://${SqlServerHost}:${SqlServerPort};databaseName=${SqlServerDatabase};encrypt=false;trustServerCertificate=true"
$mesDbUrl = "jdbc:mysql://${MySqlHost}:${MySqlPort}/mes_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8"
$boardDbUrl = "jdbc:mysql://${MySqlHost}:${MySqlPort}/board_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8"

Write-Host 'Data sources:'
$dsSqlServer = Ensure-DataSource -Name $dsNames.SqlServer -Type 'SQLSERVER' -Address $sqlServerUrl -Addition @{ username = $SqlServerUser; password = $SqlServerPassword }
$dsMesHttp = Ensure-DataSource -Name $dsNames.MesHttp -Type 'HTTP' -Address $MesMockBase -Addition @{}
$dsMesDb = Ensure-DataSource -Name $dsNames.MesDb -Type 'MYSQL' -Address $mesDbUrl -Addition @{ username = $MySqlUser; password = $MySqlPassword }
$dsBoardDb = Ensure-DataSource -Name $dsNames.BoardDb -Type 'MYSQL' -Address $boardDbUrl -Addition @{ username = $MySqlUser; password = $MySqlPassword }
$dsMq = Ensure-DataSource -Name $dsNames.Mq -Type 'RABBITMQ' -Address "amqp://${RabbitHost}:${RabbitPort}" -Addition @{ host = $RabbitHost; port = $RabbitPort; username = 'guest'; password = 'guest' }

Write-Host 'Connection tests:'
foreach ($id in @($dsSqlServer, $dsMesHttp, $dsMesDb, $dsBoardDb, $dsMq)) {
    $t = Invoke-Api POST "/api/data-sources/$id/test"
    Write-Host ("  ds {0}: ok={1} {2}" -f $id, $t.ok, $t.message)
}

Write-Host 'Jobs:'

# ---------- A5 north: SQL Server purchase orders -> MES ----------
# batchSize=2 is deliberate: 5 orders then span 3 batches, proving SQL Server OFFSET/FETCH paging works.
$a5 = @(
    @{ name = 'read-po'; type = 'READ_SQL'; addition = @{
        dataSourceId = $dsSqlServer
        sql = 'select o.FInterID, o.FBillNo, o.FDate, o.FStatus, o.FNote, s.FNumber as vendor_code from dbo.POOrder o left join dbo.t_Supplier s on o.FSupplyID = s.FItemID where {watermark}'
        watermarkType = 'SEQUENCE'; watermarkColumn = 'FInterID'; businessKey = 'FBillNo'; batchSize = 2 } },
    @{ name = 'map-mes-po'; type = 'FIELD_MAP'; addition = @{ rules = @(
        @{ sourceStep = 1; source = 'FBillNo'; target = 'sheet_no'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'vendor_code'; target = 'vendor_code'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FDate'; target = 'sheet_date'; transform = 'DATE'; datePattern = 'yyyy-MM-dd' },
        @{ sourceStep = 1; source = 'FStatus'; target = 'status'; transform = 'ENUM'; enumMap = @{ ACTIVE = '1'; CLOSED = '2' } },
        @{ sourceStep = 1; source = 'FBillNo'; target = 'source_ref'; transform = 'CONCAT'; parts = @('ERP-', @{ path = 'FBillNo' }) },
        @{ sourceStep = 1; source = 'FNote'; target = 'remark'; transform = 'STRING' }
    ) } },
    @{ name = 'push-mes'; type = 'HTTP_CALL'; addition = @{
        dataSourceId = $dsMesHttp; path = '/mes/api/purchase-order'; method = 'POST' } }
)
$a5Id = New-ScenarioJob -Name 'SH-A5-PO-to-MES' -Direction 'NORTH' -Remarks 'A5 customer ERP (SQL Server) purchase order -> MES' -Actions $a5

# ---------- A2 south: MES master data -> CustomerERP.dbo.t_Item (MERGE) ----------
$a2 = @(
    @{ name = 'read-items'; type = 'READ_SQL'; addition = @{
        dataSourceId = $dsMesDb
        sql = 'select FNumber, FName, FSpec, FUnit, FMaterial, FVersion, FModifyDate from mes_item where {watermark}'
        watermarkType = 'TIMESTAMP'; watermarkColumn = 'FModifyDate'; businessKey = 'FNumber'; batchSize = 10 } },
    @{ name = 'map-items'; type = 'FIELD_MAP'; addition = @{ rules = @(
        @{ sourceStep = 1; source = 'FNumber'; target = 'FNumber'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FName'; target = 'FName'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FSpec'; target = 'FSpec'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FUnit'; target = 'FUnit'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FMaterial'; target = 'FMaterial'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FVersion'; target = 'FVersion'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FModifyDate'; target = 'FModifyDate'; transform = 'DATE'; datePattern = 'yyyy-MM-dd HH:mm:ss' }
    ) } },
    @{ name = 'upsert-items'; type = 'WRITE_UPSERT'; addition = @{
        dataSourceId = $dsSqlServer; tableName = 'dbo.t_Item'; idempotencyKey = 'FNumber' } }
)
$a2Id = New-ScenarioJob -Name 'SH-A2-Item-to-CustomerERP' -Direction 'SOUTH' -Remarks 'A2 MES master data -> customer ERP t_Item (MERGE)' -Actions $a2

# ---------- A3 south: MES work orders -> CustomerERP.dbo.MFG_WorkOrder ----------
$a3 = @(
    @{ name = 'read-wo'; type = 'READ_SQL'; addition = @{
        dataSourceId = $dsMesDb
        sql = 'select FInterID, FBillNo, FItemNumber, FQty, FPlanStart, FPlanEnd, FStatus from mes_work_order where {watermark}'
        watermarkType = 'SEQUENCE'; watermarkColumn = 'FInterID'; businessKey = 'FBillNo'; batchSize = 10 } },
    @{ name = 'map-wo'; type = 'FIELD_MAP'; addition = @{ rules = @(
        @{ sourceStep = 1; source = 'FBillNo'; target = 'FBillNo'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FItemNumber'; target = 'FItemNumber'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FQty'; target = 'FQty'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FPlanStart'; target = 'FPlanStart'; transform = 'DATE'; datePattern = 'yyyy-MM-dd HH:mm:ss' },
        @{ sourceStep = 1; source = 'FPlanEnd'; target = 'FPlanEnd'; transform = 'DATE'; datePattern = 'yyyy-MM-dd HH:mm:ss' },
        @{ sourceStep = 1; source = 'FStatus'; target = 'FStatus'; transform = 'STRING' }
    ) } },
    @{ name = 'upsert-wo'; type = 'WRITE_UPSERT'; addition = @{
        dataSourceId = $dsSqlServer; tableName = 'dbo.MFG_WorkOrder'; idempotencyKey = 'FBillNo' } }
)
$a3Id = New-ScenarioJob -Name 'SH-A3-WorkOrder-to-CustomerERP' -Direction 'SOUTH' -Remarks 'A3 MES work order -> customer ERP MFG_WorkOrder' -Actions $a3

# ---------- A4 north: SQL Server work reports -> MES (composite business key flattened) ----------
$a4 = @(
    @{ name = 'read-reports'; type = 'READ_SQL'; addition = @{
        dataSourceId = $dsSqlServer
        sql = 'select FInterID, FBillNo, FSeq, FQtyOK, FQtyNG, FWorker, FEquipment, FReportTime from dbo.MFG_Report where {watermark}'
        watermarkType = 'SEQUENCE'; watermarkColumn = 'FInterID'; businessKey = 'FBillNo'; batchSize = 2 } },
    @{ name = 'map-report'; type = 'FIELD_MAP'; addition = @{ rules = @(
        @{ sourceStep = 1; source = 'FBillNo'; target = 'workOrderNo'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FSeq'; target = 'operationSeq'; transform = 'INT' },
        @{ sourceStep = 1; source = 'FQtyOK'; target = 'qtyOk'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FQtyNG'; target = 'qtyNg'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FWorker'; target = 'worker'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FEquipment'; target = 'equipment'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FReportTime'; target = 'reportTime'; transform = 'DATE'; datePattern = 'yyyy-MM-dd HH:mm:ss' },
        @{ sourceStep = 1; source = 'FBillNo'; target = 'reportKey'; transform = 'CONCAT'; parts = @(@{ path = 'FBillNo' }, '#', @{ path = 'FSeq' }) }
    ) } },
    @{ name = 'push-report'; type = 'HTTP_CALL'; addition = @{
        dataSourceId = $dsMesHttp; path = '/mes/api/report'; method = 'POST' } }
)
$a4Id = New-ScenarioJob -Name 'SH-A4-Report-to-MES' -Direction 'NORTH' -Remarks 'A4 work report (SQL Server) -> MES; composite key FBillNo+FSeq flattened into reportKey' -Actions $a4

# ---------- A7 north: QC/test report, 240 detail rows flattened, 5 pages of 50 ----------
# QC-SH-0003 (the 3000-row report) is left to the big-payload variant below, so this one stays at 240 rows.
$a7 = @(
    @{ name = 'read-qc'; type = 'READ_SQL'; addition = @{
        dataSourceId = $dsSqlServer
        sql = 'select d.FDetailID, h.FBillNo, h.FMoldNo, h.FSampleNo, h.FResult, d.FSeq, d.FItemCode, d.FNominal, d.FUpper, d.FLower, d.FMeasured, d.FJudge from dbo.QC_ReportDetail d join dbo.QC_Report h on h.FBillNo = d.FBillNo where {watermark} and h.FBillNo <> ''QC-SH-0003'''
        watermarkType = 'SEQUENCE'; watermarkColumn = 'FDetailID'; businessKey = 'FBillNo'; batchSize = 50 } },
    @{ name = 'map-qc'; type = 'FIELD_MAP'; addition = @{ rules = @(
        @{ sourceStep = 1; source = 'FBillNo'; target = 'reportNo'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FMoldNo'; target = 'moldNo'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FSampleNo'; target = 'sampleNo'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FResult'; target = 'result'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FSeq'; target = 'itemSeq'; transform = 'INT' },
        @{ sourceStep = 1; source = 'FItemCode'; target = 'itemCode'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FNominal'; target = 'nominal'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FUpper'; target = 'upper'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FLower'; target = 'lower'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FMeasured'; target = 'measured'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FJudge'; target = 'judge'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FBillNo'; target = 'detailKey'; transform = 'CONCAT'; parts = @(@{ path = 'FBillNo' }, '#', @{ path = 'FSeq' }) }
    ) } },
    @{ name = 'push-qc'; type = 'HTTP_CALL'; addition = @{
        dataSourceId = $dsMesHttp; path = '/mes/api/qc-report'; method = 'POST' } }
)
$a7Id = New-ScenarioJob -Name 'SH-A7-QC-to-MES' -Direction 'NORTH' -Remarks 'A7 QC/test report: 240 detail rows flattened (header denormalised per row)' -Actions $a7

# ---------- B6 north: one-object-one-code registration + handle write-back ----------
# #123: the HTTP response is addressable on the chain as context[<HTTP_CALL index>], so the handle the
# id service returns can be written back to MDM_Code.FHandle inside the same chain (no second chain).
$b6 = @(
    @{ name = 'read-codes'; type = 'READ_SQL'; addition = @{
        dataSourceId = $dsSqlServer
        sql = 'select FID, FCode, FObjectType, FObjectNo, FBatch from dbo.MDM_Code where {watermark}'
        watermarkType = 'SEQUENCE'; watermarkColumn = 'FID'; businessKey = 'FCode'; batchSize = 10 } },
    @{ name = 'wrap-codes'; type = 'FIELD_MAP'; addition = @{
        mode = 'EXPRESSION'
        expression = '{ "codes": [ { "FCode": $s_1.FCode, "objectType": $s_1.FObjectType, "objectNo": $s_1.FObjectNo, "batch": $s_1.FBatch } ] }' } },
    @{ name = 'push-register'; type = 'HTTP_CALL'; addition = @{
        dataSourceId = $dsMesHttp; path = '/mes/api/id-register'; method = 'POST' } },
    # sourceStep 3 = push-register: the mock answers {code,count,results:[{FCode,handle}]}
    @{ name = 'map-handle'; type = 'FIELD_MAP'; addition = @{ rules = @(
        @{ sourceStep = 3; source = 'results[0].FCode'; target = 'FCode'; transform = 'STRING' },
        @{ sourceStep = 3; source = 'results[0].handle'; target = 'FHandle'; transform = 'STRING' }
    ) } },
    @{ name = 'write-handle'; type = 'WRITE_UPSERT'; addition = @{
        dataSourceId = $dsSqlServer; tableName = 'dbo.MDM_Code'; idempotencyKey = 'FCode' } }
)
$b6Id = New-ScenarioJob -Name 'SH-B6-ID-Register' -Direction 'NORTH' -Remarks 'B6 one-object-one-code registration + FHandle write-back from the chain response (#123)' -Actions $b6

# ---------- entry variant 1: MES pushes master data over MQ -> customer SQL Server ----------
# Same target as A2 but the entry is MQ_CONSUME (the MES side initiates), so this proves the MQ entry
# reaches the SQL Server MERGE write path.
$a2mq = @(
    @{ name = 'consume-items'; type = 'MQ_CONSUME'; addition = @{
        dataSourceId = $dsMq; queue = 'sh.mes.item.to.edge'; businessKey = 'FNumber' } },
    @{ name = 'map-items-mq'; type = 'FIELD_MAP'; addition = @{ rules = @(
        @{ sourceStep = 1; source = 'FNumber'; target = 'FNumber'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FName'; target = 'FName'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FSpec'; target = 'FSpec'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FUnit'; target = 'FUnit'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FMaterial'; target = 'FMaterial'; transform = 'STRING' },
        @{ sourceStep = 1; source = 'FVersion'; target = 'FVersion'; transform = 'STRING' }
    ) } },
    @{ name = 'upsert-items-mq'; type = 'WRITE_UPSERT'; addition = @{
        dataSourceId = $dsSqlServer; tableName = 'dbo.t_Item'; idempotencyKey = 'FNumber' } }
)
$a2mqId = New-ScenarioJob -Name 'SH-A2MQ-Item-to-CustomerERP' -Direction 'SOUTH' -Remarks 'A2 entry variant: MES pushes master data on MQ -> SQL Server MERGE' -Actions $a2mq

# ---------- entry variant 2: work terminal POSTs straight to the gateway ----------
# Chain head is FIELD_MAP, so the trigger body becomes the chain-head input (context[0] -> sourceStep 0).
$a4push = @(
    @{ name = 'map-report-push'; type = 'FIELD_MAP'; addition = @{ rules = @(
        @{ sourceStep = 0; source = 'FBillNo'; target = 'workOrderNo'; transform = 'STRING' },
        @{ sourceStep = 0; source = 'FSeq'; target = 'operationSeq'; transform = 'INT' },
        @{ sourceStep = 0; source = 'FQtyOK'; target = 'qtyOk'; transform = 'STRING' },
        @{ sourceStep = 0; source = 'FWorker'; target = 'worker'; transform = 'STRING' },
        @{ sourceStep = 0; source = 'FEquipment'; target = 'equipment'; transform = 'STRING' },
        @{ sourceStep = 0; source = 'FBillNo'; target = 'reportKey'; transform = 'CONCAT'; parts = @(@{ path = 'FBillNo' }, '#', @{ path = 'FSeq' }) }
    ) } },
    @{ name = 'push-report-push'; type = 'HTTP_CALL'; addition = @{
        dataSourceId = $dsMesHttp; path = '/mes/api/report'; method = 'POST' } }
)
$a4pushId = New-ScenarioJob -Name 'SH-A4PUSH-Report-to-MES' -Direction 'NORTH' -Remarks 'A4 entry variant: terminal POSTs to /api/external/jobs/{id}/trigger' -Actions $a4push

# ---------- B1: device point -> cloud push + local board upsert ----------
# The bridge (tools/shadow-site/modbus-bridge.cjs) posts ONE point per trigger call; the chain head is
# FIELD_MAP so the body is the chain-head input (sourceStep 0). metric_key is the flattened composite key.
# Order is deliberately the field's most common shape (push first, write local second): before #126 that
# shape died with an NPE in WRITE_UPSERT, and the acceptance run now proves it no longer needs swapping.
$b1 = @(
    @{ name = 'map-device-point'; type = 'FIELD_MAP'; addition = @{ rules = @(
        @{ sourceStep = 0; source = 'device_no'; target = 'device_no'; transform = 'STRING' },
        @{ sourceStep = 0; source = 'point'; target = 'point'; transform = 'STRING' },
        @{ sourceStep = 0; source = 'ts'; target = 'ts'; transform = 'STRING' },
        @{ sourceStep = 0; source = 'value'; target = 'value'; transform = 'STRING' },
        @{ sourceStep = 0; source = 'quality'; target = 'quality'; transform = 'STRING' },
        @{ sourceStep = 0; source = 'device_no'; target = 'metric_key'; transform = 'CONCAT'; parts = @(@{ path = 'device_no' }, ':', @{ path = 'point' }, ':', @{ path = 'ts' }) }
    ) } },
    @{ name = 'push-cloud'; type = 'HTTP_CALL'; addition = @{
        dataSourceId = $dsMesHttp; path = '/mes/api/device-metric'; method = 'POST' } },
    @{ name = 'upsert-board'; type = 'WRITE_UPSERT'; addition = @{
        dataSourceId = $dsBoardDb; tableName = 'dev_metric'; idempotencyKey = 'metric_key' } }
)
$b1Id = New-ScenarioJob -Name 'SH-B1-DEVICE-UP' -Direction 'NORTH' -Remarks 'B1 device point: push to cloud, then upsert into local board db (the field shape that used to NPE; #126 fixed, no order swap needed)' -Actions $b1

# ---------- A7 big-payload variant: the WHOLE report in one message ----------
# Source side aggregates the detail rows into one JSON string (FOR JSON PATH); the EXPRESSION mapping
# turns it back into an array with $parse and maps it. One report = one message = one HTTP call.
$a7big = @(
    @{ name = 'read-qc-whole'; type = 'READ_SQL'; addition = @{
        dataSourceId = $dsSqlServer
        sql = 'select h.FInterID, h.FBillNo, h.FMoldNo, h.FSampleNo, h.FResult, (select d.FSeq, d.FItemCode, d.FNominal, d.FUpper, d.FLower, d.FMeasured, d.FJudge from dbo.QC_ReportDetail d where d.FBillNo = h.FBillNo order by d.FSeq for json path) as lines_json from dbo.QC_Report h where {watermark}'
        watermarkType = 'SEQUENCE'; watermarkColumn = 'FInterID'; businessKey = 'FBillNo'; batchSize = 10 } },
    @{ name = 'wrap-qc-whole'; type = 'FIELD_MAP'; addition = @{
        mode = 'EXPRESSION'
        expression = '{ "reportNo": $s_1.FBillNo, "moldNo": $s_1.FMoldNo, "sampleNo": $s_1.FSampleNo, "result": $s_1.FResult, "lineCount": $count($parse($s_1.lines_json)), "lines": $map($parse($s_1.lines_json), function($l, $i) { { "itemSeq": $l.FSeq, "itemCode": $l.FItemCode, "nominal": $l.FNominal, "upper": $l.FUpper, "lower": $l.FLower, "measured": $l.FMeasured, "judge": $l.FJudge } }) }' } },
    @{ name = 'push-qc-whole'; type = 'HTTP_CALL'; addition = @{
        dataSourceId = $dsMesHttp; path = '/mes/api/qc-report'; method = 'POST' } }
)
$a7bigId = New-ScenarioJob -Name 'SH-A7BIG-Whole-Report' -Direction 'NORTH' -Remarks 'A7 big-payload variant: whole report (up to 3000 lines) aggregated in SQL, $parse + $map, one message per report' -Actions $a7big

# ---------- B6 batch variant: the whole read batch in ONE request (#143) ----------
# #142 made this shape possible: READ_SQL (a batch of codes) -> FIELD_MAP (one payload per code)
# -> AGGREGATE (pack them into {"codes":[...]}) -> HTTP_CALL (sent once) -> RESTORE (pair the handles
# back by FCode) -> FIELD_MAP -> WRITE_UPSERT (each row gets its own handle).
# The read batch size IS the request size (ADR-0017 decision 5: the barrier is "the upstream read batch
# finished"; there is no count threshold and no timeout), so batchSize=1000 sends the 20 shadow-site
# codes in one request -- and the 1000-row bulk variant below in one request as well.
function New-B6BatchActions {
    param([string]$Sql, [int]$BatchSize = 1000)
    return @(
        @{ name = 'read-codes'; type = 'READ_SQL'; addition = @{
            dataSourceId = $dsSqlServer; sql = $Sql
            watermarkType = 'SEQUENCE'; watermarkColumn = 'FID'; businessKey = 'FCode'; batchSize = $BatchSize } },
        @{ name = 'wrap-code'; type = 'FIELD_MAP'; addition = @{ rules = @(
            @{ sourceStep = 1; source = 'FCode'; target = 'FCode'; transform = 'STRING' },
            @{ sourceStep = 1; source = 'FObjectType'; target = 'objectType'; transform = 'STRING' },
            @{ sourceStep = 1; source = 'FObjectNo'; target = 'objectNo'; transform = 'STRING' },
            @{ sourceStep = 1; source = 'FBatch'; target = 'batch'; transform = 'STRING' }
        ) } },
        @{ name = 'pack-codes'; type = 'AGGREGATE'; addition = @{ bodyField = 'codes' } },
        @{ name = 'push-register-batch'; type = 'HTTP_CALL'; addition = @{
            dataSourceId = $dsMesHttp; path = '/mes/api/id-register'; method = 'POST' } },
        # sourceStep 4 = the outlet: the stand-in answers {code,count,results:[{FCode,handle}]}
        @{ name = 'restore-handles'; type = 'ONE_TO_MANY'; addition = @{ mode = 'restore';
            sourceStep = 4; sourcePath = 'results'; resultKeyField = 'FCode'; attachAs = 'registration' } },
        @{ name = 'map-handle-batch'; type = 'FIELD_MAP'; addition = @{ rules = @(
            @{ sourceStep = 1; source = 'FCode'; target = 'FCode'; transform = 'STRING' },
            @{ sourceStep = 5; source = 'handle'; target = 'FHandle'; transform = 'STRING' }
        ) } },
        @{ name = 'write-handle-batch'; type = 'WRITE_UPSERT'; addition = @{
            dataSourceId = $dsSqlServer; tableName = 'dbo.MDM_Code'; idempotencyKey = 'FCode' } }
    )
}

$b6batch = New-B6BatchActions -Sql 'select FID, FCode, FObjectType, FObjectNo, FBatch from dbo.MDM_Code where {watermark}'
$b6batchId = New-ScenarioJob -Name 'SH-B6-Register-Batch' -Direction 'NORTH' -Remarks 'B6 batch variant (#143): 20 codes registered in ONE request, per-row handle write-back' -Actions $b6batch

# Same shape at the field scale (the B6 spec says 1000 codes per request). The BULK- rows are inserted and
# dropped by verify-scenario.ps1; the prefix filter keeps this job independent of the 20 seeded codes.
$b6bulk = New-B6BatchActions -Sql "select FID, FCode, FObjectType, FObjectNo, FBatch from dbo.MDM_Code where {watermark} and FCode like 'BULK-%'"
$b6bulkId = New-ScenarioJob -Name 'SH-B6-Register-Bulk' -Direction 'NORTH' -Remarks 'B6 batch variant at 1000-row scale (#143): one request carries the whole read batch' -Actions $b6bulk

# ---------- A2 head-and-lines variant: one report -> one head row + N line rows (#143) ----------
# The shape the spec calls A2 head-and-lines: the SQL joins head and lines and aggregates the lines into
# one JSON column, the mapping turns it back into a real array, then the SAME message writes the head row
# and is split (SPLIT, #140) into one message per line before the line rows are written.
$a2hl = @(
    @{ name = 'read-bom'; type = 'READ_SQL'; addition = @{
        dataSourceId = $dsMesDb
        sql = "select h.FBOMNo, h.FMoldNo, h.FVersion, h.FModifyDate, count(l.FSeq) as FLineCount, JSON_UNQUOTE(JSON_ARRAYAGG(JSON_OBJECT('FSeq', l.FSeq, 'FItemNumber', l.FItemNumber, 'FQty', l.FQty, 'FUnit', l.FUnit))) as lines_json from mes_bom_head h join mes_bom_line l on l.FBOMNo = h.FBOMNo where {watermark} group by h.FBOMNo, h.FMoldNo, h.FVersion, h.FModifyDate"
        watermarkType = 'TIMESTAMP'; watermarkColumn = 'FModifyDate'; businessKey = 'FBOMNo'; batchSize = 10 } },
    @{ name = 'assemble-bom'; type = 'FIELD_MAP'; addition = @{
        mode = 'EXPRESSION'; expression = '$merge([$s_1, {"lines": $parse($s_1.lines_json)}])' } },
    # head row: only the head columns. WRITE_UPSERT writes every key of the body, so the body must not
    # carry the lines array here (the array stays reachable as context[2] for the split below).
    @{ name = 'map-head'; type = 'FIELD_MAP'; addition = @{ rules = @(
        @{ sourceStep = 2; source = 'FBOMNo'; target = 'FBOMNo'; transform = 'STRING' },
        @{ sourceStep = 2; source = 'FMoldNo'; target = 'FMoldNo'; transform = 'STRING' },
        @{ sourceStep = 2; source = 'FVersion'; target = 'FVersion'; transform = 'STRING' },
        @{ sourceStep = 2; source = 'FLineCount'; target = 'FLineCount'; transform = 'STRING' },
        @{ sourceStep = 2; source = 'FModifyDate'; target = 'FModifyDate'; transform = 'DATE'; datePattern = 'yyyy-MM-dd HH:mm:ss' }
    ) } },
    @{ name = 'write-head'; type = 'WRITE_UPSERT'; addition = @{
        dataSourceId = $dsBoardDb; tableName = 'bom_head_t'; idempotencyKey = 'FBOMNo' } },
    @{ name = 'split-lines'; type = 'ONE_TO_MANY'; addition = @{ mode = 'split';
        sourceStep = 2; sourcePath = 'lines'; elementKey = 'FItemNumber'; keepParent = $true } },
    @{ name = 'map-line'; type = 'FIELD_MAP'; addition = @{ rules = @(
        @{ sourceStep = 5; source = 'FBOMNo'; target = 'FBOMNo'; transform = 'STRING' },
        @{ sourceStep = 5; source = 'FSeq'; target = 'FSeq'; transform = 'STRING' },
        @{ sourceStep = 5; source = 'FItemNumber'; target = 'FItemNumber'; transform = 'STRING' },
        @{ sourceStep = 5; source = 'FQty'; target = 'FQty'; transform = 'STRING' },
        @{ sourceStep = 5; source = 'FUnit'; target = 'FUnit'; transform = 'STRING' },
        @{ sourceStep = 5; source = 'FLineCount'; target = 'FLineCount'; transform = 'STRING' },
        @{ sourceStep = 5; source = 'FSeq'; target = 'FLineKey'; transform = 'CONCAT';
           parts = @(@{ path = 'FBOMNo' }, '#', @{ path = 'FSeq' }) }
    ) } },
    @{ name = 'write-lines'; type = 'WRITE_UPSERT'; addition = @{
        dataSourceId = $dsBoardDb; tableName = 'bom_line_t'; idempotencyKey = 'FLineKey' } }
)
$a2hlId = New-ScenarioJob -Name 'SH-A2-Bom-Head-Lines' -Direction 'SOUTH' -Remarks 'A2 head-and-lines variant (#143): one report -> one head row + N line rows (SPLIT)' -Actions $a2hl

Write-Host ''
Write-Host ("ready. job ids: A5={0} A2={1} A3={2} A4={3} A7={4} B6={5} A2MQ={6} A4PUSH={7} B1={8} A7BIG={9}" -f $a5Id, $a2Id, $a3Id, $a4Id, $a7Id, $b6Id, $a2mqId, $a4pushId, $b1Id, $a7bigId)
Write-Host ("            #143: B6BATCH={0} B6BULK={1} A2HL={2}" -f $b6batchId, $b6bulkId, $a2hlId)
Write-Host ("execute one: Invoke-RestMethod -Method Post -Uri `"$BaseUrl/api/jobs/<id>/execute`" -ContentType 'application/json' -Body '{{}}'")
Write-Host ("MES received: Invoke-RestMethod `"$MesMockBase/__received?limit=10`" | ConvertTo-Json -Depth 6")
Write-Host ("runs       : Invoke-RestMethod `"$BaseUrl/api/history/runs?jobId=<id>`" | ConvertTo-Json -Depth 6")
