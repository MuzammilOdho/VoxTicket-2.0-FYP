# VoxTicket local application start (Windows PowerShell).
#
# Starts the PostgreSQL container, verifies required environment variables
# (presence only - values are never printed), launches Spring Boot with the
# `dev` profile (enables ChatController, DataSeeder, dev OTP delivery), and
# waits for the HTTP server to become ready.
#
# Required env vars (set before running; never commit them):
#   GEMINI_API_KEY, GROQ_API_KEY, CEREBRAS_API_KEY, ROUTING_MODEL_PATH
# Optional: DB_USERNAME, DB_PASSWORD, DB_URL
#
# Usage:
#   $env:GEMINI_API_KEY="..."; $env:GROQ_API_KEY="..."; $env:CEREBRAS_API_KEY="..."
#   $env:ROUTING_MODEL_PATH="C:\path\to\e5"
#   .\scripts\start-local.ps1 [-Port 8080]

[CmdletBinding()]
param(
    [int]$Port = 8080,
    [string]$ContainerName = "voxticket-postgres",
    [string]$LogFile = ""
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($LogFile)) { $LogFile = Join-Path $RepoRoot "logs\voxticket-dev.log" }

function Fail([string]$msg) { Write-Error $msg; exit 1 }

Write-Host "==> [1/4] Starting infrastructure" -ForegroundColor Cyan
try { & docker info 2>&1 | Out-Null } catch { Fail "Docker is not running. Start Docker Desktop first." }
$st = & docker ps --filter "name=^$ContainerName$" --format "{{.Names}}" 2>$null
if ($st -notmatch [regex]::Escape($ContainerName)) {
    Write-Host "    Container '$ContainerName' not running; starting it."
    & docker start $ContainerName | Out-Null
    Start-Sleep -Seconds 5
} else { Write-Host "    OK: container '$ContainerName' running" }

Write-Host "==> [2/4] Verifying required environment variables (presence only)" -ForegroundColor Cyan
$missing = @()
foreach ($v in @("GEMINI_API_KEY", "GROQ_API_KEY", "CEREBRAS_API_KEY", "ROUTING_MODEL_PATH")) {
    $val = [Environment]::GetEnvironmentVariable($v)
    if ([string]::IsNullOrWhiteSpace($val)) { $missing += $v } else { Write-Host "    OK: $v is set" }
}
if ($missing.Count -gt 0) { Fail "Missing required env vars: $($missing -join ', '). Set them and retry." }
if (-not (Test-Path (Join-Path $env:ROUTING_MODEL_PATH "model.onnx"))) { Fail "ROUTING_MODEL_PATH does not contain model.onnx." }

Write-Host "==> [3/4] Launching Spring Boot (dev profile)" -ForegroundColor Cyan
$logDir = Split-Path -Parent $LogFile
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }
$wrapper = Join-Path $RepoRoot "mvnw.cmd"
if (-not (Test-Path $wrapper)) { Fail "mvnw.cmd not found in repo root." }
Push-Location $RepoRoot
try {
    $proc = Start-Process -FilePath $wrapper `
        -ArgumentList "spring-boot:run", "-Dspring-boot.run.profiles=dev" `
        -RedirectStandardOutput $LogFile -RedirectStandardError ($LogFile + ".err") `
        -WorkingDirectory $RepoRoot -PassThru -WindowStyle Hidden
    "PID=$($proc.Id) started=$(Get-Date -Format o)" | Out-File (Join-Path $logDir "voxticket-dev.pid") -Encoding utf8
    Write-Host "    Launched (PID $($proc.Id)); logs: $LogFile"
} finally { Pop-Location }

Write-Host "==> [4/4] Waiting for application readiness" -ForegroundColor Cyan
$deadline = (Get-Date).AddMinutes(6)
$ready = $false
while ((Get-Date) -lt $deadline) {
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$Port/actuator/health" -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop
        if ($r.StatusCode -eq 200) { $ready = $true; break }
    } catch { Start-Sleep -Seconds 5 }
}
if (-not $ready) {
    Write-Host "    App not ready after 6 minutes. Check logs:" -ForegroundColor Yellow
    Write-Host "      $LogFile"
    Fail "Application failed to become ready."
}
Write-Host ""
Write-Host "VOXTICKET RUNNING at http://localhost:$Port (dev profile)" -ForegroundColor Green
Write-Host "  Chat API: POST http://localhost:$Port/api/v1/chat  {sessionId, message, customerPhone}"
Write-Host "  Logs: $LogFile"
Write-Host "  Stop with: .\scripts\stop-local.ps1"
