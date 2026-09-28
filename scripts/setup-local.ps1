# VoxTicket local environment setup (Windows PowerShell).
#
# Verifies prerequisites, starts PostgreSQL + pgvector via Docker, waits for the
# database, validates Flyway/migrations prerequisites, routing-model artifacts,
# RAG prerequisites, and builds the project. Idempotent: safe to re-run.
#
# Usage:
#   .\scripts\setup-local.ps1 [-DbUser <user>] [-DbName <name>] [-ModelDir <path>]
#
# Provider API keys are NOT required for setup. They are only needed later for
# the live evaluation (see start-local.ps1 / run-live-evaluation.ps1).

[CmdletBinding()]
param(
    [string]$DbUser = $env:DB_USERNAME,
    [string]$DbPassword = $env:DB_PASSWORD,
    [string]$DbName = "voxticket2.0",
    [string]$ModelDir = $env:ROUTING_MODEL_PATH,
    [string]$ContainerName = "voxticket-postgres",
    [string]$PostgresImage = "pgvector/pgvector:pg16"
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot

function Fail([string]$msg) { Write-Error $msg; exit 1 }

Write-Host "==> [1/7] Verifying Java 21+" -ForegroundColor Cyan
try { $javaOut = & java -version 2>&1 | Out-String } catch { Fail "Java not found on PATH. Install Temurin/OpenJDK 21+ and retry." }
if ($javaOut -notmatch '"21\.') { Fail "Java 21+ required. Found: $($javaOut.Trim().Split("`n")[0])" }
Write-Host "    OK: $($javaOut.Trim().Split([Environment]::NewLine)[0])"

Write-Host "==> [2/7] Verifying Maven" -ForegroundColor Cyan
$haveMvn = $false
try { & mvn -version 2>&1 | Out-Null; $haveMvn = $true } catch { $haveMvn = $false }
if (-not $haveMvn) {
    $wrapper = Join-Path $RepoRoot "mvnw.cmd"
    if (-not (Test-Path $wrapper)) { Fail "Neither 'mvn' nor mvnw.cmd found." }
    Write-Host "    OK: using mvnw.cmd wrapper"
    $script:Mvn = $wrapper
} else {
    Write-Host "    OK: mvn on PATH"
    $script:Mvn = "mvn"
}

Write-Host "==> [3/7] Verifying Docker" -ForegroundColor Cyan
try { & docker info 2>&1 | Out-Null } catch { Fail "Docker is not running. Start Docker Desktop and retry." }
Write-Host "    OK: Docker daemon reachable"

Write-Host "==> [4/7] Starting PostgreSQL + pgvector" -ForegroundColor Cyan
if ([string]::IsNullOrWhiteSpace($DbUser)) { $DbUser = "muzammil" }
if ([string]::IsNullOrWhiteSpace($DbPassword)) {
    Write-Host "    DB_PASSWORD not set; using the application default from application.yaml." -ForegroundColor Yellow
}
$existing = & docker ps -a --filter "name=^$ContainerName$" --format "{{.Names}} {{.Status}}" 2>$null
if ($existing -match [regex]::Escape($ContainerName)) {
    Write-Host "    Container '$ContainerName' exists; starting it."
    & docker start $ContainerName | Out-Null
} else {
    Write-Host "    Creating container '$ContainerName' ($PostgresImage)."
    $runArgs = @("run", "-d", "--name", $ContainerName,
        "-e", "POSTGRES_USER=$DbUser",
        "-e", "POSTGRES_DB=$DbName",
        "-p", "5432:5432",
        $PostgresImage)
    if (-not [string]::IsNullOrWhiteSpace($DbPassword)) { $runArgs = @("run","-d","--name",$ContainerName,"-e","POSTGRES_USER=$DbUser","-e","POSTGRES_PASSWORD=$DbPassword","-e","POSTGRES_DB=$DbName","-p","5432:5432",$PostgresImage) }
    & docker @runArgs | Out-Null
}

Write-Host "    Waiting for PostgreSQL to accept connections..."
$deadline = (Get-Date).AddMinutes(3)
$ready = $false
while ((Get-Date) -lt $deadline) {
    $r = & docker exec $ContainerName pg_isready -U $DbUser -d $DbName 2>&1 | Out-String
    if ($r -match "accepting connections") { $ready = $true; break }
    Start-Sleep -Seconds 3
}
if (-not $ready) { Fail "PostgreSQL did not become ready within 3 minutes." }
Write-Host "    OK: PostgreSQL accepting connections (db='$DbName', user='$DbUser')"

$vec = & docker exec $ContainerName psql -U $DbUser -d $DbName -tAc "SELECT 1 FROM pg_available_extensions WHERE name='vector';" 2>$null
if ($vec -notmatch "1") { Fail "pgvector extension not available in image $PostgresImage." }
Write-Host "    OK: pgvector extension available"

Write-Host "==> [5/7] Validating routing model artifacts" -ForegroundColor Cyan
if ([string]::IsNullOrWhiteSpace($ModelDir)) { $ModelDir = Join-Path $RepoRoot "models\e5" }
foreach ($f in @("model.onnx", "tokenizer.json")) {
    $p = Join-Path $ModelDir $f
    if (-not (Test-Path $p)) { Fail "Missing routing artifact: $p. Download intfloat/multilingual-e5-small (onnx) once and set ROUTING_MODEL_PATH." }
}
Write-Host "    OK: model.onnx + tokenizer.json present in $ModelDir"

Write-Host "==> [6/7] Validating RAG prerequisites" -ForegroundColor Cyan
$knowledgeDir = Join-Path $RepoRoot "src\main\resources\knowledge"
$docs = Get-ChildItem $knowledgeDir -Filter "*.md" -ErrorAction SilentlyContinue
if (-not $docs -or $docs.Count -eq 0) { Fail "No knowledge documents in $knowledgeDir." }
Write-Host "    OK: $($docs.Count) policy documents found"
$migrations = Get-ChildItem (Join-Path $RepoRoot "src\main\resources\db\migration") -Filter "V*.sql"
Write-Host "    OK: $($migrations.Count) Flyway migrations present"

Write-Host "==> [7/7] Building project (offline-safe, skips tests)" -ForegroundColor Cyan
Push-Location $RepoRoot
try {
    & $script:Mvn -B -DskipTests package
    if ($LASTEXITCODE -ne 0) { Fail "Build failed." }
} finally { Pop-Location }
Write-Host "    OK: build succeeded"

Write-Host ""
Write-Host "SETUP COMPLETE. Next:" -ForegroundColor Green
Write-Host "  1. Set provider keys:  `$env:GEMINI_API_KEY, `$env:GROQ_API_KEY, `$env:CEREBRAS_API_KEY"
Write-Host "  2. Set routing model:  `$env:ROUTING_MODEL_PATH = '$ModelDir'"
Write-Host "  3. Run: .\scripts\start-local.ps1"
