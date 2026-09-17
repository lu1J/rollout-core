param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$DemoUrl = 'http://127.0.0.1:8081',
    [ValidatePattern('^[a-z0-9._-]{1,100}$')]
    [string]$ProjectKey = 'sdk-demo',
    [switch]$VerifyOutage
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$DemoUrl = $DemoUrl.TrimEnd('/')

function Assert-Equal($Actual, $Expected, [string]$Label) {
    if ($Actual -ne $Expected) { throw "$Label expected=$Expected actual=$Actual" }
}

function Invoke-Json([string]$Method, [string]$Url, $Body, [int]$ExpectedStatus = 200) {
    $request = @{
        Uri = $Url; Method = $Method; UseBasicParsing = $true; TimeoutSec = 15
        Headers = @{ 'X-Operator' = 'sdk-e2e' }
    }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20 -Compress))
    }
    # HTTP errors intentionally fail, including duplicate projects; never overwrite existing configuration.
    $response = Invoke-WebRequest @request
    Assert-Equal ([int]$response.StatusCode) $ExpectedStatus "$Method $Url status"
    $content = $response.Content
    if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }
    return ($content | ConvertFrom-Json)
}

Assert-Equal (Invoke-Json 'GET' "$DemoUrl/actuator/health" $null).status 'UP' 'demo health'

if ($VerifyOutage) {
    # Run only after the normal pass, then manually stop the server application within the SDK LKG TTL.
    # Keep demo running: its in-memory LKG must survive. No processes or Windows services are changed here.
    $cached = Invoke-Json 'GET' "$DemoUrl/demo/payment?userId=sdk-new" $null
    Assert-Equal $cached.source 'LKG' 'warm user outage source'
    Assert-Equal $cached.flow 'new' 'warm user outage flow'
    if ($cached.error -notin @('CONNECTION', 'TIMEOUT', 'SERVER_UNAVAILABLE')) {
        throw "Expected infrastructure failure, got $($cached.error)"
    }
    $coldUser = 'cold-' + [Guid]::NewGuid().ToString('N')
    $cold = Invoke-Json 'GET' "$DemoUrl/demo/payment?userId=$coldUser" $null
    Assert-Equal $cold.source 'DEFAULT' 'cold user default'
    Assert-Equal $cold.flow 'old' 'safe default flow'
    Write-Output 'REAL_SDK_OUTAGE_E2E=PASSED'
    return
}

Assert-Equal (Invoke-Json 'GET' "$BaseUrl/actuator/health" $null).status 'UP' 'server health'
$projectPath = "$BaseUrl/api/v1/projects/$ProjectKey"
$null = Invoke-Json 'POST' "$BaseUrl/api/v1/projects" @{ projectKey = $ProjectKey; name = 'SDK SDK demo' } 201
$null = Invoke-Json 'POST' "$projectPath/environments" @{ envKey = 'prod'; name = 'Production' } 201
$null = Invoke-Json 'POST' "$projectPath/flags" @{
    flagKey = 'new-payment-flow'; name = 'New payment flow'; valueType = 'BOOLEAN'
    variants = @(@{ variantKey = 'old'; value = $false }, @{ variantKey = 'new'; value = $true })
} 201
$configPath = "$projectPath/environments/prod/flags/new-payment-flow"
$null = Invoke-Json 'POST' "$configPath/config" @{ enabled = $true; defaultVariantKey = 'old' } 201
$null = Invoke-Json 'PUT' "$configPath/evaluation-policy" @{
    expectedVersion = 0
    rules = @(@{
        priority = 1; match = 'ALL'; variantKey = 'new'
        conditions = @(@{ attribute = 'userId'; operator = 'EQ'; value = 'sdk-new' })
    })
}

foreach ($userId in @('sdk-old', 'sdk-new')) {
    $direct = Invoke-Json 'POST' "$BaseUrl/api/v1/evaluate" @{
        projectKey = $ProjectKey; environmentKey = 'prod'; flagKey = 'new-payment-flow'
        context = @{ userId = $userId }
    }
    $demo = Invoke-Json 'GET' "$DemoUrl/demo/payment?userId=$userId" $null
    $expectedFlow = if ($userId -eq 'sdk-new') { 'new' } else { 'old' }
    Assert-Equal $direct.variantKey $expectedFlow 'direct variant'
    Assert-Equal $demo.flow $expectedFlow 'demo business flow'
    Assert-Equal $demo.source 'REMOTE' 'demo SDK source'
    Assert-Equal $demo.error 'NONE' 'demo SDK error'
    Assert-Equal $demo.variant $direct.variantKey 'HTTP chain variant'
    Assert-Equal $demo.reason $direct.reason 'HTTP chain reason'
}
Write-Output "REAL_MYSQL_SDK_E2E=PASSED (server must use MySQL); project=$ProjectKey"
Write-Output 'Optional: manually stop only rolloutcore-server, keep demo alive, then run with -VerifyOutage before LKG TTL expires.'
