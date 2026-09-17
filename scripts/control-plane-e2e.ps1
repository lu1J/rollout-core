param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^[a-z0-9._-]{1,100}$')]
    [string]$ProjectKey = 'checkout-service'
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$operatorName = 'control-plane-e2e'

function Assert-Equal($Actual, $Expected, [string]$Label) {
    if ($Actual -ne $Expected) {
        throw "$Label expected=$Expected actual=$Actual"
    }
}

function Invoke-Api([string]$Method, [string]$Path, $Body, [int]$ExpectedStatus = 200) {
    $request = @{
        Uri = "$BaseUrl$Path"
        Method = $Method
        Headers = @{ 'X-Operator' = $operatorName }
        UseBasicParsing = $true
        TimeoutSec = 20
    }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20 -Compress))
    }
    try {
        $response = Invoke-WebRequest @request
        $statusCode = [int]$response.StatusCode
        $responseText = $response.Content
        if ($responseText -is [byte[]]) {
            $responseText = [System.Text.Encoding]::UTF8.GetString($responseText)
        }
    } catch {
        if ($null -eq $_.Exception.Response) { throw }
        $statusCode = [int]$_.Exception.Response.StatusCode
        $responseText = $_.ErrorDetails.Message
        if ([string]::IsNullOrWhiteSpace($responseText)) {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            try { $responseText = $reader.ReadToEnd() } finally { $reader.Dispose() }
        }
    }
    Assert-Equal $statusCode $ExpectedStatus "$Method $Path status"
    return ($responseText | ConvertFrom-Json)
}

# Use a running server backed by a dedicated MySQL database. No service management or cleanup.
$health = Invoke-Api 'GET' '/actuator/health' $null
Assert-Equal $health.status 'UP' 'health'
$project = Invoke-Api 'POST' '/api/v1/projects' @{ projectKey = $ProjectKey; name = 'Checkout service' } 201
Assert-Equal $project.projectKey $ProjectKey 'project key'
$projectPath = "/api/v1/projects/$ProjectKey"
$environment = Invoke-Api 'POST' "$projectPath/environments" @{ envKey = 'prod'; name = 'Production' } 201
Assert-Equal $environment.projectId $project.id 'environment owner'
$flag = Invoke-Api 'POST' "$projectPath/flags" @{
    flagKey = 'new-payment-flow'
    name = 'New payment flow'
    valueType = 'BOOLEAN'
    variants = @(
        @{ variantKey = 'old'; value = $false },
        @{ variantKey = 'new'; value = $true }
    )
} 201
Assert-Equal $flag.valueType 'BOOLEAN' 'flag type'
$variants = Invoke-Api 'GET' "$projectPath/flags/new-payment-flow/variants" $null
Assert-Equal $variants.Count 2 'initial variant count'
Assert-Equal ($variants | Where-Object variantKey -EQ 'old').value $false 'old value'
Assert-Equal ($variants | Where-Object variantKey -EQ 'new').value $true 'new value'
$configPath = "$projectPath/environments/prod/flags/new-payment-flow"
$config = Invoke-Api 'POST' "$configPath/config" @{ enabled = $false; defaultVariantKey = 'old' } 201
Assert-Equal $config.version 0 'initial version'
Assert-Equal $config.enabled $false 'initial disabled'
$enabled = Invoke-Api 'POST' "$configPath/enable" @{ expectedVersion = 0 }
Assert-Equal $enabled.version 1 'enabled version'
Assert-Equal $enabled.enabled $true 'enabled state'
$disabled = Invoke-Api 'POST' "$configPath/disable" @{ expectedVersion = 1 }
Assert-Equal $disabled.version 2 'disabled version'
Assert-Equal $disabled.enabled $false 'disabled state'
$conflict = Invoke-Api 'POST' "$configPath/disable" @{ expectedVersion = 0 } 409
Assert-Equal $conflict.code 'optimistic_lock_conflict' 'stale version error'
$persisted = Invoke-Api 'GET' "$configPath/config" $null
Assert-Equal $persisted.version 2 'persisted version after conflict'
Assert-Equal $persisted.enabled $false 'persisted state after conflict'
$audits = Invoke-Api 'GET' "/api/v1/audits?projectKey=$ProjectKey" $null
Assert-Equal $audits.Count 6 'successful operation audit count'
foreach ($operation in @('PROJECT_CREATED', 'ENVIRONMENT_CREATED', 'FLAG_CREATED', 'CONFIG_CREATED', 'FLAG_ENABLED', 'FLAG_DISABLED')) {
    $matching = @($audits | Where-Object operation -EQ $operation)
    Assert-Equal $matching.Count 1 "$operation audit"
    Assert-Equal $matching[0].operatorName $operatorName "$operation operator"
}
$disableAudit = $audits | Where-Object operation -EQ 'FLAG_DISABLED'
Assert-Equal $disableAudit.before.version 1 'disable audit before'
Assert-Equal $disableAudit.after.version 2 'disable audit after'
Write-Output "REAL_MYSQL_E2E=PASSED (running server must use MySQL); project=$ProjectKey"
