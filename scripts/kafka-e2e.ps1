param(
    [string]$MySqlClient = 'mysql.exe',
    [ValidatePattern('^[a-z0-9._-]{1,100}$')]
    [string]$ProjectKey = ('kafka-' + [Guid]::NewGuid().ToString('N')),
    [switch]$RunOutageExperiment
)
# Starts two NEW application processes, never stops existing Java/Windows services.
# Requires: built server JAR, Docker Compose, mysql CLI, and actual DB credentials in environment.
# Docker automation only; the 2026-09-16 Windows native manual run does not validate this script.
# See docs/KAFKA_E2E_REPORT.md: native propagation/recovery passed, final SENT was not SQL-queried.
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$compose = Join-Path $repo 'infra/kafka-compose.yml'
$jar = Join-Path $repo 'rolloutcore-server/target/rolloutcore-server-0.1.0-SNAPSHOT.jar'
$run = Join-Path $repo ("workspace/kafka-e2e-" + [Guid]::NewGuid().ToString('N'))
$topic = 'rolloutcore.config-events.' + $ProjectKey
$composeProject = 'rolloutcore-kafka'
$baseA = 'http://127.0.0.1:8080'
$baseB = 'http://127.0.0.1:8082'
$processes = @()
$brokerStopped = $false

function Assert-Equal($Actual, $Expected, [string]$Label) {
    if ($Actual -ne $Expected) { throw "$Label expected=$Expected actual=$Actual" }
}
function Invoke-Compose([string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    $output = & docker compose -p $composeProject -f $compose @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ("Docker command failed: " + ($output -join [Environment]::NewLine)) }
    return $output
}
function Api([string]$Method, [string]$Url, $Body, [int]$Status = 200) {
    $request = @{ Uri=$Url; Method=$Method; UseBasicParsing=$true; TimeoutSec=10; Headers=@{'X-Operator'='kafka-e2e'} }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json; charset=utf-8'
        $request.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20 -Compress))
    }
    $response = Invoke-WebRequest @request
    Assert-Equal ([int]$response.StatusCode) $Status "$Method $Url"
    $content = $response.Content
    if ($content -is [byte[]]) { $content = [Text.Encoding]::UTF8.GetString($content) }
    return ($content | ConvertFrom-Json)
}
function Sql([string]$Query) {
    $saved = $env:MYSQL_PWD
    try {
        # Password is not placed on the command line or in evidence files.
        $env:MYSQL_PWD = $env:ROLLOUTCORE_DB_PASSWORD
        $ErrorActionPreference = 'Continue'
        $output = & $MySqlClient --protocol=TCP --connect-timeout=5 --batch --raw --skip-column-names "--host=$dbHost" "--port=$dbPort" "--user=$dbUser" "--database=$dbName" "--execute=$Query" 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'MySQL CLI query failed; check supplied connection/credentials (not printed).' }
        return ($output -join [Environment]::NewLine).Trim()
    } finally { $env:MYSQL_PWD = $saved }
}
function Wait-For([scriptblock]$Check, [int]$Seconds, [string]$Label) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        if (& $Check) { return }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Label"
}
function Log-Count([string]$Path, [string]$EventId) {
    if (!(Test-Path -LiteralPath $Path)) { return 0 }
    return @(Select-String -LiteralPath $Path -SimpleMatch "eventId=$EventId " |
        Where-Object { $_.Line.Contains('Kafka config applied') }).Count
}
function Wait-Applied($Event, [int]$Seconds = 30) {
    $eventId = $Event.eventId
    Wait-For { ((Log-Count $logA $eventId) -ge 1) -and ((Log-Count $logB $eventId) -ge 1) } $Seconds "both instance subscriptions for $eventId"
    Wait-For { (Sql "SELECT status FROM outbox_event WHERE event_id='$eventId'") -eq 'SENT' } $Seconds "SENT for $eventId"
}
function Event-For([int]$Version) {
    $query = "SELECT JSON_OBJECT('eventId',event_id,'schemaVersion',1,'projectKey',project_key,'environmentKey',environment_key,'flagKey',flag_key,'configVersion',version,'occurredAt',DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ')) FROM outbox_event WHERE project_key='$ProjectKey' AND environment_key='prod' AND flag_key='pay' AND version=$Version"
    return ((Sql $query) | ConvertFrom-Json)
}
function Evaluate-B {
    return (Api 'POST' "$baseB/api/v1/evaluate" @{projectKey=$ProjectKey; environmentKey='prod'; flagKey='pay'; context=@{userId='kafka-user'}})
}
function Publish($Event) {
    $line = $Event.projectKey + ':' + $Event.environmentKey + ':' + $Event.flagKey + '|' + ($Event | ConvertTo-Json -Compress)
    $ErrorActionPreference = 'Continue'
    $output = $line | & docker compose -p $composeProject -f $compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:19092 --topic $topic --property parse.key=true --property 'key.separator=|' --producer-property acks=all --producer-property enable.idempotence=true --producer-property delivery.timeout.ms=10000 --producer-property request.timeout.ms=5000 2>&1
    if ($LASTEXITCODE -ne 0) { throw ("Kafka replay failed: " + ($output -join [Environment]::NewLine)) }
}
function Save-Groups {
    foreach ($instance in @('instance-a','instance-b')) {
        Invoke-Compose @('exec','-T','kafka','/opt/kafka/bin/kafka-consumer-groups.sh','--bootstrap-server','kafka:19092','--describe','--group',"rolloutcore-cache-$instance") |
            Out-File -Encoding utf8 (Join-Path $run "$instance-group.txt")
    }
}

# Read-only prerequisite checks occur before starting anything.
$null = Get-Command docker -ErrorAction Stop
$null = Get-Command $MySqlClient -ErrorAction Stop
$null = Get-Command java -ErrorAction Stop
& docker version
if ($LASTEXITCODE -ne 0) { throw 'Docker daemon unavailable' }
& docker compose version
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose unavailable' }
if (!(Test-Path -LiteralPath $jar)) { throw 'Build the server JAR with mvn clean verify first.' }
if ([string]::IsNullOrEmpty($env:ROLLOUTCORE_DB_PASSWORD)) { throw 'Set actual ROLLOUTCORE_DB_PASSWORD; this script does not guess credentials.' }
$dbUrl = $env:ROLLOUTCORE_DB_URL
if ([string]::IsNullOrEmpty($dbUrl)) { $dbUrl = 'jdbc:mysql://127.0.0.1:3306/rolloutcore?connectionTimeZone=UTC' }
if ($dbUrl -notmatch '^jdbc:mysql://([^/:?]+)(?::([0-9]+))?/([a-zA-Z0-9_]+)(?:\?.*)?$') { throw 'Use a simple single-host JDBC MySQL URL for this development script.' }
$dbHost = $Matches[1]
$dbPort = if ($Matches[2]) { [int]$Matches[2] } else { 3306 }
$dbName = $Matches[3]
$dbUser = if ($env:ROLLOUTCORE_DB_USERNAME) { $env:ROLLOUTCORE_DB_USERNAME } else { 'rolloutcore' }
Assert-Equal (Sql 'SELECT 1') '1' 'actual MySQL connectivity'
foreach ($port in @(8080,8082)) {
    $probe = New-Object System.Net.Sockets.TcpClient
    try {
        try { $probe.Connect('127.0.0.1',$port) } catch [System.Net.Sockets.SocketException] {}
        if ($probe.Connected) { throw "Port $port is occupied. Stop the intended application yourself or use manual steps; no process was killed." }
    } finally { $probe.Dispose() }
}
$null = New-Item -ItemType Directory -Path $run
$logA = Join-Path $run 'instance-a.log'
$logB = Join-Path $run 'instance-b.log'
try {
    Invoke-Compose @('up','-d','--wait','--wait-timeout','150','kafka') | Out-Host
    Invoke-Compose @('exec','-T','kafka','/opt/kafka/bin/kafka-topics.sh','--bootstrap-server','kafka:19092','--create','--if-not-exists','--topic',$topic,'--partitions','3','--replication-factor','1') | Out-Host
    Invoke-Compose @('exec','-T','kafka','/opt/kafka/bin/kafka-topics.sh','--bootstrap-server','kafka:19092','--describe','--topic',$topic) |
        Out-File -Encoding utf8 (Join-Path $run 'topic.txt')
    foreach ($instance in @(@{id='instance-a';port=8080;log=$logA},@{id='instance-b';port=8082;log=$logB})) {
        $arguments = @('-jar',('"' + $jar + '"'),"--server.port=$($instance.port)",
            '--rolloutcore.events.transport=kafka',"--rolloutcore.events.instance-id=$($instance.id)",
            "--rolloutcore.events.topic=$topic",'--spring.kafka.bootstrap-servers=127.0.0.1:9092',
            '--rolloutcore.events.listener-auto-startup=true','--rolloutcore.outbox.enabled=true',
            '--rolloutcore.cache.redis-enabled=false','--rolloutcore.cache.l1-ttl=10m','--rolloutcore.cache.lkg-ttl=15m',
            '--rolloutcore.outbox.initial-delay-ms=120000','--rolloutcore.outbox.poll-interval-ms=1000')
        $processes += Start-Process java -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden -PassThru -RedirectStandardOutput $instance.log -RedirectStandardError (Join-Path $run "$($instance.id)-stderr.log")
    }
    $processes | Select-Object Id,ProcessName | Out-File -Encoding utf8 (Join-Path $run 'processes.txt')
    foreach ($url in @($baseA,$baseB)) {
        Wait-For { try { (Api 'GET' "$url/actuator/health" $null).status -eq 'UP' } catch { $false } } 60 "$url health"
    }
    $project = Api 'POST' "$baseA/api/v1/projects" @{projectKey=$ProjectKey; name='Kafka cross-JVM E2E'} 201
    $path = "$baseA/api/v1/projects/$ProjectKey"
    $null = Api 'POST' "$path/environments" @{envKey='prod';name='Production'} 201
    $null = Api 'POST' "$path/flags" @{flagKey='pay';name='Pay';valueType='BOOLEAN';variants=@(@{variantKey='old';value=$false},@{variantKey='new';value=$true})} 201
    $configPath = "$path/environments/prod/flags/pay/config"
    $null = Api 'POST' $configPath @{enabled=$true;defaultVariantKey='new'} 201
    Assert-Equal (Evaluate-B).value $true 'B warm value'
    Assert-Equal (Evaluate-B).configVersion 0 'B warm version'
    $warmAt = [DateTime]::UtcNow
    $null = Api 'PUT' $configPath @{enabled=$false;defaultVariantKey='old';expectedVersion=0}
    $event1 = Event-For 1
    # Initial relay delay makes PENDING observable. Fail explicitly if startup took too long.
    Assert-Equal (Sql "SELECT status FROM outbox_event WHERE event_id='$($event1.eventId)'") 'PENDING' 'committed Outbox before first relay'
    "eventId=$($event1.eventId) status=PENDING" | Out-File -Encoding utf8 (Join-Path $run 'pending.txt')
    Wait-Applied $event1 150
    if (([DateTime]::UtcNow - $warmAt).TotalSeconds -ge 600) { throw 'L1 TTL elapsed; this run cannot prove event-driven invalidation.' }
    $fresh = Evaluate-B
    Assert-Equal $fresh.configVersion 1 'B new version'
    Assert-Equal $fresh.value $false 'B new value before L1 TTL'
    # Read the actual Broker log; this is independent of listener invocation logs.
    $ErrorActionPreference = 'Continue'
    $brokerRecords = & docker compose -p $composeProject -f $compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:19092 --topic $topic --from-beginning --max-messages 2 --timeout-ms 10000 2>&1
    $brokerExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $brokerRecords | Out-File -Encoding utf8 (Join-Path $run 'broker-records.txt')
    if ($brokerExit -ne 0 -or !($brokerRecords -match [regex]::Escape($event1.eventId))) { throw 'Broker evidence missing eventId' }

    # Duplicate same event twice, and prove both subscriptions consumed both copies.
    $aCount = Log-Count $logA $event1.eventId
    $bCount = Log-Count $logB $event1.eventId
    $auditCount = Sql "SELECT COUNT(*) FROM ff_audit_log WHERE project_id=$($project.id)"
    $outboxCount = Sql "SELECT COUNT(*) FROM outbox_event WHERE project_key='$ProjectKey'"
    Publish $event1
    Publish $event1
    Wait-For { ((Log-Count $logA $event1.eventId) -ge ($aCount+2)) -and ((Log-Count $logB $event1.eventId) -ge ($bCount+2)) } 30 'duplicate messages on both groups'
    Assert-Equal (Evaluate-B).configVersion 1 'duplicate keeps version'
    Assert-Equal (Sql "SELECT COUNT(*) FROM ff_audit_log WHERE project_id=$($project.id)") $auditCount 'duplicate does not write audit'
    Assert-Equal (Sql "SELECT COUNT(*) FROM outbox_event WHERE project_key='$ProjectKey'") $outboxCount 'duplicate does not write outbox'

    # Real v2 commit and propagation, followed by replay of older v1.
    $null = Api 'PUT' $configPath @{enabled=$true;defaultVariantKey='new';expectedVersion=1}
    $event2 = Event-For 2
    Wait-Applied $event2
    Assert-Equal (Evaluate-B).configVersion 2 'v2 received'
    Publish $event1
    Wait-For { @(Select-String -LiteralPath $logB -SimpleMatch 'incomingVersion=1 minimumVersion=2' |
        Where-Object { $_.Line.Contains("projectKey=$ProjectKey,") }).Count -gt 0 } 30 'B version guard ignores v1 after v2'
    Assert-Equal (Evaluate-B).configVersion 2 'out of order does not regress'
    Assert-Equal (Evaluate-B).value $true 'out of order keeps value'
    Save-Groups
    Write-Output "REAL_KAFKA_E2E=PASSED project=$ProjectKey topic=$topic"
    Write-Output 'REAL_KAFKA_DUPLICATE_E2E=PASSED'
    Write-Output 'REAL_KAFKA_OUT_OF_ORDER_E2E=PASSED'

    if ($RunOutageExperiment) {
        # Only stops the Kafka container in this dedicated Compose project, never Java/MySQL/Windows services.
        $outageWarmAt = [DateTime]::UtcNow
        Invoke-Compose @('stop','kafka') | Out-Host
        $brokerStopped = $true
        $null = Api 'PUT' $configPath @{enabled=$false;defaultVariantKey='old';expectedVersion=2}
        $event3 = Event-For 3
        Assert-Equal (Sql "SELECT status FROM outbox_event WHERE event_id='$($event3.eventId)'") 'PENDING' 'broker offline keeps Outbox pending'
        Assert-Equal (Api 'GET' $configPath $null).version 3 'config committed while Kafka offline'
        Wait-For {
            @((Get-Content -LiteralPath $logA) + (Get-Content -LiteralPath $logB) |
                Where-Object { $_.Contains("Outbox send failed; retaining PENDING event id=$($event3.eventId)") }).Count -gt 0
        } 30 'failed relay attempt'
        Assert-Equal (Sql "SELECT status FROM outbox_event WHERE event_id='$($event3.eventId)'") 'PENDING' 'failed send remains pending'
        Invoke-Compose @('up','-d','--wait','--wait-timeout','150','kafka') | Out-Host
        $brokerStopped = $false
        Wait-Applied $event3 60
        if (([DateTime]::UtcNow - $outageWarmAt).TotalSeconds -ge 600) { throw 'Outage exceeded L1 TTL evidence window.' }
        Assert-Equal (Evaluate-B).configVersion 3 'recovery new version'
        Assert-Equal (Evaluate-B).value $false 'recovery new value'
        Save-Groups
        Write-Output 'REAL_KAFKA_OUTAGE_RECOVERY_E2E=PASSED'
    } else { Write-Output 'REAL_KAFKA_OUTAGE_RECOVERY_E2E=NOT_RUN (use -RunOutageExperiment)' }
} finally {
    if ($brokerStopped) {
        Write-Warning 'Restoring the dedicated Kafka container after failed outage experiment.'
        Invoke-Compose @('up','-d','kafka') | Out-Host
    }
    Write-Output "Evidence directory: $run"
    Write-Output 'Application processes are left running; stop them yourself after review. No user processes were killed.'
    $processes | Select-Object Id,ProcessName | Format-Table | Out-Host
}
