# Builds the Pi-Bolt runtime on Windows: Bun on the patched WebKit (JavaScriptCore with the ahead-of-time compiler), release
# flags. Windows' scripts/build-runtime.sh.
# Usage: scripts\build-runtime.ps1 [-Lto on|off] [-Jobs N] [-BuildDir DIR]
#   -Lto off    no link-time optimization: a faster build that needs less memory, for working on the engine
#   -Jobs N     jobs at once (default: Bun's build, one per core; on a machine with 16 GB of RAM, 8 is safer)
#   -BuildDir DIR  Bun's build directory, relative to .work\bun (default: build/pibolt-release, or build/pibolt-release-nolto);
#               a directory built before is built again incrementally
# Needs: the sources (scripts\fetch-sources.ps1), Visual Studio 2022 or its Build Tools with the C++ workload, the Windows SDK and
# "C++ Clang tools for Windows" (ICU's build), LLVM 23.1, CMake, Rust (cargo), Bun 1.4.2, Go, NASM, Ruby, Python 3 with the `py`
# launcher, and Git for Windows (its perl). Run it from a shell with Visual Studio's environment loaded (Launch-VsDevShell.ps1).
param(
	[ValidateSet('on', 'off')][string]$Lto = 'on',
	[int]$Jobs = 0,
	[string]$BuildDir = ''
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Work = if ($env:PIBOLT_WORK) { $env:PIBOLT_WORK } else { Join-Path $Root '.work' }
function Log($message) { Write-Host "==> $message" -ForegroundColor Cyan }
function Die($message) { Write-Host "error: $message" -ForegroundColor Red; exit 1 }
foreach ($tool in 'bun', 'cmake', 'cargo', 'clang-cl') { if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { Die "'$tool' is required but not installed" } }
if (-not $env:VSINSTALLDIR) { Die "load Visual Studio's environment first (Launch-VsDevShell.ps1)" }
$WebKit = Join-Path $Work 'webkit'; $BunSrc = Join-Path $Work 'bun'
if (-not (Test-Path (Join-Path $WebKit '.git')) -or -not (Test-Path (Join-Path $BunSrc '.git'))) { Die 'sources missing: run scripts\fetch-sources.ps1 first' }

if (-not $BuildDir) { $BuildDir = if ($Lto -eq 'off') { 'build/pibolt-release-nolto' } else { 'build/pibolt-release' } }
$buildArgs = @('scripts/build.ts', '--profile=release-local', "--lto=$Lto", "--build-dir=$BuildDir")
if ($Jobs) { $buildArgs += "-j$Jobs" }
Log "building the runtime in $BunSrc\$BuildDir (WebKit from $WebKit)"
$env:BUN_WEBKIT_PATH = $WebKit
# (MSBuild's worker processes, from ICU's build, would otherwise outlive it.)
$env:MSBUILDDISABLENODEREUSE = '1'
function Build-Runtime {
	Push-Location $BunSrc
	try {
		& bun @buildArgs
		if ($LASTEXITCODE -ne 0) { Die 'the build failed' }
	} finally { Pop-Location }
}
Build-Runtime
# Control Flow Guard checks an indirect call to a SysV-convention function with its target in the wrong register (LLVM on x64):
# each must go through WTF::callSysV(). An LTO build's objects are bitcode, where such a call can be found (docs\WINDOWS.md).
if ($Lto -eq 'on') {
	Log 'checking for indirect SysV calls that Control Flow Guard would check wrongly'
	& py -3 (Join-Path $Root 'scripts\lib\check-cfg-sysv-calls.py') (Join-Path $BunSrc $BuildDir)
	if ($LASTEXITCODE -ne 0) { Die 'an indirect call to a SysV function does not go through callSysV() (see above)' }
}
# The functions that start Pi, and those it runs most, laid out together at the front of the code (a linker order file), so that
# Pi touches fewer of its pages: what Pi runs, traced in sessions of it (profiles\runtime-win32-x64.hints, from
# scripts\train-runtime-hints.ps1), then what Bun's own workloads run. The order is made with the build just done, and the runtime
# linked again with it when it changed. (As build-runtime.sh does on macOS.)
$Hints = Join-Path $Root 'profiles\runtime-win32-x64.hints'
if (Test-Path $Hints) {
	$Order = Join-Path $BunSrc "$BuildDir\linker.order"
	$before = if (Test-Path $Order) { (Get-FileHash $Order).Hash } else { '' }
	Log "a linker order file from $Hints"
	Push-Location $BunSrc
	try {
		& bun scripts/orderfile/generate.ts "--build-dir=$BuildDir" "--hints=$Hints" | Where-Object { $_ -match '^ *hints:|^wrote ' }
		if ($LASTEXITCODE -ne 0) { Die 'making the order file failed' }
	} finally { Pop-Location }
	if ((Get-FileHash $Order).Hash -ne $before) {
		Log 'linking the runtime again with its order file'
		Build-Runtime
	}
}

$Runtime = Join-Path $Work 'runtime'
New-Item -ItemType Directory -Force $Runtime | Out-Null
Copy-Item (Join-Path $BunSrc "$BuildDir\bun.exe") (Join-Path $Runtime 'bun.exe') -Force
foreach ($extra in 'bun-profile.exe', 'bun-profile.pdb', 'bun.pdb') {
	$from = Join-Path $BunSrc "$BuildDir\$extra"
	if (Test-Path $from) { Copy-Item $from (Join-Path $Runtime $extra) -Force }
}
Log "runtime: $Runtime\bun.exe ($(& (Join-Path $Runtime 'bun.exe') --version))"
