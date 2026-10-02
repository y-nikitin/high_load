param([string]$BaseUrl = 'http://localhost:8080', [switch]$SimulateFailure)
$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

function Read-Catalog {
    param([string]$Expected)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $response = Invoke-WebRequest "$BaseUrl/api/products?page=0&size=20" -UseBasicParsing -TimeoutSec 10
    $watch.Stop()
    $cache = [string]$response.Headers['X-Cache']
    $instance = [string]$response.Headers['X-Instance-ID']
    Write-Host ("X-Cache={0}, X-Instance-ID={1}, elapsed={2:N2} ms" -f $cache, $instance, $watch.Elapsed.TotalMilliseconds)
    if ($cache -ne $Expected) { throw "Expected $Expected, received $cache. Use CACHE_ENABLED=true and stop concurrent load." }
    return ($response.Content | ConvertFrom-Json)
}

function Write-Api {
    param([string]$Method, [string]$Path, $Body)
    Invoke-RestMethod "$BaseUrl$Path" -Method $Method -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 5))) -TimeoutSec 10
}

$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$product = Write-Api POST '/api/products' @{
    sku = "CACHE-$suffix"; name = 'Cache demo'; description = 'Lab 4'; price = 100; stock = 10
}
$null = Read-Catalog 'MISS'
$null = Read-Catalog 'HIT'
$null = Write-Api PUT "/api/products/$($product.id)" @{
    name = 'Updated cache demo'; description = 'Invalidation'; price = 125
}
$fresh = Read-Catalog 'MISS'
$entry = @($fresh.items | Where-Object id -eq $product.id)
if ($entry.Count -ne 1 -or $entry[0].price -ne 125) { throw 'Catalog returned stale data after PUT' }
$null = Read-Catalog 'HIT'

if ($SimulateFailure) {
    Push-Location (Split-Path -Parent $PSScriptRoot)
    try {
        docker compose stop redis
        if ($LASTEXITCODE -ne 0) { throw 'Cannot stop Redis' }
        $null = Read-Catalog 'BYPASS'
        $null = Write-Api PUT "/api/products/$($product.id)" @{
            name = 'Updated during Redis outage'; description = 'Fallback'; price = 150
        }
        $fallback = Read-Catalog 'BYPASS'
        $entry = @($fallback.items | Where-Object id -eq $product.id)
        if ($entry.Count -ne 1 -or $entry[0].price -ne 150) { throw 'Database fallback returned stale data' }
    } finally {
        try {
            docker compose start redis
            if ($LASTEXITCODE -ne 0) { throw 'Cannot start Redis' }
        } finally { Pop-Location }
    }
}
Write-Host 'PASS: cache-aside, invalidation and requested fallback checks completed.'
Write-Host 'Timing is an illustrative single-request measurement, not a performance baseline.'
