param([string]$ResultsPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'results'))
$ErrorActionPreference = 'Stop'
$rows = @(Get-ChildItem $ResultsPath -Filter '*-measure.json' | ForEach-Object {
    $r = (Get-Content $_.FullName -Raw | ConvertFrom-Json).report
    $dbPath = $_.FullName -replace '\.json$', '.database.json'
    $tuples = $null
    if (Test-Path $dbPath) {
        $db = Get-Content $dbPath -Raw | ConvertFrom-Json
        $tuples = [double]$db.after.product_tuples_read - [double]$db.before.product_tuples_read
    }
    [pscustomobject]@{ Run = $r.runName; Session = ($r.runName -replace '-(read|write|workflow)-\d+-\d+-measure$', ''); Scenario = $r.scenario; Mode = $r.mode; Instances = $r.instances;
        Cache = $r.cacheMode; Repeat = $r.repeat; Level = $r.level; RPS = $r.rps;
        AvgMs = $r.avgMs; P50Ms = $r.p50Ms; P95Ms = $r.p95Ms; P99Ms = $r.p99Ms;
        ErrorRate = $r.requestErrorRate; WorkflowErrorRate = $r.workflowErrorRate;
        WorkflowP95Ms = $r.workflowP95Ms; WorkflowsPerSecond = $r.workflowsPerSecond;
        Dropped = $r.droppedIterations; Acceptable = $r.acceptable;
        CacheHits = $r.cacheHits; CacheMisses = $r.cacheMisses; CacheBypasses = $r.cacheBypasses;
        ProductTuplesRead = $tuples }
})
if (!$rows.Count) { throw 'No completed measurement files found.' }
$rows | Export-Csv "$ResultsPath/baseline.csv" -NoTypeInformation -Encoding UTF8
function Escape($Value) { [System.Net.WebUtility]::HtmlEncode([string]$Value) }
function Chart($Samples, [string]$Metric, [string]$Label) {
    $maximum = [double](($Samples | Measure-Object $Metric -Maximum).Maximum)
    if ($maximum -le 0) { $maximum = 1 }
    $bars = for ($i = 0; $i -lt $Samples.Count; $i++) {
        $height = 150 * [double]$Samples[$i].$Metric / $maximum
        $x = 45 + $i * 65
        $y = 175 - $height
        $h = $height.ToString('0.##', [Globalization.CultureInfo]::InvariantCulture)
        $ys = $y.ToString('0.##', [Globalization.CultureInfo]::InvariantCulture)
        "<rect x='$x' y='$ys' width='40' height='$h' fill='#2563eb'/><text x='$x' y='195'>$($Samples[$i].Level)</text><title>$(Escape $Samples[$i].$Metric)</title>"
    }
    $width = [Math]::Max(400, 70 + $Samples.Count * 65)
    "<h3>$(Escape $Label)</h3><p>Top of scale: $([Math]::Round($maximum,2)); horizontal axis: load level.</p><svg role='img' aria-label='$(Escape $Label)' viewBox='0 0 $width 210'>$($bars -join '')</svg>"
}
$sections = foreach ($group in ($rows | Group-Object Session, Scenario, Mode, Instances, Cache, Repeat)) {
    $samples = @($group.Group | Sort-Object Level)
    $best = $samples | Where-Object Acceptable | Sort-Object RPS -Descending | Select-Object -First 1
    $firstFailure = $samples | Where-Object { !$_.Acceptable } | Select-Object -First 1
    $capacity = if ($best) { "Highest observed RPS within SLO: $([Math]::Round($best.RPS,2)) at level $($best.Level)." } else { 'No measured level met the SLO.' }
    $saturation = if ($firstFailure) { "First SLO/load-delivery failure: level $($firstFailure.Level). Candidate overload boundary; inspect resource evidence." } else { 'No SLO failure observed. Saturation was not established; increase load if the generator has headroom.' }
    "<section><h2>$(Escape $group.Name)</h2><p>$capacity $saturation</p>" +
        (Chart $samples 'RPS' 'Completed HTTP requests per second') +
        (Chart $samples 'P95Ms' 'HTTP p95 latency, ms') +
        ($samples | Select-Object Level,RPS,AvgMs,P50Ms,P95Ms,P99Ms,ErrorRate,WorkflowErrorRate,Dropped,Acceptable,ProductTuplesRead | ConvertTo-Html -Fragment | Out-String) + '</section>'
}
$html = @"
<!doctype html><html lang="en"><meta charset="utf-8"><title>Lab 5 baseline</title>
<style>body{font:16px system-ui;margin:30px;color:#172033}section{margin:30px 0;padding:20px;background:#f4f6fa}table{border-collapse:collapse;font-size:13px}td,th{padding:7px;border:1px solid #ccd3df}svg{max-width:800px;width:100%}text{font-size:12px}</style>
<h1>Performance baseline</h1><p>HTTP SLO: p95 ≤ 500 ms, p99 ≤ 1000 ms; request and workflow error rates ≤ 1%; no dropped iterations. Error rates are fractions. Percentiles are per run and are never averaged.</p>
<p>Primary bottleneck: requires interpretation of *.resources.jsonl and *.database.json. These charts do not establish causality. Product tuples read are a database-work proxy, not SQL query counts. Only compare runs with matching environment metadata and durations. Cold means an empty cache at stage start; it warms during measurement.</p>
$($sections -join "`n")
<p>Scaling factor = RPS(N replicas) / RPS(1 replica) at the same scenario, load, mode and cache setting. Efficiency = factor / N. Cache latency reduction = 1 - warm p95 / disabled p95; database work reduction = 1 - warm product tuples read / disabled product tuples read. Avoid division by zero; compare equal-duration runs and also normalize database work per request.</p>
</html>
"@
$html | Set-Content "$ResultsPath/baseline.html" -Encoding UTF8
Write-Host "Report: $ResultsPath/baseline.html; table: $ResultsPath/baseline.csv"
