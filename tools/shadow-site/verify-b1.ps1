# B1 acceptance run: device (Modbus) -> collection bridge -> gateway -> local board DB + cloud.
#
# ASCII-only on purpose (Windows PowerShell 5.1 parses BOM-less files as ANSI).
#
# Usage (repo root):
#   powershell -File tools/shadow-site/verify-b1.ps1
#   powershell -File tools/shadow-site/verify-b1.ps1 -KeepRunning      # leave sim + bridge up afterwards
#   powershell -File tools/shadow-site/verify-b1.ps1 -Seconds 40       # longer steady-state window
#
# What it checks:
#   * bridge reads the Modbus slave (both units) and posts one message per point
#   * board DB rows == posted points, same count per device/point
#   * the device poll counter arrives monotonically (each row is a fresh sample, no gaps/dupes)
#   * cloud stand-in receives the same points, with the flattened composite key
#   * device outage (simulator stopped for a moment) shows up as STALE, not as zeros
#
# Not covered here (needs the gateway process, which this script does not own):
#   * gateway outage buffering (bridge queue) -- run it by hand, see README "B1 设备侧".
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$MesMock = 'http://localhost:9100',
    [string]$MysqlContainer = 'edge-gateway-mysql',
    [int]$ModbusPort = 1502,
    [int]$DeviceAdminPort = 9201,
    [int]$BridgeAdminPort = 9202,
    [string]$BridgeHost = '127.0.0.1',
    [int]$PollMs = 5000,
    [int]$Seconds = 25,
    [switch]$KeepRunning,
    # -GatewayOutage: stop the real gateway process (port 8080) and start it again with maven, instead of
    # the default "disable the job" injection. Slower (~1 min) and it does kill the process, but it is
    # the closest thing to a real site outage.
    [switch]$GatewayOutage
)

$ErrorActionPreference = 'Continue'
$script:pass = 0
$script:fail = 0
$script:dir = Split-Path -Parent $PSCommandPath                                  # tools/shadow-site
$script:repo = Split-Path -Parent (Split-Path -Parent $script:dir)               # repo root

function Check {
    param([string]$Name, $Ok, [string]$Detail = '')
    if ($Ok) { $script:pass++; Write-Host ("  PASS  {0}" -f $Name) }
    else { $script:fail++; Write-Host ("  FAIL  {0}  {1}" -f $Name, $Detail) }
}

function PortOpen {
    param([int]$Port)
    $c = New-Object System.Net.Sockets.TcpClient
    try { $c.Connect('127.0.0.1', $Port); return $true } catch { return $false } finally { $c.Dispose() }
}

function MySql {
    param([string]$Sql)
    $out = docker exec $MysqlContainer mysql -uroot -proot -N -B -e $Sql 2>&1
    return @($out | Where-Object { "$_" -notmatch 'Warning' })
}

function BridgeStats {
    return Invoke-RestMethod "http://localhost:$BridgeAdminPort/" -TimeoutSec 10
}

function ReceivedCount {
    param([string]$PathFilter)
    return @((Invoke-RestMethod "$MesMock/__received?path=$PathFilter&limit=5000" -TimeoutSec 30).records).Count
}

function Api {
    param([string]$Method, [string]$Path, $Body)
    $uri = "$BaseUrl$Path"
    if ($null -eq $Body) { return Invoke-RestMethod -Method $Method -Uri $uri -TimeoutSec 60 }
    $json = $Body | ConvertTo-Json -Depth 8 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    return Invoke-RestMethod -Method $Method -Uri $uri -ContentType 'application/json; charset=utf-8' -Body $bytes -TimeoutSec 60
}

# Per-device poll counter must arrive monotonically: gaps or dupes mean the collection/delivery
# pipeline lost or repeated a sample. Returns the offending sequences (empty = good).
function CounterGaps {
    $seq = MySql "select device_no, group_concat(value order by cast(value as unsigned)) from board_db.dev_metric where point='counter' group by device_no"
    $bad = @()
    foreach ($line in @($seq)) {
        $parts = $line -split "`t"
        if (@($parts).Count -lt 2) { continue }
        $nums = @($parts[1] -split ',' | ForEach-Object { [int][double]$_ })
        for ($i = 1; $i -lt $nums.Count; $i++) {
            if ($nums[$i] -ne $nums[$i - 1] + 1) { $bad += ("{0}:{1}" -f $parts[0], ($nums -join ',')) }
        }
    }
    return $bad
}

# Start-Process on this box dies with "Key already in dictionary: NO_PROXY/no_proxy" unless the
# lowercase duplicates are dropped first (PowerShell builds the child env dict case-insensitively).
foreach ($n in @('no_proxy', 'http_proxy', 'https_proxy', 'all_proxy')) {
    Remove-Item "Env:$n" -ErrorAction SilentlyContinue
}

Write-Host ("== B1 acceptance ==  gateway={0} modbus={1} bridge-admin={2}" -f $BaseUrl, $ModbusPort, $BridgeAdminPort)

# ---------------------------------------------------------------- preconditions
try { Invoke-RestMethod "$BaseUrl/api/jobs" -TimeoutSec 5 | Out-Null }
catch { Write-Host 'gateway is not reachable; start it first (see README)'; exit 2 }
if ((ReceivedCount 'device-metric') -lt 0) { }   # touch the mock so a dead mock fails loudly below

$job = @((Invoke-RestMethod "$BaseUrl/api/jobs?name=SH-B1-DEVICE-UP" -TimeoutSec 30).data) |
    Where-Object { $_.name -eq 'SH-B1-DEVICE-UP' } | Select-Object -First 1
if (-not $job) { Write-Host 'job SH-B1-DEVICE-UP not found (run seed-scenario.ps1 first)'; exit 2 }
$jobId = $job.id

foreach ($p in @($ModbusPort, $DeviceAdminPort, $BridgeAdminPort)) {
    if (PortOpen $p) {
        Write-Host ("port {0} is already in use - stop the previous simulator/bridge first" -f $p)
        exit 2
    }
}

$keyResp = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/sites/1/api-key" -TimeoutSec 30
$key = $keyResp.data.apiKey
if ([string]::IsNullOrEmpty($key)) { Write-Host 'could not issue a site API key'; exit 2 }

MySql 'truncate table board_db.dev_metric' | Out-Null
Invoke-RestMethod -Method Delete "$MesMock/__received" -TimeoutSec 30 | Out-Null
Write-Host ("job id={0}, board table truncated, mock cleared" -f $jobId)

# ---------------------------------------------------------------- start simulator + bridge
$deviceOut = Join-Path $script:repo '.scratch\b1-device.out.log'
$deviceErr = Join-Path $script:repo '.scratch\b1-device.err.log'
$bridgeOut = Join-Path $script:repo '.scratch\b1-bridge.out.log'
$bridgeErr = Join-Path $script:repo '.scratch\b1-bridge.err.log'

$sim = Start-Process -FilePath 'node' -ArgumentList (Join-Path $script:dir 'modbus-device.cjs') `
    -PassThru -WindowStyle Hidden -RedirectStandardOutput $deviceOut -RedirectStandardError $deviceErr
$waited = 0
while (-not (PortOpen $DeviceAdminPort) -and $waited -lt 20) { Start-Sleep -Seconds 1; $waited++ }
Check 'modbus device simulator is up' (PortOpen $DeviceAdminPort) ("admin port {0}" -f $DeviceAdminPort)

$env:JOB_ID = "$jobId"
$env:GATEWAY = $BaseUrl
$env:API_KEY = $key
$env:DEVICE_UNITS = '1,2'
$env:POLL_MS = "$PollMs"
$env:DEVICE_HOST = $BridgeHost
$env:DEVICE_PORT = "$ModbusPort"
$env:ADMIN_PORT = "$BridgeAdminPort"
$bridge = Start-Process -FilePath 'node' -ArgumentList (Join-Path $script:dir 'modbus-bridge.cjs') `
    -PassThru -WindowStyle Hidden -RedirectStandardOutput $bridgeOut -RedirectStandardError $bridgeErr
$waited = 0
while (-not (PortOpen $BridgeAdminPort) -and $waited -lt 20) { Start-Sleep -Seconds 1; $waited++ }
Check 'collection bridge is up' (PortOpen $BridgeAdminPort) ("admin port {0}" -f $BridgeAdminPort)

try {
    # ------------------------------------------------------------ steady state
    Start-Sleep -Seconds $Seconds
    $stats = BridgeStats
    Write-Host ("    (bridge: cycles={0} posted={1} buffered={2} failed={3})" -f $stats.cycles, $stats.posted, $stats.buffered, $stats.failed)
    Check 'bridge reads the slave (no modbus errors)' (-not $stats.lastError -and $stats.posted -ge 16) ("lastError='{0}' posted={1}" -f $stats.lastError, $stats.posted)
    Check 'bridge keeps nothing buffered while the gateway is up' ($stats.buffered -eq 0) ("buffered={0}" -f $stats.buffered)

    $rows = MySql 'select count(*) from board_db.dev_metric'
    Check 'board rows == posted points' (("$rows" -match ('^\s*' + $stats.posted + '\s*$'))) ("rows={0} posted={1}" -f ($rows -join '|'), $stats.posted)

    $shape = MySql "select device_no, point, count(*) c from board_db.dev_metric group by device_no, point order by device_no, point"
    Check 'both devices x 4 points land in the board' (@($shape).Count -eq 8) ("groups={0}" -f @($shape).Count)
    $even = @($shape | Where-Object { ($_ -split "`t")[2] -lt 2 })
    Check 'each device/point has at least 2 samples' ($even.Count -eq 0) ("short={0}" -f $even.Count)

    # poll counter must arrive monotonically per device: proves the rows are distinct fresh samples
    $bad = CounterGaps
    Check 'poll counter arrives monotonically (no gaps, no dupes)' ($bad.Count -eq 0) ($bad -join ' / ')

    $quality = MySql "select quality, count(*) from board_db.dev_metric group by quality"
    $nonGood = @($quality | Where-Object { ($_ -split "`t")[0] -ne 'GOOD' })
    Check 'steady state is all GOOD' ($nonGood.Count -eq 0) (($quality -join ' | '))

    $cloud = ReceivedCount 'device-metric'
    Check 'cloud stand-in got the same number of points' ($cloud -eq [int]("$rows" -replace '\D', '')) ("cloud={0} board={1}" -f $cloud, ($rows -join '|'))

    $keyShape = MySql "select metric_key from board_db.dev_metric order by ts desc limit 1"
    Check 'composite key is device:point:timestamp' (("$keyShape" -match '^DEV-\d:[a-z_]+:\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$')) ("key={0}" -f ($keyShape -join '|'))

    # ------------------------------------------------------------ gateway outage -> bridge buffers (D1)
    # Two flavours of "the target is unreachable":
    #   default        : disable the job -> the trigger answers 400 -> the bridge takes the very same
    #                    failure path (reject -> queue -> flush). Fast, deterministic, no process juggling.
    #   -GatewayOutage : really stop the process listening on 8080 and start it again with maven.
    $rowsBeforeOutage = [int](("$(MySql 'select count(*) from board_db.dev_metric')" -replace '\D', ''))
    if ($GatewayOutage) {
        $listen = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($listen) {
            Stop-Process -Id $listen.OwningProcess -Force
            Write-Host ("    (gateway pid {0} stopped)" -f $listen.OwningProcess)
        } else {
            Write-Host '    (no listener on 8080 - is the gateway up?)'
        }
    } else {
        Api PUT "/api/jobs/$jobId/status" @{ status = 0 } | Out-Null
        Write-Host '    (job disabled: the trigger answers 400, the same failure path as an unreachable target)'
    }

    $queued = 0
    for ($i = 0; $i -lt 20; $i++) { Start-Sleep -Seconds 2; $queued = (BridgeStats).buffered; if ($queued -gt 0) { break } }
    Write-Host ("    (buffered during the outage: {0})" -f $queued)
    Check 'bridge buffers while the target refuses' ($queued -gt 0) ("buffered={0}" -f $queued)
    $rowsDuring = [int](("$(MySql 'select count(*) from board_db.dev_metric')" -replace '\D', ''))
    Check 'the board stops growing while the target is down' ($rowsDuring -le ($rowsBeforeOutage + 8)) ("before={0} during={1}" -f $rowsBeforeOutage, $rowsDuring)

    if ($GatewayOutage) {
        $mvn = Join-Path $script:repo '.tools\apache-maven-3.9.9\bin\mvn.cmd'
        Start-Process -FilePath $mvn -ArgumentList '-o', 'spring-boot:run' -WorkingDirectory (Join-Path $script:repo 'spike') -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $script:repo '.scratch\gateway-restart.out.log') `
            -RedirectStandardError (Join-Path $script:repo '.scratch\gateway-restart.err.log') | Out-Null
        $waited = 0
        while ($waited -lt 90) {
            try { Invoke-RestMethod "$BaseUrl/api/jobs" -TimeoutSec 3 | Out-Null; break } catch { Start-Sleep -Seconds 3; $waited += 3 }
        }
        Write-Host ("    (gateway back after {0}s)" -f $waited)
    } else {
        Api PUT "/api/jobs/$jobId/status" @{ status = 1 } | Out-Null
        Write-Host '    (job enabled again)'
    }

    $drained = -1
    for ($i = 0; $i -lt 45; $i++) { Start-Sleep -Seconds 2; $drained = (BridgeStats).buffered; if ($drained -eq 0) { break } }
    Check 'bridge drains its queue after recovery' ($drained -eq 0) ("buffered={0}" -f $drained)
    $rowsAfterOutage = [int](("$(MySql 'select count(*) from board_db.dev_metric')" -replace '\D', ''))
    Check 'the buffered points land in the board' ($rowsAfterOutage -gt $rowsDuring) ("during={0} after={1}" -f $rowsDuring, $rowsAfterOutage)
    $badAfter = CounterGaps
    Check 'poll counter stays continuous across the outage (no gap, no dupe)' ($badAfter.Count -eq 0) ($badAfter -join ' / ')

    # ------------------------------------------------------------ device outage -> STALE
    # Deliberately last: once the slave is gone the bridge keeps re-sending the last known values, so the
    # poll counter repeats (same value, new timestamp) and the continuity check above would no longer hold.
    $before = [int](("$(MySql 'select count(*) from board_db.dev_metric')" -replace '\D', ''))
    Stop-Process -Id $sim.Id -Force
    Start-Sleep -Seconds ([Math]::Max(6, [int]($PollMs / 1000) * 2))
    $stale = MySql "select count(*) from board_db.dev_metric where quality='STALE'"
    Check 'device outage shows up as STALE (not zeros)' (("$stale" -match '^\s*[1-9]\d*\s*$')) ("stale rows={0}" -f ($stale -join '|'))
    $zeros = MySql "select count(*) from board_db.dev_metric where point='counter' and quality='STALE' and value=0"
    Check 'STALE keeps the last known value (not 0)' (("$zeros" -match '^\s*0\s*$')) ("stale counter zeros={0}" -f ($zeros -join '|'))
    $after = [int](("$(MySql 'select count(*) from board_db.dev_metric')" -replace '\D', ''))
    Check 'the board keeps growing during the outage (points are still delivered)' ($after -gt $before) ("before={0} after={1}" -f $before, $after)
}
finally {
    if (-not $KeepRunning) {
        if ($bridge -and -not $bridge.HasExited) { Stop-Process -Id $bridge.Id -Force }
        if ($sim -and -not $sim.HasExited) { Stop-Process -Id $sim.Id -Force }
        Write-Host 'simulator and bridge stopped (use -KeepRunning to leave them up)'
    } else {
        Write-Host 'simulator and bridge left running'
    }
}

Write-Host ''
Write-Host ("== result: {0} passed, {1} failed ==" -f $script:pass, $script:fail)
if ($script:fail -gt 0) { exit 1 }
exit 0
