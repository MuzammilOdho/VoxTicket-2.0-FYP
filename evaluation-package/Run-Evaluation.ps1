<#
.SYNOPSIS
  VoxTicket complete thesis evaluation runner (Windows / PowerShell).
.DESCRIPTION
  Runs every evaluation area that can be automated and writes thesis-ready
  reports from measured JSON only. Nothing is typed in by hand.
  - E1: mvn clean test (default suite) + pytest, parsed from XML reports.
  - E2: live probes against a running backend (or BLOCKED if it is down).
  - E3: the Phase3EvaluationSuite (real ONNX routing) + one live arm per run.
  - E4: RAG dataset validation + manual harness + Recall/MRR/nDCG metrics.
  - E5: processed only when you pass a filled voice template CSV.
  - Reports: FINAL-EVALUATION-REPORT.md + CHAPTER-7-TABLES.md, generated.

  Synced to main a78ff41 (evaluation restart, 2026-11-03). The 2026-11-01
  evaluation commits were intentionally abandoned by the user; this package
  does not depend on them. v3: extracting this zip at the repository root
  ALSO applies the repo files it carries — the re-anchored seed files
  (anchor 2026-10-24; ORD-10006 eligible through 2026-11-13, after which
  you re-anchor again with the same formula) and the gated RAG harness
  test. Review them with git diff after extracting; the runner never
  modifies your repo. Current main's production simple-margin default is
  the pre-restart calibration, and its cost table prices all tiers at 0.0
  (excluded). v7 FINAL: self-run backend workflow (you run the backend
  yourself; the script uses it and never stops it) — see README.

  Extract this package at the repository root so that this script sits in
  <repo>/evaluation-package/Run-Evaluation.ps1. RepoRoot then defaults to
  the script's parent folder. Outputs go to evaluation-package/results/<timestamp>/
  and evaluation-package/reports/.

  Rate limiting (RPM): Groq already returned 429s at single-session load, so
  every API-calling script paces itself (default 10 requests/minute) and
  backs off on 429 (30/60/120/240s, honouring Retry-After). Every 429 is
  recorded in the output JSON as data, never hidden. Raise -Rpm only if
  your key's plan allows it.

  Credentials: none are stored anywhere in this package. Admin probes use a
  throwaway username with a random password. Groq/Voyage keys stay in your
  environment (IntelliJ run configuration), never in files here.

.EXAMPLE
  # Fully hands-off run (script builds and starts the backend per arm):
  # needs GROQ_API_KEY / VOYAGE_API_KEY in this PowerShell session and a
  # free port 8080. If a backend is already running, it is used instead
  # and never stopped.
  .\Run-Evaluation.ps1 -AutoBackend

.EXAMPLE
  # Full run except live arms (backend still needed for E2 probes):
  .\Run-Evaluation.ps1 -ModelPath "D:\dev\VoxTicket-models\multilingual-e5-small"

.EXAMPLE
  # Full run with the HYBRID live arm (backend must run with that strategy):
  .\Run-Evaluation.ps1 -ModelPath "D:\dev\VoxTicket-models\multilingual-e5-small" -LiveArms "HYBRID" -CustomerPhone "+923001234567"

.EXAMPLE
  # After the 10 manual voice calls, with the filled template:
  .\Run-Evaluation.ps1 -ModelPath "D:\dev\VoxTicket-models\multilingual-e5-small" -VoiceCsv ".\results\voice_calls_filled.csv"
#>
param(
    [string]$RepoRoot = "",
    [string]$ModelPath = "",
    [string]$BackendUrl = "http://localhost:8080",
    [string]$CustomerPhone = "",
    [int]$Rpm = 10,
    [string]$LiveArms = "HYBRID",
    [switch]$SkipLive,
    [string]$VoiceCsv = "",
    [double]$PricePer1k = 0,
    [string]$EmbeddingProvider = "",
    [switch]$AutoBackend
)

$ErrorActionPreference = 'Continue'
$PackageDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($RepoRoot)) { $RepoRoot = Split-Path -Parent $PackageDir }
$ScriptsDir = Join-Path $PackageDir 'scripts'
$DatasetsDir = Join-Path $PackageDir 'datasets'
$TemplatesDir = Join-Path $PackageDir 'templates'
$Stamp = Get-Date -Format 'yyyy-MM-dd-HHmm'
$ResultsDir = Join-Path (Join-Path $PackageDir 'results') $Stamp
$ReportsDir = Join-Path $PackageDir 'reports'
New-Item -ItemType Directory -Force -Path $ResultsDir, $ReportsDir | Out-Null

# Model path: explicit flag wins, else the environment variable, else the
# standard locations. The E3 suite is skipped (BLOCKED) when nothing resolves.
if ([string]::IsNullOrWhiteSpace($ModelPath)) {
    $candidates = @()
    if ($env:VOXTICKET_ROUTING_MODEL_PATH) { $candidates += $env:VOXTICKET_ROUTING_MODEL_PATH }
    $candidates += 'D:\dev\VoxTicket-models\multilingual-e5-small'
    $candidates += (Join-Path (Split-Path -Parent $RepoRoot) 'VoxTicket-models\multilingual-e5-small')
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'model.onnx'))) { $ModelPath = $candidate; break }
    }
    if ($ModelPath) { Write-Host "  Model path auto-resolved: $ModelPath" }
    else { Write-Host '  No model path given or found: E3 suite will be skipped (BLOCKED). Pass -ModelPath.' }
}

# The application context itself needs the routing model directory (env
# ROUTING_MODEL_PATH). The E3 suite passes its own -D property, but plain
# `mvn clean test` does not — without the env var, the
# routingEmbeddingService bean fails with "Routing model directory is not
# configured" and every Spring-context test errors (140 errors on the
# 2026-11-03 run, while unit tests passed). Default it from the resolved
# model path when the user has not set it; never override an explicit
# value. Child mvn/java processes inherit it. This also fixes the E4
# harness, which boots the same context.
if ([string]::IsNullOrWhiteSpace($env:ROUTING_MODEL_PATH) -and $ModelPath) {
    $env:ROUTING_MODEL_PATH = $ModelPath
    Write-Host "  ROUTING_MODEL_PATH defaulted to $ModelPath for the test JVMs."
}

$StepLog = @{}
function Step {
    param([string]$Name, [scriptblock]$Body)
    Write-Host ""
    Write-Host "===== $Name =====" -ForegroundColor Cyan
    try { & $Body; $StepLog[$Name] = 'done' }
    catch { Write-Host "  !! $Name failed: $($_.Exception.Message)" -ForegroundColor Yellow; $StepLog[$Name] = 'failed' }
}

function Test-Backend {
    try {
        $resp = Invoke-WebRequest -Uri "$BackendUrl/actuator/health" -Method Get -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop
        return ($resp.StatusCode -eq 200)
    } catch { return $false }
}

# Backend helpers are defined HERE (before any Step uses them): PowerShell
# does not hoist function definitions, so defining them after E2-probes
# made E2 crash with 'Stop-BackendArm is not recognized'.
$script:BackendJar = $null
function Start-BackendArm {
    param([string]$Arm)
    if (-not $script:BackendJar) {
        Push-Location (Join-Path $RepoRoot 'java-backend')
        & mvn package -DskipTests 2>&1 |
            Tee-Object -FilePath (Join-Path $ResultsDir 'backend_build.log') | Out-Null
        Pop-Location
        $jar = Get-ChildItem (Join-Path $RepoRoot 'java-backend/target/*.jar') |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $jar) { throw 'No backend jar found after mvn package -DskipTests.' }
        $script:BackendJar = $jar.FullName
    }
    $env:VOXTICKET_AI_SELECTOR_STRATEGY = $Arm
    $env:SPRING_PROFILES_ACTIVE = 'dev'
    $proc = Start-Process -FilePath 'java' -ArgumentList @('-jar', $script:BackendJar) `
        -RedirectStandardOutput (Join-Path $ResultsDir "backend_$Arm.log") `
        -RedirectStandardError (Join-Path $ResultsDir "backend_$Arm.err.log") `
        -PassThru -NoNewWindow
    $deadline = (Get-Date).AddSeconds(150)
    while ((Get-Date) -lt $deadline) {
        if ($proc.HasExited) { throw "Backend for arm $Arm exited during startup. See backend_$Arm.log (Spring logs to stdout) and backend_$Arm.err.log (missing keys in this session, or the database, are the usual causes)." }
        if (Test-Backend) { return $proc }
        Start-Sleep -Seconds 5
    }
    throw "Backend for arm $Arm did not become healthy within 150s."
}
function Stop-BackendArm {
    param($Proc)
    if ($Proc -and -not $Proc.HasExited) {
        Stop-Process -Id $Proc.Id -Force -ErrorAction SilentlyContinue
    }
}

# ---------- 0. Run header ----------
$GitSha = ''
try { $GitSha = (git -C $RepoRoot rev-parse HEAD).Trim() } catch { $GitSha = 'unknown (git not available)' }
$JavaVersion = ''
try { $JavaVersion = ((java -version 2>&1) | Select-Object -First 1).Trim() } catch { $JavaVersion = 'not found' }
$Meta = [ordered]@{
    date               = (Get-Date -Format 'o')
    git_sha            = $GitSha
    java_version       = $JavaVersion
    model_path         = $ModelPath
    backend_url        = $BackendUrl
    rpm                = $Rpm
    embedding_provider = $(if ($EmbeddingProvider) { $EmbeddingProvider } else { 'NOT RECORDED - record before quoting E4 numbers' })
    price_note         = $(if ($PricePer1k -gt 0) { "Estimated costs use $PricePer1k USD per 1k tokens (word-count estimate, NOT metered). Verify this price against Groq's published pricing before quoting it in the thesis." } else { 'No price given: cost cells stay empty (not estimated).' })
}
$Meta | ConvertTo-Json | Set-Content (Join-Path $ResultsDir 'meta.json') -Encoding UTF8
Write-Host "Run: $Stamp  Commit: $GitSha  Results: $ResultsDir"

# ---------- 1. E1: test suites, parsed from XML ----------
Step 'E1-java' {
    Push-Location (Join-Path $RepoRoot 'java-backend')
    # LocalRunTest is excluded BY NAME with a reason: on the abandoned
    # 2026-11-01 tree it was an ungated @SpringBootTest needing a dev
    # database, so it errored on a plain gate run. Current main has no such
    # test, so the exclusion is a harmless no-op there — but it stays, so
    # re-adding an ungated variant can never silently break the gate again.
    # It is not silently dropped: this exclusion is stated in the report.
    & mvn clean test "-Dtest=!RagEvaluationHarnessLocalRunTest" 2>&1 |
        Tee-Object -FilePath (Join-Path $ResultsDir 'e1_mvn.log') | Out-Null
    Pop-Location
}
Step 'E1-python' {
    $VoiceDir = Join-Path $RepoRoot 'python-voice'
    if (Test-Path $VoiceDir) {
        Push-Location $VoiceDir
        # Quote the WHOLE --junitxml argument: the unquoted --junitxml=(...)
        # form made pytest treat the XML path as a test path ("file or
        # directory not found: ...\pytest.xml", no tests ran), while a
        # plain `python -m pytest -q` in the same folder passes 100 tests.
        & python -m pytest -q "--junitxml=$(Join-Path $ResultsDir 'pytest.xml')" 2>&1 |
            Tee-Object -FilePath (Join-Path $ResultsDir 'e1_pytest.log') | Out-Null
        Pop-Location
        if (-not (Test-Path (Join-Path $ResultsDir 'pytest.xml'))) {
            Write-Host '  pytest produced no junit XML; see e1_pytest.log. E1-python will report not_run.'
        }
    } else { Write-Host "  python-voice directory not found, skipping pytest." }
}
Step 'E1-parse' {
    & python (Join-Path $ScriptsDir 'e1_parse.py') `
        --surefire-dir (Join-Path $RepoRoot 'java-backend/target/surefire-reports') `
        --pytest-xml (Join-Path $ResultsDir 'pytest.xml') `
        --out (Join-Path $ResultsDir 'e1.json')
}

# ---------- 2. E2: live probes (safe even when the backend is down) ----------
Step 'E2-probes' {
    # With -AutoBackend the probes get a backend too (HYBRID), so a run
    # with no backend already up no longer reports every probe BLOCKED.
    # Without it, an unreachable backend means all probes BLOCKED — the
    # prober records that honestly instead of inventing failures.
    $startedHere = $null
    if ((-not (Test-Backend)) -and $AutoBackend) {
        Write-Host '  No backend running: starting one (HYBRID) for the E2 probes...'
        $startedHere = Start-BackendArm 'HYBRID'
    }
    try {
        $probeArgs = @(
            (Join-Path $ScriptsDir 'e2_probe.py'), '--base-url', $BackendUrl,
            '--probes', (Join-Path $DatasetsDir 'e2_probes.json'),
            '--out-json', (Join-Path $ResultsDir 'e2.json'),
            '--out-csv', (Join-Path $ResultsDir 'e2_probes.csv'),
            '--rpm', "$Rpm"
        )
        if ($CustomerPhone) { $probeArgs += @('--customer-phone', $CustomerPhone) }
        & python @probeArgs 2>&1 | Tee-Object -FilePath (Join-Path $ResultsDir 'e2.log') | Out-Null
    } finally {
        Stop-BackendArm $startedHere
    }
}

# ---------- 3. E3: offline routing suite ----------
Step 'E3-suite' {
    if ([string]::IsNullOrWhiteSpace($ModelPath) -or -not (Test-Path (Join-Path $ModelPath 'model.onnx'))) {
        Write-Host '  ModelPath missing or without model.onnx: suite skipped (BLOCKED). Pass -ModelPath.'
    } else {
        Push-Location (Join-Path $RepoRoot 'java-backend')
        # Quote the WHOLE -D argument: PowerShell splits -Dname="value" at '='.
        & mvn test -Dtest=Phase3EvaluationSuite "-Dvoxticket.phase3.eval=true" "-Dvoxticket.phase3.git-sha=$GitSha" "-Dvoxticket.routing.model.path=$ModelPath" 2>&1 |
            Tee-Object -FilePath (Join-Path $ResultsDir 'e3_suite.log') | Out-Null
        Pop-Location
    }
    & python (Join-Path $ScriptsDir 'e3_parse_suite.py') `
        --log (Join-Path $ResultsDir 'e3_suite.log') `
        --out (Join-Path $ResultsDir 'e3_routing.json')
}

# ---------- 4. E3 live: one arm per backend start (declared, not verified) ----------
# -AutoBackend: build the jar once, then start it for the E2 probes
# (HYBRID) and per live arm with that arm's strategy, wait for health,
# run, and stop it again. Keys and DB settings pass through from this
# session's environment when set; nothing is stored in this script. A
# backend that is ALREADY running is always used instead and never
# stopped or restarted by this script.
if (-not $SkipLive) {
    Step 'E3-live' {
        $armFiles = @()
        foreach ($arm in ($LiveArms -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })) {
            $startedHere = $null
            if (-not (Test-Backend)) {
                if ($AutoBackend) {
                    Write-Host "  Starting backend for arm $arm (strategy VOXTICKET_AI_SELECTOR_STRATEGY=$arm)..."
                    $startedHere = Start-BackendArm $arm
                } else {
                    Write-Host "  Backend not reachable: arm $arm skipped (BLOCKED). Start the backend and re-run, use -AutoBackend, or use -SkipLive."
                    continue
                }
            } else {
                Write-Host "  Live arm: $arm -- using the already-running backend. Make sure it runs with VOXTICKET_AI_SELECTOR_STRATEGY=$arm (HYBRID is the default). The chat response carries no tier field, so the script records the DECLARED arm; confirm it from backend routing-decision logs." -ForegroundColor Yellow
            }
            try {
                $liveArgs = @(
                    (Join-Path $ScriptsDir 'e3_live.py'), '--base-url', $BackendUrl,
                    '--scenarios', (Join-Path $DatasetsDir 'e3_scenarios.json'),
                    '--arm', $arm, '--out', (Join-Path $ResultsDir "e3_live_$arm.json"),
                    '--rpm', "$Rpm"
                )
                if ($CustomerPhone) { $liveArgs += @('--customer-phone', $CustomerPhone) }
                if ($PricePer1k -gt 0) { $liveArgs += @('--price-per-1k', "$PricePer1k") }
                & python @liveArgs 2>&1 | Tee-Object -FilePath (Join-Path $ResultsDir "e3_live_$arm.log") | Out-Null
                $armFiles += (Join-Path $ResultsDir "e3_live_$arm.json")
            } finally {
                Stop-BackendArm $startedHere
            }
        }
        if ($armFiles.Count -gt 0) {
            & python (Join-Path $ScriptsDir 'e3_aggregate.py') --arm-files $armFiles --out (Join-Path $ResultsDir 'e3_comparison.json')
            Write-Host '  Human quality scores: fill the *_human_scores_template.csv files, then re-run the aggregator with all filled files as --human-scores (see README).'
        }
    }
}

# ---------- 5. E4: dataset check, harness, metrics ----------
Step 'E4-dataset' {
    & python (Join-Path $ScriptsDir 'e4_validate_dataset.py') `
        --dataset (Join-Path $DatasetsDir 'rag_questions_v2.json') `
        --out (Join-Path $ResultsDir 'e4_dataset_check.json')
}
Step 'E4-harness' {
    # The harness test ships in this zip at its repo path
    # (java-backend/src/test/java/com/voxticket/rag/). If it is missing,
    # the zip was not extracted at the repo root — re-extract it.
    $HarnessTest = Join-Path $RepoRoot 'java-backend/src/test/java/com/voxticket/rag/RagEvaluationHarnessManualTest.java'
    if (-not (Test-Path $HarnessTest)) {
        Write-Host '  RagEvaluationHarnessManualTest not present: harness skipped. This zip carries it at java-backend/src/test/java/com/voxticket/rag/ — extract the zip at the repo root to place it.'
        "Harness test not present in this tree (current main has none). Copy the optional file to enable this step." |
            Set-Content (Join-Path $ResultsDir 'e4_harness.log') -Encoding UTF8
    } else {
        Push-Location (Join-Path $RepoRoot 'java-backend')
        # Property-gated only (NO -Dgroups flag): the pom hard-codes
        # <excludedGroups>manual</excludedGroups> as a literal, and CLI
        # flags cannot override a literal — the old flag combo silently
        # ran 0 tests. The property gate alone keeps this test out of a
        # plain gate (JUnit disables it, reported as skipped). This test
        # needs a dev database, embeddings, and a populated vector store.
        # If it fails, E4 metrics stay 'not measured'.
        & mvn test -Dtest=RagEvaluationHarnessManualTest "-Dvoxticket.eval.rag=true" "-Dvoxticket.eval.rag.dataset=$(Join-Path $DatasetsDir 'rag_questions_v2.json')" "-Dvoxticket.eval.rag.output=$(Join-Path $ResultsDir 'retrieval-raw.json')" 2>&1 |
            Tee-Object -FilePath (Join-Path $ResultsDir 'e4_harness.log') | Out-Null
        Pop-Location
    }
    & python (Join-Path $ScriptsDir 'e4_metrics.py') `
        --retrieval (Join-Path $ResultsDir 'retrieval-raw.json') `
        --dataset (Join-Path $DatasetsDir 'rag_questions_v2.json') `
        --out-json (Join-Path $ResultsDir 'e4.json') `
        --out-md (Join-Path $ResultsDir 'e4_table.md')
}

# ---------- 6. E5: only from a filled template ----------
Step 'E5-voice' {
    # A filled template dropped next to this script is picked up
    # automatically, so -VoiceCsv is only needed for a custom location.
    if (-not $VoiceCsv) {
        $autoCsv = Join-Path $PackageDir 'voice_calls_filled.csv'
        if (Test-Path $autoCsv) { $VoiceCsv = $autoCsv; Write-Host "  Using filled voice template: $VoiceCsv" }
    }
    if ($VoiceCsv -and (Test-Path $VoiceCsv)) {
        & python (Join-Path $ScriptsDir 'e5_process.py') `
            --csv $VoiceCsv --out-json (Join-Path $ResultsDir 'e5.json') `
            --out-md (Join-Path $ResultsDir 'e5_table.md')
    } else {
        Copy-Item (Join-Path $TemplatesDir 'voice_calls_template.csv') (Join-Path $ResultsDir 'voice_calls_template.csv') -Force
        & python (Join-Path $ScriptsDir 'e5_process.py') `
            --csv (Join-Path $ResultsDir 'voice_calls_template.csv') `
            --out-json (Join-Path $ResultsDir 'e5.json') | Out-Null
        Write-Host '  No filled voice template given: E5 stays awaiting_user. Do the 10 manual calls, fill the template, and pass it with -VoiceCsv.'
    }
}

# ---------- 7. Reports ----------
Step 'Reports' {
    & python (Join-Path $ScriptsDir 'make_report.py') `
        --results-dir $ResultsDir --meta (Join-Path $ResultsDir 'meta.json') `
        --out-md (Join-Path $ReportsDir 'FINAL-EVALUATION-REPORT.md') `
        --out-ch7 (Join-Path $ReportsDir 'CHAPTER-7-TABLES.md')
}

$SeedChanged = @()
try {
    $SeedChanged = @(git -C $RepoRoot status --porcelain -- 'java-backend/src/main/java/com/voxticket/persistence/seed/SeedDatasetFactory.java' 'java-backend/src/test/java/com/voxticket/persistence/seed/SeedDatasetDeterminismTest.java')
} catch { $SeedChanged = @() }
if ($SeedChanged.Count -gt 0) {
    Write-Host '  Reminder: seed files are modified vs HEAD (the re-anchor this zip applies). Before voice calls: wipe + reseed the database, then verify ORD-10006 eligible / ORD-10007 expired / ORD-10003 cancellable. ORD-10006 stays eligible only through 2026-11-13 on this anchor.' -ForegroundColor Yellow
}
Write-Host ""
Write-Host "===== RUN SUMMARY =====" -ForegroundColor Green
foreach ($area in @('e1.json', 'e2.json', 'e3_routing.json', 'e3_comparison.json', 'e4.json', 'e5.json')) {
    $path = Join-Path $ResultsDir $area
    if (Test-Path $path) {
        try { $status = (Get-Content $path -Raw | ConvertFrom-Json).status } catch { $status = 'unreadable' }
        Write-Host "  $area : $status"
    } else { Write-Host "  $area : missing" }
}
Write-Host "Reports: $ReportsDir"
Write-Host "Results: $ResultsDir"
