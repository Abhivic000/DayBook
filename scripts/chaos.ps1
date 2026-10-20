<#
.SYNOPSIS
    Chaos test: breaks the running Docker stack under load, then proves no money was created,
    destroyed, duplicated or lost. Prints PASS/FAIL per check and exits 1 on any failure.

.DESCRIPTION
    Needs the full stack: docker compose up -d --build --wait

    While k6 sends transfers, top-ups and withdrawals (load/chaos.js), this script:
      1. hard-kills the API (SIGKILL: no shutdown hooks, in-flight transactions cut mid-way)
      2. restarts it
      3. switches the PSP simulator to LATE (it takes the payment but answers after our timeout,
         so the outcome is unknown and the transaction stays PENDING until the sweeper asks), then
         to DOWN (refuses everything; the circuit breaker opens), then back to normal
      4. hard-kills the consumer, then restarts it

    Then it waits for the system to settle and checks:
      - exactly-once: replaying every transfer key gives one stored answer each, and the database
        holds exactly as many transfers as there were "201 settled" answers
      - repeated requests got byte-identical answers
      - no transaction is left PENDING (the sweeper asked the PSP about each one)
      - the outbox is fully published and the statement projection caught up
      - reconciliation reports 0 CRITICAL violations

.EXAMPLE
    .\scripts\chaos.ps1
.EXAMPLE
    .\scripts\chaos.ps1 -Rate 50 -Seconds 180
#>
param(
    [string] $BaseUrl = 'http://localhost:8080',
    [string] $PspUrl = 'http://localhost:8090',
    [string] $AdminKey = 'dev-admin-key',
    [ValidateRange(1, 500)] [int] $Rate = 30,
    [ValidateRange(60, 1800)] [int] $Seconds = 150,
    [int] $SettleTimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/DaybookApi.ps1')

$root = Split-Path $PSScriptRoot -Parent
$run = 'chaos-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$container = "daybook-$run"
$script:failures = 0
$started = Get-Date

# --- Helpers -------------------------------------------------------------------------------------

function Assert-Check {
    param([string] $Name, [bool] $Passed, [string] $Detail = '')
    if ($Passed) {
        Write-Host "  PASS  $Name  $Detail" -ForegroundColor Green
    } else {
        Write-Host "  FAIL  $Name  $Detail" -ForegroundColor Red
        $script:failures++
    }
}

function Write-Step([string] $Message) {
    $elapsed = [int]((Get-Date) - $started).TotalSeconds
    Write-Host ("[t+{0,3}s] {1}" -f $elapsed, $Message) -ForegroundColor Cyan
}

# Docker writes progress to stderr; in Windows PowerShell that would count as an error.
# A plain function (no param block) so flags like -e reach docker instead of being read as
# PowerShell's own common parameters (-ErrorAction...).
function Invoke-Docker {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & docker @args 2>&1 | ForEach-Object { "$_" }
        if ($LASTEXITCODE -ne 0) {
            throw "docker $($args -join ' ') failed ($LASTEXITCODE): $($output -join "`n")"
        }
        return $output
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Invoke-Sql([string] $Sql) {
    $out = Invoke-Docker compose -f "$root/compose.yaml" exec -T postgres psql -U daybook -d daybook -tAc $Sql
    return (@($out) | Where-Object { $_ -ne '' } | Select-Object -Last 1)
}

function Wait-Until([string] $What, [scriptblock] $Condition, [int] $TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (& $Condition) {
            Write-Step "$What"
            return $true
        }
        Start-Sleep -Seconds 3
    }
    Write-Step "TIMED OUT waiting: $What"
    return $false
}

function Wait-Until-Elapsed([int] $Second) {
    $wait = $Second - ((Get-Date) - $started).TotalSeconds
    if ($wait -gt 0) { Start-Sleep -Milliseconds ([int]($wait * 1000)) }
}

function Set-PspMode([string] $Mode, [long] $DelayMs = 0) {
    $res = Invoke-DaybookApi -BaseUrl $PspUrl -Method PUT -Path '/admin/behaviour' -Body @{ mode = $Mode; delayMs = $DelayMs }
    if ($res.Status -ne 200) { throw "Could not set PSP mode $Mode (status $($res.Status))" }
}

function Wait-Healthy([string] $Service) {
    $ok = Wait-Until "$Service is healthy again" {
        $state = Invoke-Docker inspect -f '{{.State.Health.Status}}' (Invoke-Docker compose -f "$root/compose.yaml" ps -q $Service)
        "$state" -eq 'healthy'
    } 120
    if (-not $ok) { throw "$Service did not become healthy" }
}

# --- Run -----------------------------------------------------------------------------------------

Write-Host "Daybook chaos test ($run): $Rate transfers/s + PSP payments for $Seconds s" -ForegroundColor White
Wait-DaybookReady -BaseUrl $BaseUrl
Set-PspMode 'APPROVE'

try {
    Write-Step 'starting k6 traffic'
    Invoke-Docker compose -f "$root/compose.yaml" --profile tools run -d --name $container k6 run -q `
        -e RUN_ID=$run -e RATE=$Rate -e SECONDS=$Seconds /scripts/chaos.js | Out-Null

    $s = [int]($Seconds / 150 * 1000) / 1000  # stretch the timeline for longer runs
    Wait-Until-Elapsed (25 * $s)
    Write-Step 'KILL api (SIGKILL)'
    Invoke-Docker compose -f "$root/compose.yaml" kill -s SIGKILL api | Out-Null
    Start-Sleep -Seconds 5
    Write-Step 'restart api'
    Invoke-Docker compose -f "$root/compose.yaml" start api | Out-Null
    Wait-Healthy 'api'

    Wait-Until-Elapsed (60 * $s)
    Write-Step 'PSP -> LATE (answers after 4s; the API gives up after 2s: outcome unknown)'
    Set-PspMode 'LATE' 4000
    Wait-Until-Elapsed (70 * $s)
    Write-Step 'PSP -> DOWN'
    Set-PspMode 'DOWN'
    Wait-Until-Elapsed (100 * $s)
    Write-Step 'PSP -> APPROVE'
    Set-PspMode 'APPROVE'

    Wait-Until-Elapsed (110 * $s)
    Write-Step 'KILL consumer (SIGKILL)'
    Invoke-Docker compose -f "$root/compose.yaml" kill -s SIGKILL consumer | Out-Null
    Start-Sleep -Seconds 5
    Write-Step 'restart consumer'
    Invoke-Docker compose -f "$root/compose.yaml" start consumer | Out-Null
    Wait-Healthy 'consumer'

    Write-Step 'waiting for k6 to finish (traffic, then replay of every key)'
    Invoke-Docker wait $container | Out-Null
    Invoke-Docker logs $container | Where-Object { $_ -notmatch 'level=(warning|info)' } | Write-Host
} finally {
    # Leave the stack as we found it, whatever happened.
    try { Set-PspMode 'APPROVE' } catch { }
    try { Invoke-Docker compose -f "$root/compose.yaml" start api consumer | Out-Null } catch { }
    try { Invoke-Docker rm -f $container | Out-Null } catch { }
}

$resultFile = Join-Path $root "load/results/$run.json"
if (-not (Test-Path $resultFile)) { throw "k6 wrote no result file ($resultFile); see its output above" }
$result = Get-Content $resultFile -Raw | ConvertFrom-Json
$tenant = $result.tenantId

# --- Let the system settle -----------------------------------------------------------------------

Write-Step 'settling: sweeper resolves PENDING payments, relay drains outbox, consumer catches up'
$pendingDone = Wait-Until 'no PENDING transactions left for this tenant' {
    [int](Invoke-Sql "SELECT count(*) FROM transactions WHERE tenant_id = '$tenant' AND status = 'PENDING'") -eq 0
} $SettleTimeoutSeconds
$outboxDone = Wait-Until 'outbox fully published' {
    [int](Invoke-Sql 'SELECT count(*) FROM outbox_events WHERE published_at IS NULL') -eq 0
} 120
$projectionDone = Wait-Until 'statement projection caught up with the ledger' {
    [int](Invoke-Sql @"
SELECT count(*) FROM accounts a
  LEFT JOIN projection.account_balances p ON p.account_id = a.id
 WHERE a.tenant_id = '$tenant' AND a.version > 0
   AND (p.account_id IS NULL OR p.last_version <> a.version OR p.balance_minor <> a.balance_minor)
"@) -eq 0
} 120

# --- Verdict -------------------------------------------------------------------------------------

Write-Host ''
Write-Host 'Checks' -ForegroundColor White
$transfersInDb = [int](Invoke-Sql "SELECT count(*) FROM transactions WHERE tenant_id = '$tenant' AND type = 'TRANSFER'")
Assert-Check 'every transfer key has exactly one stored answer' ($result.finalUnexpected -eq 0) "($($result.transferKeys) keys: $($result.finalSettled) settled, $($result.finalRejected) rejected, $($result.finalUnexpected) other)"
Assert-Check 'transfers in the database = transfers answered 201 (no duplicates, none lost)' ($transfersInDb -eq $result.finalSettled) "(db $transfersInDb, answered $($result.finalSettled))"
Assert-Check 'repeated requests got identical answers' ($result.duplicateMismatch -eq 0) "(mismatches: $($result.duplicateMismatch))"
# Resolved long after creation = was PENDING and resolved by the sweeper asking the PSP.
$sweptLater = [int](Invoke-Sql "SELECT count(*) FROM transactions WHERE tenant_id = '$tenant' AND type IN ('TOPUP', 'WITHDRAWAL') AND status <> 'PENDING' AND updated_at - created_at > interval '30 seconds'")
Assert-Check 'no transaction left PENDING' $pendingDone "($sweptLater payment(s) had an unknown outcome and were resolved later by the sweeper)"
Assert-Check 'outbox fully published' $outboxDone
Assert-Check 'statement projection matches the ledger' $projectionDone

$recon = Invoke-DaybookApi -BaseUrl $BaseUrl -Method POST -Path '/v1/admin/reconciliation/runs' -ApiKey $AdminKey
$critical = 'LEDGER_UNBALANCED', 'TRANSACTION_UNBALANCED', 'BALANCE_DRIFT', 'VERSION_DRIFT', 'NEGATIVE_BALANCE', 'HOLD_MISMATCH', 'PROJECTION_MISMATCH'
$criticalFound = @($recon.Json.violations | Where-Object { $critical -contains $_.check })
Assert-Check 'reconciliation: 0 CRITICAL violations' ($recon.Status -eq 201 -and $criticalFound.Count -eq 0) "(status $($recon.Status), critical $($criticalFound.Count), all violations $(@($recon.Json.violations).Count), run $($recon.Json.id))"
$criticalFound | Select-Object -First 5 | ForEach-Object { Write-Host "        $($_.check): $($_.detail)" -ForegroundColor Red }

$counts = Invoke-Sql "SELECT string_agg(type || ' ' || status || ' ' || n, ', ' ORDER BY type, status) FROM (SELECT type, status, count(*) n FROM transactions WHERE tenant_id = '$tenant' GROUP BY 1, 2) t"
Write-Host ''
Write-Host "Client retries: $($result.clientRetries), gave up: $($result.clientGaveUp)"
Write-Host "Transactions for this run: $counts"
Write-Host ''
if ($script:failures -eq 0) {
    Write-Host 'CHAOS TEST: PASS' -ForegroundColor Green
    exit 0
}
Write-Host "CHAOS TEST: FAIL ($($script:failures) check(s))" -ForegroundColor Red
exit 1
