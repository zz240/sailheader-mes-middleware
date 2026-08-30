<#
.SYNOPSIS
向 RabbitMQ 队列发布一条 JSON 消息（本地联调用，走管理 HTTP API，无需额外依赖）。

.EXAMPLE
.\tools\mq-publish.ps1 -Queue bom.to.edge -Payload '{"bomId":"BOM-2026-001","bomNo":"BOM-2026-001","items":[]}'

.EXAMPLE
.\tools\mq-publish.ps1 -Queue bom.to.edge -PayloadFile .\bom.json
#>
param(
    [string]$Queue = 'bom.to.edge',
    [string]$Payload,
    [string]$PayloadFile,
    [string]$MgmtHost = 'localhost',
    [int]$MgmtPort = 15672,
    [string]$User = 'guest',
    [string]$Password = 'guest'
)

if ($PayloadFile) {
    $Payload = Get-Content -Raw -LiteralPath $PayloadFile
}
if ([string]::IsNullOrWhiteSpace($Payload)) {
    Write-Error '需要提供 -Payload 或 -PayloadFile'
    exit 1
}

$auth = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${User}:${Password}"))
$body = @{
    properties       = @{ content_type = 'application/json' }
    routing_key      = $Queue
    payload          = $Payload
    payload_encoding = 'string'
} | ConvertTo-Json -Depth 5 -Compress

$uri = "http://${MgmtHost}:${MgmtPort}/api/exchanges/%2F/amq.default/publish"
$resp = Invoke-RestMethod -Method Post -Uri $uri `
    -Headers @{ Authorization = "Basic $auth" } `
    -ContentType 'application/json' -Body $body
$resp | ConvertTo-Json -Compress
