param(
    [ValidateSet('read', 'write', 'workflow')][string]$Scenario = 'read',
    [ValidateRange(1, 3)][int]$Instances = 1,
    [ValidateSet('disabled', 'cold', 'warm')][string]$CacheMode = 'disabled',
    [ValidateSet('vus', 'arrival')][string]$Mode = 'vus',
    [int[]]$Levels = @(10, 25, 50, 100, 200),
    [ValidateRange(5, 3600)][int]$Seconds = 30,
    [ValidateRange(5, 600)][int]$WarmupSeconds = 10,
    [ValidateRange(1, 10)][int]$Repeats = 1,
    [ValidateRange(1, 100)][int]$PoolSize = 10,
    [ValidateRange(1024, 65535)][int]$Port = 18080
)
$ErrorActionPreference = 'Stop'
if ($Scenario -ne 'read' -and $CacheMode -ne 'disabled') { throw 'Compare cache modes using the read scenario.' }
if (!$Levels.Count -or @($Levels | Where-Object { $_ -lt 1 -or $_ -gt 1000 }).Count) { throw 'Levels must be between 1 and 1000.' }
$root = Split-Path $PSScriptRoot -Parent
$project = 'baseline-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 6)
$compose = @('compose', '-p', $project, '-f', "$root/compose.yaml", '-f', "$root/compose.baseline.yaml")
$settings = @{ APP_PORT = "$Port"; CACHE_ENABLED = ($CacheMode -ne 'disabled').ToString().ToLowerInvariant();
    CACHE_TTL_SECONDS = '3600'; SPRING_PROFILES_ACTIVE = 'default'; LB_ALGORITHM = 'roundrobin'; DB_POOL_SIZE = "$PoolSize" }
$previous = @{}
function Invoke-Compose([string[]]$Arguments) {
    & docker @compose @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed: $Arguments" }
}
function Get-DatabaseSnapshot {
    $sql = "SELECT row_to_json(s) FROM (SELECT xact_commit, xact_rollback, tup_returned, tup_fetched, blks_read, blks_hit, (SELECT coalesce(sum(seq_tup_read + idx_tup_fetch),0) FROM pg_stat_user_tables WHERE relname='products') AS product_tuples_read FROM pg_stat_database WHERE datname=current_database()) s;"
    $output = Invoke-Compose @('exec', '-T', 'db', 'psql', '-U', 'orders', '-d', 'orders', '-At', '-c', $sql)
    return ($output | ConvertFrom-Json)
}
Push-Location $root
try {
    foreach ($key in $settings.Keys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key); [Environment]::SetEnvironmentVariable($key, $settings[$key]) }
    New-Item -ItemType Directory -Force "$root/results" | Out-Null
    Invoke-Compose @('up', '--build', '-d', '--wait', '--scale', "app=$Instances")
    Get-Content "$root/load-tests/seed.sql" -Raw | & docker @compose exec -T db psql -U orders -d orders -v ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) { throw 'Could not seed the isolated database.' }
    $ids = @(Invoke-Compose @('ps', '-q', 'app'))
    $hostnames = @(& docker inspect --format '{{.Config.Hostname}}' @ids)
    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve replica identities.' }
    $dockerInfo = & docker info --format '{{json .}}' | ConvertFrom-Json
    $revision = & git rev-parse HEAD
    $metadata = @{ project = $project; revision = "$revision"; scenario = $Scenario; instances = $Instances;
        cacheMode = $CacheMode; mode = $Mode; poolSizePerReplica = $PoolSize; cacheTtlSeconds = 3600;
        dockerCpus = $dockerInfo.NCPU; dockerMemoryBytes = $dockerInfo.MemTotal;
        appCpuPerReplica = 1; appMemoryMiBPerReplica = 512; databaseCpu = 2; databaseMemoryMiB = 1024;
        generatorCpu = 1; generatorMemoryMiB = 1024; redisCpu = 0.5; redisMemoryMiB = 192;
        lbCpu = 0.5; lbMemoryMiB = 128; identities = $hostnames; startedAt = (Get-Date).ToString('o') }
    $metadata | ConvertTo-Json -Depth 5 | Set-Content "$root/results/$project.environment.json" -Encoding UTF8
    foreach ($repeat in 1..$Repeats) {
        foreach ($level in $Levels) {
            foreach ($phase in @('warmup', 'measure')) {
                $duration = if ($phase -eq 'warmup') { $WarmupSeconds } else { $Seconds }
                $name = "$project-$Scenario-$level-$repeat-$phase"
                if ($phase -eq 'measure' -and $Scenario -eq 'read' -and $CacheMode -ne 'disabled') {
                    Invoke-Compose @('exec', '-T', 'redis', 'redis-cli', 'FLUSHDB') | Out-Null
                    if ($CacheMode -eq 'warm') {
                        foreach ($page in 0..4) { Invoke-WebRequest "http://localhost:$Port/api/products?page=$page&size=20" -UseBasicParsing | Out-Null }
                    }
                }
                $before = Get-DatabaseSnapshot
                $job = Start-Job -ArgumentList $project, "$root/results/$name.resources.jsonl" -ScriptBlock {
                    param($ProjectName, $OutputPath)
                    while ($true) {
                        $ContainerIds = @(& docker ps -q --filter "label=com.docker.compose.project=$ProjectName")
                        if (!$ContainerIds.Count) { break }
                        $sample = @(& docker stats --no-stream --format '{{json .}}' @ContainerIds)
                        @{ time = (Get-Date).ToString('o'); containers = @($sample | ForEach-Object { $_ | ConvertFrom-Json }) } |
                            ConvertTo-Json -Depth 5 -Compress | Add-Content $OutputPath -Encoding UTF8
                        Start-Sleep -Seconds 2
                    }
                }
                try {
                    Invoke-Compose @('run', '--rm', '-e', "BUSINESS_SCENARIO=$Scenario", '-e', "LOAD_MODE=$Mode",
                        '-e', "LEVEL=$level", '-e', "SECONDS=$duration", '-e', "PHASE=$phase", '-e', "RUN_NAME=$name",
                        '-e', "INSTANCES=$Instances", '-e', "INSTANCE_IDS=$($hostnames -join ',')", '-e', "CACHE_MODE=$CacheMode",
                        '-e', "REPEAT=$repeat", 'benchmark')
                } finally { Stop-Job $job; Remove-Job $job }
                # PostgreSQL statistics are asynchronous. This delay is outside the measured window.
                Start-Sleep -Seconds 2
                @{ before = $before; after = (Get-DatabaseSnapshot); durationSeconds = $duration } |
                    ConvertTo-Json -Depth 5 | Set-Content "$root/results/$name.database.json" -Encoding UTF8
            }
        }
    }
    & "$PSScriptRoot/report-baseline.ps1"
} finally {
    # Retain the isolated volume and evidence for inspection; never delete the user's existing data.
    & docker @compose down
    foreach ($key in $settings.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key]) }
    Pop-Location
    Write-Host "Isolated project: $project (database volume retained). Results: $root/results"
}
