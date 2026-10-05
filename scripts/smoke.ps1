<#
.SYNOPSIS
    End-to-end smoke test of a running Daybook API. Prints PASS/FAIL per check and exits 1 on
    any failure, so CI can gate on it.

.DESCRIPTION
    Creates a fresh tenant, accounts and funding, then checks transfers, idempotent replay, key
    reuse, overdraft rejection, exact balances, conservation of money, statements, tenant
    isolation and authentication. Every run uses fresh idempotency keys, so it can be re-run.

.EXAMPLE
    .\scripts\smoke.ps1
.EXAMPLE
    .\scripts\smoke.ps1 -BaseUrl http://localhost:8081 -AdminKey my-admin-key
#>
param(
    [string] $BaseUrl = 'http://localhost:8080',
    # Matches the 'local' profile. Never use a real admin key as a default.
    [string] $AdminKey = 'dev-admin-key'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/DaybookApi.ps1')

$script:failures = 0

function Assert-Check {
    param([string] $Name, [bool] $Passed, [string] $Detail = '')
    if ($Passed) {
        Write-Host "  PASS  $Name" -ForegroundColor Green
    } else {
        Write-Host "  FAIL  $Name  $Detail" -ForegroundColor Red
        $script:failures++
    }
}

function Stop-Smoke {
    Write-Host ''
    if ($script:failures -eq 0) {
        Write-Host 'SMOKE TEST: PASS' -ForegroundColor Green
        exit 0
    }
    Write-Host "SMOKE TEST: FAIL ($script:failures failed)" -ForegroundColor Red
    exit 1
}

function Api {
    param([string] $Method, [string] $Path, [string] $Key, [string] $Idem, [object] $Body)
    Invoke-DaybookApi -BaseUrl $BaseUrl -Method $Method -Path $Path -ApiKey $Key -IdempotencyKey $Idem -Body $Body
}

Write-Host "Daybook smoke test against $BaseUrl"
Wait-DaybookReady -BaseUrl $BaseUrl
$run = [guid]::NewGuid().ToString('N').Substring(0, 8)

# --- Setup: tenant, two accounts, opening balance -------------------------------------------
$tenant = Api POST '/v1/admin/tenants' $AdminKey $null @{ name = "smoke-$run" }
Assert-Check 'admin creates a tenant and receives an API key' ($tenant.Status -eq 201 -and $tenant.Json.apiKey -like 'dbk_*') "status $($tenant.Status)"
if ($tenant.Status -ne 201) { Stop-Smoke }
$tenantId = $tenant.Json.tenantId
$key = $tenant.Json.apiKey

$a = Api POST '/v1/accounts' $key "acct-a-$run" $null
$b = Api POST '/v1/accounts' $key "acct-b-$run" $null
Assert-Check 'tenant creates two accounts' ($a.Status -eq 201 -and $b.Status -eq 201)
if ($a.Status -ne 201 -or $b.Status -ne 201) { Stop-Smoke }
$accountA = $a.Json.id
$accountB = $b.Json.id

$fund = Api POST "/v1/admin/tenants/$tenantId/fundings" $AdminKey "fund-$run" @{ accountId = $accountA; amountMinor = 100000 }
Assert-Check 'admin funds account A with 100000' ($fund.Status -eq 201) "status $($fund.Status)"

# --- Transfers and idempotency ---------------------------------------------------------------
$transferBody = @{ fromAccountId = $accountA; toAccountId = $accountB; amountMinor = 25000 }
$transfer = Api POST '/v1/transfers' $key "xfer-$run" $transferBody
Assert-Check 'transfer A -> B of 25000 settles' ($transfer.Status -eq 201 -and $transfer.Json.status -eq 'SETTLED') "status $($transfer.Status)"

$replay = Api POST '/v1/transfers' $key "xfer-$run" $transferBody
Assert-Check 'replay with the same key returns the identical response' ($replay.Raw -eq $transfer.Raw -and $replay.Replayed -eq 'true')

$reuse = Api POST '/v1/transfers' $key "xfer-$run" @{ fromAccountId = $accountA; toAccountId = $accountB; amountMinor = 1 }
Assert-Check 'same key with a different request is rejected (422)' ($reuse.Status -eq 422 -and $reuse.Json.type -like '*/idempotency-key-reused') "status $($reuse.Status)"

$overdraft = Api POST '/v1/transfers' $key "overdraft-$run" @{ fromAccountId = $accountB; toAccountId = $accountA; amountMinor = 25001 }
Assert-Check 'overdraft is rejected as insufficient-funds (422)' ($overdraft.Status -eq 422 -and $overdraft.Json.type -like '*/insufficient-funds') "status $($overdraft.Status)"

# --- Balances and conservation ---------------------------------------------------------------
$balanceA = (Api GET "/v1/accounts/$accountA" $key $null $null).Json.balanceMinor
$balanceB = (Api GET "/v1/accounts/$accountB" $key $null $null).Json.balanceMinor
Assert-Check 'balances are exact (A = 75000, B = 25000)' ($balanceA -eq 75000 -and $balanceB -eq 25000) "A=$balanceA B=$balanceB"
Assert-Check 'money is conserved (A + B = 100000 funded)' (($balanceA + $balanceB) -eq 100000)

$statement = Api GET "/v1/accounts/$accountB/transactions" $key $null $null
$lines = @($statement.Json.lines)
Assert-Check "statement for B shows exactly one credit of 25000" ($lines.Count -eq 1 -and $lines[0].direction -eq 'CREDIT' -and $lines[0].amountMinor -eq 25000)

# --- Security --------------------------------------------------------------------------------
$other = Api POST '/v1/admin/tenants' $AdminKey $null @{ name = "smoke-other-$run" }
$foreign = Api GET "/v1/accounts/$accountA" $other.Json.apiKey $null $null
Assert-Check "another tenant gets 404 for this tenant's account" ($foreign.Status -eq 404) "status $($foreign.Status)"

$anonymous = Api GET "/v1/accounts/$accountA" $null $null $null
Assert-Check 'a request without an API key gets 401' ($anonymous.Status -eq 401) "status $($anonymous.Status)"

$tenantOnAdmin = Api POST '/v1/admin/tenants' $key $null @{ name = 'nope' }
Assert-Check 'a tenant key cannot use admin endpoints (403)' ($tenantOnAdmin.Status -eq 403) "status $($tenantOnAdmin.Status)"

Stop-Smoke
