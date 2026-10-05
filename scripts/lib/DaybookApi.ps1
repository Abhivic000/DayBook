# Shared HTTP helpers for the Daybook scripts.
# Compatible with Windows PowerShell 5.1 and PowerShell 7+ (used in CI on Linux).
#
# Uses .NET HttpClient rather than Invoke-RestMethod: in PowerShell 5.1, Invoke-RestMethod throws on
# every 4xx/5xx, which makes asserting "this must return 422" awkward. HttpClient just returns the
# status code.

Add-Type -AssemblyName System.Net.Http

$script:DaybookHttp = New-Object System.Net.Http.HttpClient
$script:DaybookHttp.Timeout = [TimeSpan]::FromSeconds(30)

function Invoke-DaybookApi {
    param(
        [Parameter(Mandatory)] [string] $BaseUrl,
        [Parameter(Mandatory)] [string] $Method,
        [Parameter(Mandatory)] [string] $Path,
        [string] $ApiKey,
        [string] $IdempotencyKey,
        [object] $Body
    )
    $uri = $BaseUrl.TrimEnd('/') + $Path
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), $uri)
    if ($ApiKey) {
        [void] $request.Headers.TryAddWithoutValidation('Authorization', "Bearer $ApiKey")
    }
    if ($IdempotencyKey) {
        [void] $request.Headers.TryAddWithoutValidation('Idempotency-Key', $IdempotencyKey)
    }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Compress -Depth 10
        $request.Content = New-Object System.Net.Http.StringContent($json, [System.Text.Encoding]::UTF8, 'application/json')
    }

    $response = $script:DaybookHttp.SendAsync($request).GetAwaiter().GetResult()
    $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()

    $parsed = $null
    if ($text) {
        $parsed = $text | ConvertFrom-Json
    }
    $replayed = $null
    $values = $null
    if ($response.Headers.TryGetValues('Idempotent-Replayed', [ref] $values)) {
        $replayed = @($values)[0]
    }

    [pscustomobject] @{
        Status   = [int] $response.StatusCode
        Raw      = $text
        Json     = $parsed
        Replayed = $replayed
    }
}

function Wait-DaybookReady {
    param(
        [Parameter(Mandatory)] [string] $BaseUrl,
        [int] $TimeoutSeconds = 90
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $probe = Invoke-DaybookApi -BaseUrl $BaseUrl -Method GET -Path '/actuator/health/readiness'
            if ($probe.Status -eq 200) {
                return
            }
        } catch {
            # Not listening yet; keep waiting.
        }
        Start-Sleep -Seconds 1
    }
    throw "Daybook API at $BaseUrl was not ready within $TimeoutSeconds seconds"
}
