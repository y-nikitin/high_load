param(
    [string]$Node1Url = 'http://localhost:8080',
    [string]$Node2Url = 'http://localhost:8081',
    [switch]$SimulateFailure
)

$ErrorActionPreference = 'Stop'
$Node1Url = $Node1Url.TrimEnd('/')
$Node2Url = $Node2Url.TrimEnd('/')

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) { throw "$Message (expected: $Expected, actual: $Actual)" }
}

function Invoke-NodeApi {
    param([int]$Node, [string]$Method, [string]$Path, $Body, [int]$ExpectedStatus = 200)
    $baseUrl = if ($Node -eq 1) { $Node1Url } else { $Node2Url }
    $parameters = @{
        Method = $Method; Uri = "$baseUrl$Path"; UseBasicParsing = $true; TimeoutSec = 15
        # This value must never override the server's actual identity.
        Headers = @{ 'X-Instance-ID' = 'client-supplied-value' }
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10))
    }
    # No WebSession or cookie jar: each request contains all required context.
    $response = Invoke-WebRequest @parameters
    $identity = [string]$response.Headers['X-Instance-ID']
    Assert-Equal $identity "node-$Node" 'Unexpected processing instance'
    Assert-Equal ([int]$response.StatusCode) $ExpectedStatus 'Unexpected HTTP status'
    if ($response.Headers['Set-Cookie']) { throw 'The API unexpectedly created a cookie' }
    Write-Host "$Method $Path -> HTTP $($response.StatusCode), X-Instance-ID: $identity"
    if ($response.Content) { $response.Content | ConvertFrom-Json }
}

function Wait-Node1 {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $health = Invoke-RestMethod "$Node1Url/actuator/health" -TimeoutSec 3
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw 'node-1 did not become healthy after restart'
}

$null = Invoke-NodeApi 1 GET '/actuator/health'
$null = Invoke-NodeApi 2 GET '/actuator/health'
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)

$customer = Invoke-NodeApi 1 POST '/api/customers' @{
    name = 'Stateless Customer'; email = "stateless-$suffix@example.com"
} 201
$sharedCustomer = Invoke-NodeApi 2 GET "/api/customers/$($customer.id)"
Assert-Equal $sharedCustomer.id $customer.id 'Customer is not shared'

$product = Invoke-NodeApi 1 POST '/api/products' @{
    sku = "STATELESS-$suffix"; name = 'Shared product'; description = 'Created on node 1'
    price = 100.00; stock = 10
} 201
$readOnSecond = Invoke-NodeApi 2 GET "/api/products/$($product.id)"
Assert-Equal $readOnSecond.stock 10 'node-2 did not read the created stock'
$null = Invoke-NodeApi 2 PUT "/api/products/$($product.id)" @{
    name = 'Updated on node 2'; description = 'Shared PostgreSQL state'; price = 125.00
}
$readOnFirst = Invoke-NodeApi 1 GET "/api/products/$($product.id)"
Assert-Equal $readOnFirst.price 125.00 'node-1 returned stale price'
Assert-Equal $readOnFirst.name 'Updated on node 2' 'node-1 returned stale name'
Write-Host 'PASS: POST node-1 -> GET node-2 -> PUT node-2 -> GET node-1'

$order = Invoke-NodeApi 1 POST '/api/orders' @{
    customerId = $customer.id; items = @(@{ productId = $product.id; quantity = 2 })
} 201
$sharedOrder = Invoke-NodeApi 2 GET "/api/orders/$($order.id)"
Assert-Equal $sharedOrder.total 250.00 'Shared order total is incorrect'
$stock = Invoke-NodeApi 2 GET "/api/products/$($product.id)"
Assert-Equal $stock.stock 8 'Shared stock was not deducted'

if ($SimulateFailure) {
    Push-Location (Split-Path -Parent $PSScriptRoot)
    try {
        # Kill only the first backend. The database and second backend remain running.
        docker compose kill -s SIGKILL app
        if ($LASTEXITCODE -ne 0) { throw 'Failed to kill node-1' }
        $containerId = docker compose ps --all --quiet app
        if ($LASTEXITCODE -ne 0 -or -not $containerId) { throw 'Cannot identify the stopped node-1 container' }
        $isRunning = docker inspect --format '{{.State.Running}}' $containerId
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect node-1 state' }
        Assert-Equal ([string]$isRunning).Trim() 'false' 'node-1 must remain stopped during the failure scenario'
        $survivingOrder = Invoke-NodeApi 2 GET "/api/orders/$($order.id)"
        Assert-Equal $survivingOrder.status 'PLACED' 'Committed order was lost'
        Assert-Equal $survivingOrder.items[0].quantity 2 'Committed order items were lost'
        $cancelled = Invoke-NodeApi 2 POST "/api/orders/$($order.id)/cancel"
        Assert-Equal $cancelled.status 'CANCELLED' 'node-2 could not continue the workflow'
        Write-Host 'PASS: node-2 read committed data and continued the workflow while node-1 was down'
    } finally {
        # Restore the demonstration environment even if an assertion fails.
        try {
            docker compose start app
            if ($LASTEXITCODE -ne 0) { throw 'Failed to restart node-1' }
            Wait-Node1
        } finally {
            Pop-Location
        }
    }
} else {
    $null = Invoke-NodeApi 2 POST "/api/orders/$($order.id)/cancel"
}

$after = Invoke-NodeApi 1 GET "/api/orders/$($order.id)"
Assert-Equal $after.status 'CANCELLED' 'node-1 did not read the current shared state'
$null = Invoke-NodeApi 1 POST "/api/orders/$($order.id)/cancel"
$restored = Invoke-NodeApi 2 GET "/api/products/$($product.id)"
Assert-Equal $restored.stock 10 'Cross-instance cancellation restored inventory more than once'
Write-Host 'PASS: cross-instance consistency, no cookies, and idempotent cancellation'
if ($SimulateFailure) { Write-Host 'PASS: instance loss and restart' }
[pscustomobject]@{ CustomerId = $customer.id; ProductId = $product.id; OrderId = $order.id }
