# Records which of the runtime's functions Pi enters, in the order it first enters them, from sessions of a Pi-Bolt build
# (bench\orderfile_session.py: Pi started, a headless prompt, a TUI session, against the local fake model). scripts\build-runtime.ps1
# lays those functions out first in the runtime's code (a linker order file), so that Pi touches fewer of its pages. The list is of
# names: it holds from one build of the runtime to the next, and is made again when what Pi runs has changed much. Windows'
# scripts/train-runtime-hints.sh.
# Usage: scripts\train-runtime-hints.ps1 [-Pi BUILD] [-BuildDir DIR]
#   -Pi BUILD       a Pi-Bolt build made with that runtime (default out\pi-bolt)
#   -BuildDir DIR   the runtime's build, in .work\bun (default build/pibolt-release)
param(
	[string]$Pi = '',
	[string]$BuildDir = 'build/pibolt-release'
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Work = if ($env:PIBOLT_WORK) { $env:PIBOLT_WORK } else { Join-Path $Root '.work' }
function Log($message) { Write-Host "==> $message" -ForegroundColor Cyan }
function Die($message) { Write-Host "error: $message" -ForegroundColor Red; exit 1 }
foreach ($tool in 'bun', 'py') { if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { Die "'$tool' is required but not installed" } }
if (-not $Pi) { $Pi = Join-Path $Root 'out\pi-bolt' }
$Pi = [System.IO.Path]::GetFullPath($Pi)
$BunSrc = Join-Path $Work 'bun'
$Profile = Join-Path $BunSrc "$BuildDir\bun-profile.exe"
if (-not (Test-Path $Profile)) { Die "no $Profile`: run scripts\build-runtime.ps1" }
$Exe = Join-Path $Pi 'pi.exe'
if (-not (Test-Path $Exe)) { Die "no Pi-Bolt build at $Pi`: run scripts\build-pi.ps1" }
$Out = Join-Path $Root 'profiles\runtime-win32-x64.hints'
$Session = Join-Path $Root 'bench\orderfile_session.py'
# Python's own executable, for hints.ts to start: `py` may be a batch file, which a spawn would run through cmd.exe.
$Python = (& py -3 -c 'import sys; print(sys.executable)').Trim()
if (-not (Test-Path $Python)) { Die 'cannot find Python 3 (the py launcher)' }
Log "tracing sessions of $Exe -> $Out"
# (hints.ts checks that the executable has the profile's code: the same runtime, or the traced addresses would mean nothing.)
Push-Location $BunSrc
try {
	& bun scripts/orderfile/hints.ts "--build-dir=$BuildDir" "--exe=$Exe" "--out=$Out" -- $Python $Session '{}'
	if ($LASTEXITCODE -ne 0) { Die 'tracing failed' }
} finally { Pop-Location }
