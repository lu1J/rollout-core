param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^[a-z0-9._-]{1,100}$')]
    [string]$ProjectKey = ('perf-' + [Guid]::NewGuid().ToString('N'))
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

function Invoke-Api([string]$Method, [string]$Path, $Body, [int]$ExpectedStatus) {
    $request = @{
        Uri = "$BaseUrl$Path"; Method = $Method; UseBasicParsing = $true
        TimeoutSec = 20; Headers = @{ 'X-Operator' = 'perf-fixture' }
    }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20 -Compress))
    }
    $response = Invoke-WebRequest @request
    if ([int]$response.StatusCode -ne $ExpectedStatus) { throw "Unexpected HTTP status for $Method $Path" }
    return ($response.Content | ConvertFrom-Json)
}

# Only public HTTP APIs. Existing data is never replaced or deleted.
# On partial failure, retain data and use a fresh project key on the next run.
$health = Invoke-Api 'GET' '/actuator/health' $null 200
if ($health.status -ne 'UP') { throw 'Server is not healthy' }
$null = Invoke-Api 'POST' '/api/v1/projects' @{ projectKey = $ProjectKey; name = 'Local performance fixture' } 201
$projectPath = "/api/v1/projects/$ProjectKey"
$null = Invoke-Api 'POST' "$projectPath/environments" @{ envKey = 'perf'; name = 'Performance' } 201
$null = Invoke-Api 'POST' "$projectPath/flags" @{
    flagKey = 'evaluation'; name = 'Evaluation benchmark'; valueType = 'BOOLEAN'
    variants = @(@{ variantKey = 'off'; value = $false }, @{ variantKey = 'on'; value = $true })
} 201
$flagPath = "$projectPath/environments/perf/flags/evaluation"
$null = Invoke-Api 'POST' "$flagPath/config" @{ enabled = $true; defaultVariantKey = 'off' } 201
$null = Invoke-Api 'PUT' "$flagPath/evaluation-policy" @{
    expectedVersion = 0
    rules = @(@{
        priority = 10; match = 'ALL'; variantKey = 'on'
        conditions = @(@{ attribute = 'country'; operator = 'EQ'; value = 'JP' })
    })
    rollout = @(@{ variantKey = 'on'; weight = 5000 }, @{ variantKey = 'off'; weight = 5000 })
} 200
foreach ($country in @('JP', 'CN')) {
    $result = Invoke-Api 'POST' '/api/v1/evaluate' @{
        projectKey = $ProjectKey; environmentKey = 'perf'; flagKey = 'evaluation'
        context = @{ userId = 'fixture-check'; country = $country }
    } 200
    $expectedReason = if ($country -eq 'JP') { 'RULE_MATCH' } else { 'PERCENTAGE_ROLLOUT' }
    if ($result.reason -ne $expectedReason -or $result.configVersion -ne 1) { throw 'Fixture evaluation mismatch' }
    if ($country -eq 'CN' -and ($null -eq $result.bucket -or $result.bucket -lt 0 -or $result.bucket -ge 10000)) {
        throw 'Expected a real hash bucket'
    }
}
# Default load context has no country: rule misses, then the real hash path runs.
Write-Output "Fixture ready: $ProjectKey / perf / evaluation (version 1)"
Write-Output "python scripts/load-test.py --base-url $BaseUrl --project $ProjectKey --environment perf --flag evaluation --concurrency 8 --requests 10000 --warmup 500"
