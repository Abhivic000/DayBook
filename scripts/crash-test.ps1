<#
.SYNOPSIS
    Crash test with real processes (PRD section 8, SRS acceptance 3): events committed before the
    API is killed are published after restart and applied by the consumer exactly once.

.DESCRIPTION
    Needs: Docker Compose stack running (docker compose up -d --wait) and both jars built
    (.\mvnw.cmd package -DskipTests, or verify). Starts its own API and consumer on separate ports.

    Phase 1  API runs with its outbox relay OFF: transfers commit, events wait in the outbox.
             The API process is then hard-killed - the "crash between commit and publish".
    Phase 2  API restarts with the relay ON. Every event must be published, the projection must
             match the ledger exactly, and each ledger entry must appear exactly once.
    Phase 3  Simulate a crash after Kafka acknowledged but before rows were marked published:
             everything is republished (duplicates). The projection must not change.

    Exits 0 on PASS, 1 on FAIL.

.EXAMPLE
    .\scripts\crash-test.ps1
#>
param(
    [int] $ApiPort = 18080,
    [int] $ConsumerPort = 18082,
    [string] $AdminKey = 'dev-admin-key',
    [ValidateRange(1, 200)] [int] $Transfers = 20,
    [int] $TimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/DaybookApi.ps1')

$root = Split-Path $PSScriptRoot -Parent
$apiUrl = "http://localhost:$ApiPort"
$logDir = Join-Path ([System.IO.Path]::GetTempPath()) ("daybook-crash-test-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force $logDir | Out-Null
$script:failures = 0
$script:processes = @{}

# --- Helpers -------------------------------------------------------------------------------------

function Assert-Check {
    param([string] $Name, [bool] $Passed, [string] $Detail = '')
    if ($Passed) {
        Write-Host "  PASS  $Name" -ForegroundColor Green
    } else {
        Write-Host "  FAIL  $Name  $Detail" -ForegroundColor Red
        $script:failures++
    }
}

function Find-Jar([string] $Module) {
    $jar = Get-ChildItem (Join-Path $root "$Module/target") -Filter "$Module-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1
    if (-not $jar) {
        throw "No $Module jar found. Build first: .\mvnw.cmd package -DskipTests"
    }
    return $jar.FullName
}

function Test-PortInUse([int] $Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $client.Connect('127.0.0.1', $Port)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Start-App([string] $Name, [string] $Jar, [int] $Port, [string[]] $Extra) {
    # Quote the jar path: Start-Process joins arguments with spaces, and the path may contain them.
    $arguments = @('-Duser.timezone=UTC', '-jar', "`"$Jar`"", '--spring.profiles.active=local', "--server.port=$Port") + $Extra
    $process = Start-Process -FilePath 'java' -ArgumentList $arguments -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $logDir "$Name.out.log") `
        -RedirectStandardError (Join-Path $logDir "$Name.err.log")
    $script:processes[$Name] = $process

    # Wait for readiness, but fail at once - with the reason - if the process dies on startup.
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            throw "$Name exited during startup (exit code $($process.ExitCode)).`n$(Get-LogTail $Name)"
        }
        try {
            $probe = Invoke-DaybookApi -BaseUrl "http://localhost:$Port" -Method GET -Path '/actuator/health/readiness'
            if ($probe.Status -eq 200) {
                Write-Host "  started $Name (pid $($process.Id), port $Port)"
                return
            }
        } catch {
            # Not listening yet.
        }
        Start-Sleep -Seconds 1
    }
    throw "$Name was not ready on port $Port within $TimeoutSeconds seconds.`n$(Get-LogTail $Name)"
}

function Get-LogTail([string] $Name) {
    $lines = @()
    foreach ($suffix in 'out', 'err') {
        $file = Join-Path $logDir "$Name.$suffix.log"
        if (Test-Path $file) {
            $lines += Get-Content $file -Tail 15
        }
    }
    return "--- last log lines of $Name ---`n" + ($lines -join "`n")
}

function Stop-App([string] $Name) {
    $process = $script:processes[$Name]
    if ($process -and -not $process.HasExited) {
        # -Force: a hard kill (TerminateProcess / SIGKILL). No shutdown hooks, no graceful close.
        Stop-Process -Id $process.Id -Force
        $process.WaitForExit(15000) | Out-Null
    }
    $script:processes.Remove($Name)
}

function Invoke-Sql([string] $Sql) {
    # psql inside the Compose Postgres container: -t (rows only) -A (unaligned) -> a bare value.
    $result = docker compose -f (Join-Path $root 'compose.yaml') exec -T postgres psql -U daybook -d daybook -t -A -c $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed: $Sql"
    }
    return ($result | Out-String).Trim()
}

function Wait-Until([scriptblock] $Condition, [string] $What) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (& $Condition) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    }
    Write-Host "  timed out waiting for $What" -ForegroundColor Yellow
    return $false
}

function Api([string] $Method, [string] $Path, [string] $Key, [string] $Idem, [object] $Body) {
    Invoke-DaybookApi -BaseUrl $apiUrl -Method $Method -Path $Path -ApiKey $Key -IdempotencyKey $Idem -Body $Body
}

# --- Ledger-vs-projection checks (the same comparison Phase 4 reconciliation will run) -----------

function Get-Counts([string] $TenantId) {
    [pscustomobject] @{
        Entries     = [int] (Invoke-Sql "SELECT count(*) FROM ledger_entries WHERE tenant_id = '$TenantId'")
        Unpublished = [int] (Invoke-Sql "SELECT count(*) FROM outbox_events WHERE payload::jsonb ->> 'tenantId' = '$TenantId' AND published_at IS NULL")
        Lines       = [int] (Invoke-Sql "SELECT count(*) FROM projection.statement_lines WHERE tenant_id = '$TenantId'")
        Drifted     = [int] (Invoke-Sql @"
SELECT count(*) FROM accounts a
  LEFT JOIN projection.account_balances p ON p.account_id = a.id
 WHERE a.tenant_id = '$TenantId'
   AND a.version > 0
   AND (p.account_id IS NULL OR p.balance_minor <> a.balance_minor OR p.last_version <> a.version)
"@)
    }
}

# --- Run -----------------------------------------------------------------------------------------

try {
    Write-Host "Daybook crash test (logs: $logDir)"
    foreach ($port in 8080, 8081, $ApiPort, $ConsumerPort) {
        if (Test-PortInUse $port) {
            throw "Port $port is in use. Stop other Daybook API/consumer instances first: another API's outbox relay would publish the events this test needs to keep unpublished."
        }
    }
    $apiJar = Find-Jar 'daybook-api'
    $consumerJar = Find-Jar 'daybook-consumer'
    $run = [guid]::NewGuid().ToString('N').Substring(0, 8)

    Write-Host ''
    Write-Host 'Phase 1: relay off, commit transfers, then kill the API' -ForegroundColor Cyan
    # API first: its migration V6 creates the database role the consumer logs in with (ADR 0013).
    # On a fresh database (as in CI) the consumer cannot even connect until the API has started.
    Start-App 'api' $apiJar $ApiPort @('--daybook.outbox.relay-enabled=false')
    Start-App 'consumer' $consumerJar $ConsumerPort @()

    $tenant = Api POST '/v1/admin/tenants' $AdminKey $null @{ name = "crash-$run" }
    $tenantId = $tenant.Json.tenantId
    $key = $tenant.Json.apiKey
    $a = (Api POST '/v1/accounts' $key "a-$run" $null).Json.id
    $b = (Api POST '/v1/accounts' $key "b-$run" $null).Json.id
    [void] (Api POST "/v1/admin/tenants/$tenantId/fundings" $AdminKey "f-$run" @{ accountId = $a; amountMinor = 1000000 })
    $settled = 0
    for ($i = 1; $i -le $Transfers; $i++) {
        $from = $a; $to = $b
        if ($i % 2 -eq 0) { $from = $b; $to = $a }
        $response = Api POST '/v1/transfers' $key "t-$run-$i" @{ fromAccountId = $from; toAccountId = $to; amountMinor = 1000 }
        if ($response.Status -eq 201) { $settled++ }
    }
    Assert-Check "all $Transfers transfers settled" ($settled -eq $Transfers) "settled $settled"

    $before = Get-Counts $tenantId
    Assert-Check "every entry has a committed, unpublished outbox event ($($before.Entries))" `
        ($before.Entries -gt 0 -and $before.Unpublished -eq $before.Entries) "unpublished $($before.Unpublished)"

    Stop-App 'api'
    Write-Host '  API hard-killed'
    Start-Sleep -Seconds 3
    $afterKill = Get-Counts $tenantId
    Assert-Check 'nothing reached the consumer while the relay was down' ($afterKill.Lines -eq 0) "lines $($afterKill.Lines)"

    Write-Host ''
    Write-Host 'Phase 2: restart the API, events must be delivered exactly once' -ForegroundColor Cyan
    Start-App 'api' $apiJar $ApiPort @()
    $caughtUp = Wait-Until { $c = Get-Counts $tenantId; $c.Unpublished -eq 0 -and $c.Lines -eq $c.Entries } 'publication and projection to catch up'
    $after = Get-Counts $tenantId
    Assert-Check 'every outbox event was published after restart' ($after.Unpublished -eq 0) "unpublished $($after.Unpublished)"
    Assert-Check "each of the $($after.Entries) ledger entries projected exactly once" ($caughtUp -and $after.Lines -eq $after.Entries) "lines $($after.Lines)"
    Assert-Check 'projection balances and versions match the ledger' ($after.Drifted -eq 0) "drifted $($after.Drifted)"

    $statement = Api GET "/v1/accounts/$a/statement" $key $null $null
    Assert-Check 'statement endpoint reports the projection up to date' ($statement.Json.consistency.upToDate -eq $true)

    Write-Host ''
    Write-Host 'Phase 3: crash after broker ack, before marking published -> duplicates' -ForegroundColor Cyan
    [void] (Invoke-Sql "UPDATE outbox_events SET published_at = NULL WHERE payload::jsonb ->> 'tenantId' = '$tenantId'")
    [void] (Wait-Until { (Get-Counts $tenantId).Unpublished -eq 0 } 'republication')
    Start-Sleep -Seconds 5 # let the consumer chew through the duplicates
    $dupes = Get-Counts $tenantId
    Assert-Check 'all events were published a second time' ($dupes.Unpublished -eq 0)
    Assert-Check 'duplicates changed nothing: still exactly one line per entry' ($dupes.Lines -eq $dupes.Entries) "lines $($dupes.Lines) entries $($dupes.Entries)"
    Assert-Check 'projection still matches the ledger' ($dupes.Drifted -eq 0) "drifted $($dupes.Drifted)"
} catch {
    Write-Host "  ERROR  $($_.Exception.Message)" -ForegroundColor Red
    $script:failures++
} finally {
    foreach ($name in @($script:processes.Keys)) {
        Stop-App $name
    }
}

Write-Host ''
if ($script:failures -eq 0) {
    Write-Host 'CRASH TEST: PASS' -ForegroundColor Green
    Remove-Item -Recurse -Force $logDir -ErrorAction SilentlyContinue
    exit 0
}
Write-Host "CRASH TEST: FAIL ($script:failures failed) - logs in $logDir" -ForegroundColor Red
exit 1
