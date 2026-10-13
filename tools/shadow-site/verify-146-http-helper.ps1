# HTTP 请求helper（验收脚本共用）：显式控制方法、UTF-8 请求体与响应体，并把非 2xx 变成可断言对象。
# 为什么不直接用 Invoke-RestMethod / WebClient（都是实测踩过的）：
#   1) PS 5.1 的 Invoke-RestMethod 把错误响应体按 ANSI 解码，信封里的中文会让 ConvertFrom-Json 失败；
#   2) WebClient.UploadString(Uri, method, data) 的 method 重载在这台机器上不可靠（PUT 被改写、路径被吃）。
# 用法：. .\http-helper.ps1 之后调 Invoke-Json -Method POST -Path '/api/jobs' -Body @{...}

function Invoke-Json {
    param(
        [string]$Method = 'GET',
        [string]$Path,
        $Body,
        [int]$TimeoutSec = 120
    )
    $uri = $script:BaseUrl + $Path
    $req = [System.Net.HttpWebRequest]::Create($uri)
    $req.Method = $Method
    $req.Timeout = $TimeoutSec * 1000
    $req.ReadWriteTimeout = $TimeoutSec * 1000
    $req.ContentType = 'application/json; charset=utf-8'
    $req.Accept = 'application/json'
    if ($null -ne $Body) {
        $json = if ($Body -is [string]) { $Body } else { $Body | ConvertTo-Json -Depth 10 -Compress }
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        $req.ContentLength = $bytes.Length
        $stream = $req.GetRequestStream()
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Dispose()
    }
    try {
        $resp = $req.GetResponse()
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        if ($null -eq $resp) {
            return [pscustomobject]@{ __error = $true; status = 0; code = $null; message = $_.Exception.Message; raw = '' }
        }
    }
    try {
        $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
        $raw = $reader.ReadToEnd()
        $reader.Dispose()
        $status = [int]$resp.StatusCode
        $resp.Dispose()
        if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
        $parsed = $raw | ConvertFrom-Json
        if ($status -ge 400) {
            return [pscustomobject]@{ __error = $true; status = $status; code = $parsed.code; message = $parsed.message; raw = $raw }
        }
        return $parsed
    } finally {
        if ($resp) { $resp.Dispose() }
    }
}

# 各脚本里 Api(method, path, body) 这个老签名保持不动，内部转到 Invoke-Json
function Api([string]$method, [string]$path, $body) {
    return Invoke-Json -Method $method -Path $path -Body $body
}

# 记一次断言
function Check([string]$name, [bool]$ok, [string]$detail = '') {
    if ($ok) {
        $script:pass++
        Write-Host ("  [PASS] {0}" -f $name)
    } else {
        $script:fail++
        Write-Host ("  [FAIL] {0} {1}" -f $name, $detail) -ForegroundColor Red
    }
}
