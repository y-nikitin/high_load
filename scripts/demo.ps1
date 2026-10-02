param(
    [string]$BaseUrl = 'http://localhost:8080',
    [switch]$VerifyPersistence
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body)
    $parameters = @{ Method = $Method; Uri = "$BaseUrl$Path" }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10))
    }
    Invoke-RestMethod @parameters
}

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw "$Message (expected: $Expected, actual: $Actual)"
    }
}

function Wait-Healthy {
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            $health = Invoke-RestMethod "$BaseUrl/actuator/health" -TimeoutSec 3
            if ($health.status -eq 'UP') { return }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw 'Application did not become healthy.'
}

Wait-Healthy
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$customer = Invoke-Api POST '/api/customers' @{ name = 'Demo Customer'; email = "demo-$suffix@example.com" }
Write-Host "1. Customer created: $($customer.id)"

$product = Invoke-Api POST '/api/products' @{
    sku = "DEMO-$suffix"; name = 'Mechanical keyboard'; description = 'Lab 1 demonstration'
    price = 1500.00; stock = 10
}
Write-Host "2. Product created: $($product.id), stock = $($product.stock)"

$catalog = Invoke-Api GET '/api/products?page=0&size=20'
Write-Host "3. Catalog returned $($catalog.items.Count) products"

$updated = Invoke-Api PUT "/api/products/$($product.id)" @{
    name = 'Mechanical keyboard'; description = 'Updated description'; price = 1600.00
}
Assert-Equal $updated.price 1600.00 'Product price update failed'
Write-Host '4. Product updated'

$order = Invoke-Api POST '/api/orders' @{
    customerId = $customer.id; items = @(@{ productId = $product.id; quantity = 2 })
}
Assert-Equal $order.total 3200.00 'Incorrect order total'
$stock = Invoke-Api GET "/api/products/$($product.id)"
Assert-Equal $stock.stock 8 'Stock was not deducted'
Write-Host "5. Order created: $($order.id), total = $($order.total) UAH, stock = 8"

$saved = Invoke-Api GET "/api/orders/$($order.id)"
Assert-Equal $saved.status 'PLACED' 'Order read failed'
Write-Host '6. Order read with saved items'

if ($VerifyPersistence) {
    Push-Location (Split-Path -Parent $PSScriptRoot)
    try {
        docker compose restart db
        if ($LASTEXITCODE -ne 0) { throw 'Database restart failed' }
        Wait-Healthy
        $persisted = Invoke-Api GET "/api/orders/$($order.id)"
        Assert-Equal $persisted.id $order.id 'Order ID changed after DB restart'
        Assert-Equal $persisted.total 3200.00 'Order total changed after DB restart'
        Assert-Equal $persisted.status 'PLACED' 'Order status changed after DB restart'
        Assert-Equal $persisted.items.Count 1 'Order items changed after DB restart'
        Assert-Equal $persisted.items[0].productId $product.id 'Order product changed after DB restart'
        Assert-Equal $persisted.items[0].quantity 2 'Order quantity changed after DB restart'
        Assert-Equal $persisted.items[0].unitPrice 1600.00 'Historical price changed after DB restart'
        $persistedProduct = Invoke-Api GET "/api/products/$($product.id)"
        Assert-Equal $persistedProduct.stock 8 'Stock changed after DB restart'
        Write-Host '7. PASS: order, items and stock survived PostgreSQL restart'
    } finally {
        Pop-Location
    }
}

$cancelled = Invoke-Api POST "/api/orders/$($order.id)/cancel"
Assert-Equal $cancelled.status 'CANCELLED' 'Order cancellation failed'
$null = Invoke-Api POST "/api/orders/$($order.id)/cancel"
$restored = Invoke-Api GET "/api/products/$($product.id)"
Assert-Equal $restored.stock 10 'Cancellation must restore stock only once'
Write-Host '8. Order cancelled twice; stock restored exactly once'

$null = Invoke-Api DELETE "/api/products/$($product.id)"
$history = Invoke-Api GET "/api/orders/$($order.id)"
Assert-Equal $history.total 3200.00 'Soft delete damaged order history'
Write-Host '9. Product deleted from catalog; order history preserved'
Write-Host 'PASS: API demonstration completed.'
[pscustomobject]@{ CustomerId = $customer.id; ProductId = $product.id; OrderId = $order.id }
