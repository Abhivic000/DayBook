<#
.SYNOPSIS
    Creates demo data in a running Daybook API: one tenant, several funded USER accounts.

.DESCRIPTION
    Prints the tenant's API key (shown only once — the server stores just its hash) and the
    account ids, ready to use in Swagger UI or curl.

.EXAMPLE
    .\scripts\seed.ps1
.EXAMPLE
    .\scripts\seed.ps1 -Accounts 10 -OpeningBalanceMinor 500000
#>
param(
    [string] $BaseUrl = 'http://localhost:8080',
    # Matches the 'local' profile. Never use a real admin key as a default.
    [string] $AdminKey = 'dev-admin-key',
    [ValidateRange(1, 100)] [int] $Accounts = 5,
    # Minor units (paise): 1000000 = Rs 10,000.
    [ValidateRange(1, 1000000000)] [long] $OpeningBalanceMinor = 1000000,
    [string] $TenantName = 'Demo Tenant'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/DaybookApi.ps1')

function Assert-Status {
    param($Response, [int] $Expected, [string] $What)
    if ($Response.Status -ne $Expected) {
        throw "$What failed: HTTP $($Response.Status) $($Response.Raw)"
    }
}

Wait-DaybookReady -BaseUrl $BaseUrl
$run = [guid]::NewGuid().ToString('N').Substring(0, 8)

$tenant = Invoke-DaybookApi -BaseUrl $BaseUrl -Method POST -Path '/v1/admin/tenants' -ApiKey $AdminKey -Body @{ name = $TenantName }
Assert-Status $tenant 201 'Creating tenant'
$tenantId = $tenant.Json.tenantId
$tenantKey = $tenant.Json.apiKey

$created = @()
for ($i = 1; $i -le $Accounts; $i++) {
    $account = Invoke-DaybookApi -BaseUrl $BaseUrl -Method POST -Path '/v1/accounts' -ApiKey $tenantKey -IdempotencyKey "seed-acct-$run-$i"
    Assert-Status $account 201 "Creating account $i"
    $funding = Invoke-DaybookApi -BaseUrl $BaseUrl -Method POST -Path "/v1/admin/tenants/$tenantId/fundings" `
        -ApiKey $AdminKey -IdempotencyKey "seed-fund-$run-$i" -Body @{ accountId = $account.Json.id; amountMinor = $OpeningBalanceMinor }
    Assert-Status $funding 201 "Funding account $i"
    $created += [pscustomobject] @{ Account = $i; Id = $account.Json.id; BalanceMinor = $OpeningBalanceMinor }
}

Write-Host ''
Write-Host "Seeded tenant '$TenantName'" -ForegroundColor Green
Write-Host "  Tenant id : $tenantId"
Write-Host "  API key   : $tenantKey"
Write-Host '              (shown once - the server stores only its hash)' -ForegroundColor Yellow
$created | Format-Table -AutoSize | Out-String | Write-Host
