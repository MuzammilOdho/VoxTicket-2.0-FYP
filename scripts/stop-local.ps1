# VoxTicket local stop (Windows PowerShell).
# Stops the Spring Boot dev process started by start-local.ps1 and the
# PostgreSQL container. Idempotent.

[CmdletBinding()]
param(
    [string]$ContainerName = "voxticket-postgres"
)

$ErrorActionPreference = "Continue"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $RepoRoot "logs\voxticket-dev.pid"

Write-Host "==> Stopping Spring Boot process" -ForegroundColor Cyan
if (Test-Path $pidFile) {
    $content = Get-Content $pidFile -Raw
    if ($content -match "PID=(\d+)") {
        $pid = [int]$Matches[1]
        try { Stop-Process -Id $pid -Force -ErrorAction Stop; Write-Host "    Stopped PID $pid" }
        catch { Write-Host "    PID $pid already gone." -ForegroundColor Yellow }
    }
    Remove-Item $pidFile -ErrorAction SilentlyContinue
} else { Write-Host "    No PID file; skipping." -ForegroundColor Yellow }

Write-Host "==> Stopping PostgreSQL container '$ContainerName'" -ForegroundColor Cyan
try {
    & docker stop $ContainerName 2>$null | Out-Null
    Write-Host "    Container stopped."
} catch { Write-Host "    Docker unavailable or container already stopped." -ForegroundColor Yellow }

Write-Host "DONE." -ForegroundColor Green
