# Builds Pi as a single executable with every function compiled ahead of time: Windows' scripts/build-pi.sh.
#
# Usage: scripts\build-pi.ps1 [options]
#   -Pi DIR           a built Pi tree (npm ci --ignore-scripts; npm run build). Default: this repository, which is a fork of Pi
#   -Out DIR          where to put the executable and its assets. Default: out\pi-bolt
#   -Jit on|off       run with the JIT on (code that is not compiled ahead of time gets JIT-compiled) or off (the default)
#   -Cpu native|baseline
#                     native: code for this CPU's instruction set (AVX2 class); baseline: any x86-64 CPU Bun runs on
#   -Profile DIR      the training profile (bytecode order + regular expressions). Default: profiles\pi-<version>
#   -KeepBytecode     keep the bytecode in the prebuilt heap
#   -Stable           build with a stock Bun instead ($env:PIBOLT_STABLE_BUN, default `bun`): the comparison build, no AOT
#   -VerifyDeterminism
#                     build a second time with a copy of the runtime (another file, so ASLR loads it at another address) and
#                     check that the prebuilt heaps are the same byte for byte: a pointer the executable's writer did not relocate,
#                     or a table hashed by address, would differ (scripts\lib\compare-static-heaps.py)
# Environment: PIBOLT_BUN (the Pi-Bolt runtime; default .work\runtime\bun.exe); PIBOLT_BUILD_LOG (a file for what the compiler
#              prints)
param(
	[string]$Pi = '',
	[string]$Out = '',
	[ValidateSet('on', 'off')][string]$Jit = 'off',
	[ValidateSet('native', 'baseline')][string]$Cpu = 'native',
	[string]$Profile = '',
	[switch]$KeepBytecode,
	[switch]$Stable,
	[switch]$VerifyDeterminism
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandArgumentPassing = 'Legacy' # (PowerShell 7 as Windows PowerShell: see Build-Pi)
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Work = if ($env:PIBOLT_WORK) { $env:PIBOLT_WORK } else { Join-Path $Root '.work' }
function Log($message) { Write-Host "==> $message" -ForegroundColor Cyan }
function Die($message) { Write-Host "error: $message" -ForegroundColor Red; exit 1 }

if (-not $Pi) { $Pi = if ($env:PIBOLT_PI) { $env:PIBOLT_PI } else { $Root } }
$Pi = (Resolve-Path $Pi).Path
$Agent = Join-Path $Pi 'packages\coding-agent'
if (-not (Test-Path (Join-Path $Agent 'dist\bun\cli.js'))) { Die "$Agent\dist\bun\cli.js not found: build Pi first (npm ci --ignore-scripts; npm run build)" }
$Version = (Get-Content (Join-Path $Agent 'package.json') -Raw | ConvertFrom-Json).version
# The executable is made from Pi's built output (each package's dist), not from its sources: a source changed since its package
# was built would be left out.
foreach ($package in Get-ChildItem (Split-Path $Agent) -Directory) {
	$src = Join-Path $package.FullName 'src'; $dist = Join-Path $package.FullName 'dist'
	if (-not (Test-Path $src) -or -not (Test-Path $dist)) { continue }
	$built = Get-ChildItem $dist -Filter *.js -File | Select-Object -First 1
	if (-not $built) { continue }
	$stale = Get-ChildItem $src -Recurse -Filter *.ts -File | Where-Object { $_.Name -notlike '*.d.ts' -and $_.LastWriteTime -gt $built.LastWriteTime } | Select-Object -First 1
	if ($stale) { Die "Pi's sources changed since it was built ($($stale.FullName)): build it again (npm run build in $Pi)" }
}
$PiboltVersion = (Get-Content (Join-Path $Root 'VERSION') -Raw).Trim()
$Platform = 'win32-x64'
$CpuVariant = 'x64'; if ($Cpu -eq 'baseline') { $CpuVariant = 'x64-baseline' }; if ($Jit -eq 'on') { $CpuVariant = 'x64-jit' }
if (-not $Out) { $Out = Join-Path $Root 'out\pi-bolt' }
$Out = [System.IO.Path]::GetFullPath($Out)
if (-not $Profile) { $Profile = Join-Path $Root "profiles\pi-$Version" }
$Entries = @('./dist/bun/cli.js', './src/utils/image-resize-worker.ts')
if (Test-Path (Join-Path $Agent 'src\extensions\codemode\worker.ts')) { $Entries += './src/extensions/codemode/worker.ts' }

function Stage-Assets($dir) {
	Copy-Item (Join-Path $Agent 'package.json'), (Join-Path $Agent 'README.md'), (Join-Path $Agent 'CHANGELOG.md') $dir
	New-Item -ItemType Directory -Force "$dir\theme", "$dir\assets", "$dir\export-html\vendor", "$dir\native\win32\prebuilds" | Out-Null
	Copy-Item "$Agent\src\modes\interactive\theme\*.json" "$dir\theme"
	Copy-Item "$Agent\src\modes\interactive\assets\*" "$dir\assets"
	Copy-Item "$Agent\src\core\export-html\template.html" "$dir\export-html"
	if (Test-Path "$Agent\src\core\export-html\template.css") { Copy-Item "$Agent\src\core\export-html\template.css", "$Agent\src\core\export-html\template.js" "$dir\export-html" }
	Copy-Item "$Agent\src\core\export-html\vendor\*.js" "$dir\export-html\vendor" -ErrorAction SilentlyContinue
	Copy-Item "$Pi\node_modules\@silvia-odwyer\photon-node\photon_rs_bg.wasm" $dir
	Copy-Item -Recurse "$Pi\packages\tui\native\win32\prebuilds\$Platform" "$dir\native\win32\prebuilds"
}

# (The previous build is moved aside, not deleted: the folder may hold a running executable.)
if (Test-Path $Out) {
	$old = "$Out.old-$(Get-Date -Format yyyyMMddHHmmss)"
	Move-Item $Out $old
	Log "the previous build is in $old"
}
New-Item -ItemType Directory -Force $Out | Out-Null
$CommonArgs = @('build', '--compile', '--no-compile-autoload-bunfig', '--no-compile-autoload-dotenv', "--target=bun-windows-x64", '--bytecode', '--format=esm')

if ($Stable) {
	$Bun = if ($env:PIBOLT_STABLE_BUN) { $env:PIBOLT_STABLE_BUN } else { 'bun' }
	Log "Pi $Version with stock Bun $(& $Bun --version) (bytecode, no AOT) -> $Out"
	Push-Location $Agent
	try {
		& $Bun @CommonArgs @Entries --outfile (Join-Path $Out 'pi.exe') | Out-Null
		if ($LASTEXITCODE -ne 0) { Die 'bun build failed' }
	} finally { Pop-Location }
	Stage-Assets $Out
	Log "done: $Out\pi.exe"
	exit 0
}

$Bun = if ($env:PIBOLT_BUN) { $env:PIBOLT_BUN } else { Join-Path $Work 'runtime\bun.exe' }
if (-not (Test-Path $Bun)) { Die "no Pi-Bolt runtime at ${Bun}: build it (scripts\build-runtime.ps1), or set PIBOLT_BUN" }
$OrderArgs = @(); $Regexps = ''
if (Test-Path (Join-Path $Profile 'bytecode.order')) {
	$OrderArgs = @("--bytecode-order=$(Join-Path $Profile 'bytecode.order')")
	if (Test-Path (Join-Path $Profile 'regexps.txt')) { $Regexps = Join-Path $Profile 'regexps.txt' }
} else {
	Write-Host "warning: no training profile at ${Profile}: building without one" -ForegroundColor Yellow
}
Log "Pi $Version, ahead of time: JIT $Jit, CPU $Cpu, $(if ($KeepBytecode) { 'bytecode kept' } else { 'bytecode left out' }) -> $Out"
$saved = @{}
$vars = @{
	BUN_JSC_useJIT = '0'; BUN_STATIC_HEAP = '1'; BUN_AOT = '1'
	BUN_JSC_useAOTLoopSplitting = $(if ($env:BUN_JSC_useAOTLoopSplitting) { $env:BUN_JSC_useAOTLoopSplitting } else { '1' })
	BUN_JSC_aotLoopSplittingPolicy = $(if ($env:BUN_JSC_aotLoopSplittingPolicy) { $env:BUN_JSC_aotLoopSplittingPolicy } else { '5' })
	BUN_JSC_useImmutableIntrinsics = $(if ($env:BUN_JSC_useImmutableIntrinsics) { $env:BUN_JSC_useImmutableIntrinsics } else { '1' })
}
if ($Jit -eq 'off') { $vars.BUN_AOT_JIT = '0' }
if ($Cpu -eq 'baseline') { $vars.BUN_AOT_CPU = 'baseline' }
if (-not $KeepBytecode) { $vars.BUN_JSC_omitBytecodeFromStaticHeap = '1' }
if ($Regexps) { $vars.BUN_JSC_aotRegExpsPath = $Regexps }
foreach ($k in $vars.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $vars[$k]) }
function Build-Pi($runtime, $outfile, $log) {
	# A JSON string. (Windows PowerShell passes a native command's argument's double quotes as they are, which the program's runtime
	# then takes away, and splits an argument with quotes at its spaces: the quotes are written \" and the spaces as JSON escapes.)
	$build = "$PiboltVersion $CpuVariant jit-$Jit" -replace ' ', ('\' + 'u0020')
	# (What the compiler prints on stderr is its progress: Windows PowerShell would make each line of it an error, and stop.)
	$ErrorActionPreference = 'Continue'
	& $runtime @CommonArgs @OrderArgs --define "PIBOLT_BUILD=\`"$build\`"" '--compile-exec-argv=--smol' @Entries --outfile $outfile *> $log
	$status = $LASTEXITCODE
	$ErrorActionPreference = 'Stop'
	Get-Content $log | Where-Object { $_ -notmatch '^AOT: ' } | Select-Object -Last 3
	if ($status -ne 0) { Die "bun build failed (see $log)" }
}
Push-Location $Agent
try {
	$log = if ($env:PIBOLT_BUILD_LOG) { $env:PIBOLT_BUILD_LOG } else { Join-Path $Work 'build-pi.log' }
	# Twice: the first build says how big each part of the prebuilt heap is, and the second makes them that big, side by side, so
	# that the executable has no room between them (which would be charged to the system's commit while it is cached; docs/WINDOWS.md).
	$sizes = Join-Path $Work 'static-region-sizes.txt'
	Remove-Item $sizes -ErrorAction SilentlyContinue
	$saved.BUN_STATIC_REGION_SIZES_OUT = [Environment]::GetEnvironmentVariable('BUN_STATIC_REGION_SIZES_OUT')
	$saved.BUN_STATIC_REGION_SIZES = [Environment]::GetEnvironmentVariable('BUN_STATIC_REGION_SIZES')
	[Environment]::SetEnvironmentVariable('BUN_STATIC_REGION_SIZES', $null)
	[Environment]::SetEnvironmentVariable('BUN_STATIC_REGION_SIZES_OUT', $sizes)
	Log 'first build: how big the prebuilt heap is'
	Build-Pi $Bun (Join-Path $Work 'pi-first-build.exe') "$log.first"
	[Environment]::SetEnvironmentVariable('BUN_STATIC_REGION_SIZES_OUT', $null)
	if (-not (Test-Path $sizes)) { Die 'the first build did not say how big its prebuilt heap is' }
	[Environment]::SetEnvironmentVariable('BUN_STATIC_REGION_SIZES', (Get-Content $sizes -Raw).Trim())
	Log "second build, with the heap's parts that big ($((Get-Content $sizes -Raw).Trim()))"
	Build-Pi $Bun (Join-Path $Out 'pi.exe') $log
	if ($VerifyDeterminism) {
		$verify = Join-Path $Work "verify-$(Get-Date -Format yyyyMMddHHmmss)"
		New-Item -ItemType Directory -Force $verify | Out-Null
		Copy-Item $Bun (Join-Path $verify 'bun-copy.exe')
		Log "building again with a copy of the runtime, to compare ($verify)"
		Build-Pi (Join-Path $verify 'bun-copy.exe') (Join-Path $verify 'pi.exe') (Join-Path $verify 'build-pi.log')
	}
} finally {
	Pop-Location
	foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) }
}
if ($VerifyDeterminism) {
	$python = if (Get-Command py -ErrorAction SilentlyContinue) { 'py' } else { 'python' }
	& $python (Join-Path $Root 'scripts\lib\compare-static-heaps.py') (Join-Path $Out 'pi.exe') (Join-Path $verify 'pi.exe')
	if ($LASTEXITCODE -ne 0) { Die "the prebuilt heap depends on where the runtime was loaded (see above)" }
	Log 'the prebuilt heap is the same from both builds'
}
Stage-Assets $Out
"Pi-Bolt $PiboltVersion (Pi $Version), win32-$CpuVariant, JIT $Jit, built $((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd'))" | Set-Content -Encoding ascii (Join-Path $Out 'pi-bolt.txt')

$env:BUN_STATIC_HEAP_VERBOSE = '1'
$ErrorActionPreference = 'Continue' # (stderr is where it says it)
$check = (& (Join-Path $Out 'pi.exe') --version 2>&1 | Out-String)
$ErrorActionPreference = 'Stop'
[Environment]::SetEnvironmentVariable('BUN_STATIC_HEAP_VERBOSE', $null)
if ($check -notmatch 'image registered: true') { Die "the executable does not use its compiled code:`n$check" }
# The image's size, and what of it is uninitialized: that is charged to the system's commit while Windows has the executable cached
# (docs/WINDOWS.md). .pbreg is the 32 MB of the region that has to be there; a .pbgap is room between the arenas, which should be small.
$bytes = [IO.File]::ReadAllBytes((Join-Path $Out 'pi.exe'))
$pe = [BitConverter]::ToInt32($bytes, 0x3c)
$count = [BitConverter]::ToUInt16($bytes, $pe + 6)
$table = $pe + 24 + [BitConverter]::ToUInt16($bytes, $pe + 20)
$sizeOfImage = [BitConverter]::ToUInt32($bytes, $pe + 24 + 56)
$uninitialized = @{}
$heapSections = 0
for ($i = 0; $i -lt $count; $i++) {
	$at = $table + 40 * $i
	$name = [Text.Encoding]::ASCII.GetString($bytes, $at, 8).TrimEnd([char]0)
	$virtualSize = [BitConverter]::ToUInt32($bytes, $at + 8)
	$characteristics = [BitConverter]::ToUInt32($bytes, $at + 36)
	if ($characteristics -band 0x80) { $uninitialized[$name] = $uninitialized[$name] + $virtualSize }
	if ($name -in '.pbheap', '.pbimage', '.pbcode') { $heapSections += $virtualSize }
}
$gaps = [double]$uninitialized['.pbgap']
Log ("image {0:N1} MB, of it the static heap and code {1:N1} MB; uninitialized: {2}" -f ($sizeOfImage / 1MB), ($heapSections / 1MB), (($uninitialized.GetEnumerator() | Sort-Object Name | ForEach-Object { '{0} {1:N1} MB' -f $_.Name, ($_.Value / 1MB) }) -join ', '))
if ($gaps -gt 8MB) { Die ("{0:N1} MB of room between the static heap's arenas (.pbgap), which is charged to the system's commit: the arenas were not where the build expected them (see its log)" -f ($gaps / 1MB)) }
Log "done: $Out\pi.exe ($([math]::Round((Get-Item (Join-Path $Out 'pi.exe')).Length / 1MB)) MB, Pi $(($check.Trim() -split "`n")[-1]))"
