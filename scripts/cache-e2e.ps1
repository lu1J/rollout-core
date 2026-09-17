param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [ValidatePattern('^[a-z0-9._-]{1,100}$')]
    [string]$ProjectKey = ('cache-' + [Guid]::NewGuid().ToString('N')),
    [switch]$RedisEnabled,
    [string]$RedisCliPath,
    [string]$RedisHost,
    [int]$RedisPort = 0
)

$ErrorActionPreference = 'Stop'
if ($RedisEnabled -and ([string]::IsNullOrWhiteSpace($RedisCliPath) -or
        [string]::IsNullOrWhiteSpace($RedisHost) -or $RedisPort -lt 1 -or $RedisPort -gt 65535)) {
    throw 'Redis verification requires explicit RedisCliPath, RedisHost and RedisPort. Configure server Redis separately.'
}
$BaseUrl = $BaseUrl.TrimEnd('/')
$operatorName = 'cache-e2e'

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

# Run against a server backed by MySQL after Flyway V2. Creates data only; no cleanup/service management.
$health = Invoke-Api 'GET' '/actuator/health' $null
Assert-Equal $health.status 'UP' 'health'
$project = Invoke-Api 'POST' '/api/v1/projects' @{ projectKey = $ProjectKey; name = 'Evaluation evaluation' } 201
Assert-Equal $project.projectKey $ProjectKey 'project key'
$projectPath = "/api/v1/projects/$ProjectKey"
$environment = Invoke-Api 'POST' "$projectPath/environments" @{ envKey = 'prod'; name = 'Production' } 201
Assert-Equal $environment.projectId $project.id 'environment owner'
$flag = Invoke-Api 'POST' "$projectPath/flags" @{
    flagKey = 'new-payment-flow'; name = 'New payment flow'; valueType = 'BOOLEAN'
    variants = @(@{ variantKey = 'old'; value = $false }, @{ variantKey = 'new'; value = $true })
} 201
Assert-Equal $flag.valueType 'BOOLEAN' 'flag type'
$configPath = "$projectPath/environments/prod/flags/new-payment-flow"
$config = Invoke-Api 'POST' "$configPath/config" @{ enabled = $true; defaultVariantKey = 'old' } 201
Assert-Equal $config.version 0 'initial version'
$policy = @{
    expectedVersion = 0
    rules = @(@{
        priority = 10; match = 'ALL'; variantKey = 'new'
        conditions = @(
            @{ attribute = 'country'; operator = 'EQ'; value = 'JP' },
            @{ attribute = 'vipLevel'; operator = 'GTE'; value = 3 }
        )
    })
    rollout = @(@{ variantKey = 'new'; weight = 1000 }, @{ variantKey = 'old'; weight = 9000 })
}
$saved = Invoke-Api 'PUT' "$configPath/evaluation-policy" $policy
Assert-Equal $saved.version 1 'policy version'
Assert-Equal $saved.policy.rules[0].priority 10 'policy priority'
$request = @{
    projectKey = $ProjectKey; environmentKey = 'prod'; flagKey = 'new-payment-flow'
    context = @{ userId = 'user-123'; country = 'JP'; vipLevel = 5; appVersion = '2.3.1' }
}
$matched = Invoke-Api 'POST' '/api/v1/evaluate' $request
Assert-Equal $matched.variantKey 'new' 'rule variant'
Assert-Equal $matched.value $true 'rule value'
Assert-Equal $matched.reason 'RULE_MATCH' 'rule reason'
Assert-Equal $matched.matchedRulePriority 10 'matched priority'
Assert-Equal $matched.configVersion 1 'evaluated version'
Assert-Equal $matched.bucket $null 'rule has no bucket'
$request.context.country = 'US'
$ordinary = Invoke-Api 'POST' '/api/v1/evaluate' $request
Assert-Equal $ordinary.reason 'PERCENTAGE_ROLLOUT' 'ordinary reason'
if ($null -eq $ordinary.bucket -or $ordinary.bucket -lt 0 -or $ordinary.bucket -gt 9999) { throw 'Invalid bucket' }
$expectedVariant = if ($ordinary.bucket -lt 1000) { 'new' } else { 'old' }
Assert-Equal $ordinary.variantKey $expectedVariant 'bucket interval'
Assert-Equal $ordinary.value ($expectedVariant -eq 'new') 'rollout value'
for ($i = 0; $i -lt 5; $i++) {
    $again = Invoke-Api 'POST' '/api/v1/evaluate' $request
    Assert-Equal $again.bucket $ordinary.bucket 'stable bucket'
    Assert-Equal $again.variantKey $ordinary.variantKey 'stable variant'
    Assert-Equal $again.reason 'PERCENTAGE_ROLLOUT' 'stable reason'
}
# Change actual policy content so a stale write cannot silently overwrite the latest policy.
$policy.expectedVersion = 1
$policy.rules[0].priority = 5
$saved = Invoke-Api 'PUT' "$configPath/evaluation-policy" $policy
Assert-Equal $saved.version 2 'second policy version'
$beforeConflict = @(Invoke-Api 'GET' "/api/v1/audits?projectKey=$ProjectKey&limit=100" $null)
$policy.expectedVersion = 0
$policy.rules[0].priority = 99
$conflict = Invoke-Api 'PUT' "$configPath/evaluation-policy" $policy 409
Assert-Equal $conflict.code 'optimistic_lock_conflict' 'stale error'
$persisted = Invoke-Api 'GET' "$configPath/config" $null
Assert-Equal $persisted.version 2 'stale preserves version'
Assert-Equal $persisted.enabled $true 'policy preserves enabled'
Assert-Equal $persisted.defaultVariantId $config.defaultVariantId 'policy preserves default'
$persistedPolicy = $persisted.evaluationPolicyJson | ConvertFrom-Json
Assert-Equal $persistedPolicy.rules[0].priority 5 'stale preserves latest policy'
$audits = @(Invoke-Api 'GET' "/api/v1/audits?projectKey=$ProjectKey&limit=100" $null)
Assert-Equal $audits.Count $beforeConflict.Count 'stale adds no audit'
$policyAudits = @($audits | Where-Object operation -EQ 'EVALUATION_POLICY_UPDATED')
Assert-Equal $policyAudits.Count 2 'successful policy audits'
Assert-Equal $policyAudits[0].operatorName $operatorName 'policy audit operator'
Assert-Equal $policyAudits[0].before.configVersion 1 'audit before version'
Assert-Equal $policyAudits[0].after.configVersion 2 'audit after version'
Assert-Equal $policyAudits[0].before.evaluationPolicy.rules[0].priority 10 'audit old policy'
Assert-Equal $policyAudits[0].after.evaluationPolicy.rules[0].priority 5 'audit new policy'
# Verify Kill Switch takes precedence and its write preserves the policy.
$disabled = Invoke-Api 'POST' "$configPath/disable" @{ expectedVersion = 2 }
Assert-Equal $disabled.version 3 'disable version'
$request.context.country = 'JP'
$off = Invoke-Api 'POST' '/api/v1/evaluate' $request
Assert-Equal $off.reason 'DISABLED' 'kill switch precedence'
Assert-Equal $off.variantKey 'old' 'disabled default'
Assert-Equal $off.value $false 'disabled value'
Assert-Equal $off.bucket $null 'disabled bucket'
$enabled = Invoke-Api 'POST' "$configPath/enable" @{ expectedVersion = 3 }
Assert-Equal $enabled.version 4 'enable version'
$restored = Invoke-Api 'POST' '/api/v1/evaluate' $request
Assert-Equal $restored.reason 'RULE_MATCH' 'switch preserves policy'
Assert-Equal $restored.matchedRulePriority 5 'latest policy restored'
Assert-Equal $restored.configVersion 4 'latest config version'

# Warm L1, then prove each write path invalidates the locally cached snapshot.
$policy.expectedVersion = 4
$policy.rules[0].priority = 1
$policy.rules[0].variantKey = 'old'
$policy.rollout = @(@{ variantKey = 'new'; weight = 10000 })
$updated = Invoke-Api 'PUT' "$configPath/evaluation-policy" $policy
Assert-Equal $updated.version 5 'Cache policy version'
$ruleAfterWrite = Invoke-Api 'POST' '/api/v1/evaluate' $request
Assert-Equal $ruleAfterWrite.variantKey 'old' 'policy invalidation variant'
Assert-Equal $ruleAfterWrite.reason 'RULE_MATCH' 'policy invalidation reason'
Assert-Equal $ruleAfterWrite.configVersion 5 'policy invalidation version'
$request.context.country = 'US'
$rolloutAfterWrite = Invoke-Api 'POST' '/api/v1/evaluate' $request
Assert-Equal $rolloutAfterWrite.variantKey 'new' '100 percent rollout'
Assert-Equal $rolloutAfterWrite.reason 'PERCENTAGE_ROLLOUT' 'rollout reason preserved'
Assert-Equal $rolloutAfterWrite.bucket $ordinary.bucket 'policy write does not change stable bucket'
$changedDefault = Invoke-Api 'PUT' "$configPath/config" @{ expectedVersion = 5; enabled = $false; defaultVariantKey = 'new' }
Assert-Equal $changedDefault.version 6 'default and kill switch version'
$disabledNewDefault = Invoke-Api 'POST' '/api/v1/evaluate' $request
Assert-Equal $disabledNewDefault.reason 'DISABLED' 'config update invalidation'
Assert-Equal $disabledNewDefault.variantKey 'new' 'latest default variant'
Assert-Equal $disabledNewDefault.value $true 'disabled returns configured default, not hardcoded false'
Assert-Equal $disabledNewDefault.configVersion 6 'disabled cache version'
$enabledAgain = Invoke-Api 'POST' "$configPath/enable" @{ expectedVersion = 6 }
Assert-Equal $enabledAgain.version 7 'enabled again version'
$request.context.country = 'JP'
$latest = Invoke-Api 'POST' '/api/v1/evaluate' $request
Assert-Equal $latest.reason 'RULE_MATCH' 'latest rule after enable'
Assert-Equal $latest.variantKey 'old' 'latest rule variant after enable'
Assert-Equal $latest.configVersion 7 'latest version after enable'

# A confirmed missing config may be negatively cached; creating it must clear that marker.
$lateFlag = Invoke-Api 'POST' "$projectPath/flags" @{
    flagKey = 'late-config'; name = 'Late config'; valueType = 'BOOLEAN'
    variants = @(@{ variantKey = 'old'; value = $false })
} 201
$request.flagKey = 'late-config'
for ($i = 0; $i -lt 2; $i++) {
    $missing = Invoke-Api 'POST' '/api/v1/evaluate' $request 404
    Assert-Equal $missing.code 'resource_not_found' 'negative lookup'
}
$lateConfig = Invoke-Api 'POST' "$projectPath/environments/prod/flags/late-config/config" @{
    enabled = $true; defaultVariantKey = 'old'
} 201
$afterCreate = Invoke-Api 'POST' '/api/v1/evaluate' $request
Assert-Equal $afterCreate.reason 'DEFAULT' 'create clears negative entry'
Assert-Equal $afterCreate.value $false 'created config evaluates'

Write-Output "REAL_MYSQL_CACHE_E2E=PASSED (running server must use MySQL); project=$ProjectKey"
if ($RedisEnabled) {
    # Read only the exact snapshot produced by this run. REDISCLI_AUTH may be supplied by the caller.
    $redisKey = "rolloutcore:eval:v1:${ProjectKey}:prod:new-payment-flow"
    $payload = & $RedisCliPath --raw -h $RedisHost -p $RedisPort HGET $redisKey payload
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($payload -join ''))) { throw 'Redis snapshot not observed' }
    $redisSnapshot = ($payload -join "`n") | ConvertFrom-Json
    Assert-Equal $redisSnapshot.key.projectKey $ProjectKey 'real Redis snapshot scope'
    Assert-Equal $redisSnapshot.configVersion 7 'real Redis snapshot version'
    Assert-Equal $redisSnapshot.policy.rules[0].variantKey 'old' 'real Redis latest policy'
    $ttl = & $RedisCliPath --raw -h $RedisHost -p $RedisPort PTTL $redisKey
    if ($LASTEXITCODE -ne 0 -or [long]$ttl -le 0) { throw 'Redis snapshot must have a positive TTL' }
    Write-Output 'REAL_REDIS_CACHE_E2E=PASSED (observed real L2 JSON/version/TTL; does not prove every request source)'
} else {
    Write-Output 'REAL_REDIS_CACHE_E2E=NOT_RUN (no explicit Redis verification requested)'
}
