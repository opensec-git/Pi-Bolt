# Puts the strings a Pi-Bolt build touches as it runs (Pi started, a headless prompt, a TUI session, against the local fake model)
# first in the training profile's order file, in the order it first touches them: the build lays out each string's record, atom and
# JSString in that order, so that Pi starts on fewer pages of its prebuilt heap (scripts\lib\train_heap_strings.py says how the runs
# are traced). The profile is the Pi version's, shared by every platform; this runs on Windows, after scripts/train-profile.sh has
# made the profile, on a build made with it. Then build Pi again.
# Usage: scripts\train-heap-strings.ps1 [-Pi BUILD] [-Profile DIR]
#   -Pi BUILD       a Pi-Bolt build of the profile's Pi version (default out\pi-bolt)
#   -Profile DIR    the training profile whose bytecode.order is rewritten (default: the one build-pi.ps1 uses, profiles\pi-<version>)
param(
	[string]$Pi = '',
	[string]$Profile = ''
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
function Log($message) { Write-Host "==> $message" -ForegroundColor Cyan }
function Die($message) { Write-Host "error: $message" -ForegroundColor Red; exit 1 }
if (-not (Get-Command py -ErrorAction SilentlyContinue)) { Die "'py' is required but not installed" }
if (-not $Pi) { $Pi = Join-Path $Root 'out\pi-bolt' }
$Pi = [System.IO.Path]::GetFullPath($Pi)
$Exe = Join-Path $Pi 'pi.exe'
if (-not (Test-Path $Exe)) { Die "no Pi-Bolt build at $Pi`: run scripts\build-pi.ps1" }
if (-not $Profile) {
	$version = (Get-Content -Raw (Join-Path $Root 'packages\coding-agent\package.json') | ConvertFrom-Json).version
	$Profile = Join-Path $Root "profiles\pi-$version"
}
$Order = Join-Path ([System.IO.Path]::GetFullPath($Profile)) 'bytecode.order'
if (-not (Test-Path $Order)) { Die "no $Order`: run scripts/train-profile.sh" }
Log "tracing runs of $Exe -> $Order"
& py -3 (Join-Path $Root 'scripts\lib\train_heap_strings.py') $Exe $Order
if ($LASTEXITCODE -ne 0) { Die 'tracing failed' }
Log "done: build Pi again (scripts\build-pi.ps1) to lay its strings out by it"
