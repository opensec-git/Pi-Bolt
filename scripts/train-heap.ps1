# Records what of Pi's prebuilt heap Pi's runs touch (Pi started, a headless prompt, a TUI session, against the local fake model),
# for the build to lay it out side by side: the strings, first in the training profile's order file in the order the runs first
# touch them; the functions whose executables they touch, in the profile's heap-functions.txt. So Pi starts on fewer pages of its
# heap (scripts\lib\train_heap.py says how the runs are traced). The profile is the Pi version's, shared by every platform; this
# runs on Windows, after scripts/train-profile.sh has made the profile. It builds Pi to trace (with the profile as it is, saying
# where each function's executables are), then rewrites the profile: build Pi again afterwards.
# Usage: scripts\train-heap.ps1 [-Profile DIR] [-Plugins FILE]
#   -Profile DIR    the training profile (default: the one build-pi.ps1 uses, profiles\pi-<version>)
#   -Plugins FILE   train with these extensions compiled in (build-pi.ps1 -Plugins), as the release is built: the functions are
#                   named by their module's place in the build, which the plugins' modules change
param(
	[string]$Profile = '',
	[string]$Plugins = ''
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Work = if ($env:PIBOLT_WORK) { $env:PIBOLT_WORK } else { Join-Path $Root '.work' }
function Log($message) { Write-Host "==> $message" -ForegroundColor Cyan }
function Die($message) { Write-Host "error: $message" -ForegroundColor Red; exit 1 }
if (-not (Get-Command py -ErrorAction SilentlyContinue)) { Die "'py' is required but not installed" }
if (-not $Profile) {
	$version = (Get-Content -Raw (Join-Path $Root 'packages\coding-agent\package.json') | ConvertFrom-Json).version
	$Profile = Join-Path $Root "profiles\pi-$version"
}
$Profile = [System.IO.Path]::GetFullPath($Profile)
if (-not (Test-Path (Join-Path $Profile 'bytecode.order'))) { Die "no $Profile\bytecode.order: run scripts/train-profile.sh" }
$Build = Join-Path $Work 'heap-training'
$Cells = Join-Path $Work 'heap-training-function-cells.txt'
Log "a Pi-Bolt build to trace -> $Build"
$withPlugins = if ($Plugins) { @{ Plugins = [System.IO.Path]::GetFullPath($Plugins) } } else { @{} }
& (Join-Path $Root 'scripts\build-pi.ps1') -Out $Build -Profile $Profile -FunctionCellsOut $Cells @withPlugins
if ($LASTEXITCODE -ne 0) { Die 'the build failed' }
Log "tracing runs of $Build\pi.exe -> $Profile"
& py -3 (Join-Path $Root 'scripts\lib\train_heap.py') (Join-Path $Build 'pi.exe') $Cells $Profile
if ($LASTEXITCODE -ne 0) { Die 'tracing failed' }
Log 'done: build Pi again (scripts\build-pi.ps1) to lay its heap out by the profile'
