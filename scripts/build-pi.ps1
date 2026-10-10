# Builds Pi as a single executable with every function compiled ahead of time: Windows' scripts/build-pi.sh.
#
# Usage: scripts\build-pi.ps1 [options]
#   -Pi DIR           a built Pi tree (npm ci --ignore-scripts; npm run build:offline, with the model catalog the repository has). Default: this repository, which is a fork of Pi
#   -Out DIR          where to put the executable and its assets. Default: out\pi-bolt
#   -Jit on|off       run with the JIT on (code that is not compiled ahead of time gets JIT-compiled) or off (the default)
#   -Cpu native|baseline
#                     native: code for this CPU's instruction set (AVX2 class); baseline: any x86-64 CPU Bun runs on
#   -Profile DIR      the training profile (bytecode order + regular expressions). Default: profiles\pi-<version>
#   -Plugins FILE     compile Pi extensions into the executable: FILE is a manifest module whose default export is the list of
#                     extension factories (see docs\PLUGINS.md and examples\plugins\plugins.ts). Its folder is built along with it.
#   -PluginWorker PATH[,PATH...]
#                     a worker script a plugin starts, relative to the manifest's folder (several: separated by commas)
#   -KeepBytecode     keep the bytecode in the prebuilt heap
#   -Stable           build with a stock Bun instead ($env:PIBOLT_STABLE_BUN, default `bun`): the comparison build, no AOT
#   -VerifyDeterminism
#                     build a second time with a copy of the runtime (another file, so ASLR loads it at another address) and
#                     check that the prebuilt heaps are the same byte for byte: a pointer the executable's writer did not relocate,
#                     or a table hashed by address, would differ (scripts\lib\compare-static-heaps.py)
#   -FunctionCellsOut FILE
#                     write where each function's executables are in the prebuilt heap, by name (for scripts\train-heap.ps1)
# Environment: PIBOLT_BUN (the Pi-Bolt runtime; default .work\runtime\bun.exe); PIBOLT_BUILD_LOG (a file for what the compiler
#              prints)
param(
	[string]$Pi = '',
	[string]$Out = '',
	[ValidateSet('on', 'off')][string]$Jit = 'off',
	[ValidateSet('native', 'baseline')][string]$Cpu = 'native',
	[string]$Profile = '',
	[string]$Plugins = '',
	[string[]]$PluginWorker = @(),
	[switch]$KeepBytecode,
	[switch]$Stable,
	[switch]$VerifyDeterminism,
	[string]$FunctionCellsOut = ''
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandArgumentPassing = 'Legacy' # (PowerShell 7 as Windows PowerShell: see Build-Pi)
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Work = if ($env:PIBOLT_WORK) { $env:PIBOLT_WORK } else { Join-Path $Root '.work' }
function Log($message) { Write-Host "==> $message" -ForegroundColor Cyan }
function Die($message) { Write-Host "error: $message" -ForegroundColor Red; Remove-PluginStage; exit 1 }
# The plugins' staging folder in the Pi tree (Stage-Plugins) is removed however the script ends, as build-pi.sh's trap does: by
# Die, after the build, and by the trap below on an error.
$Stage = ''; $Staged = $false
function Remove-PluginStage {
	if (-not $script:Staged) { return }
	$script:Staged = $false
	try { Remove-Item -LiteralPath $script:Stage -Recurse -Force -ErrorAction Stop }
	catch { Write-Host "warning: could not remove $script:Stage ($($_.Exception.Message))" -ForegroundColor Yellow }
}
trap { Remove-PluginStage; break }

if (-not $Pi) { $Pi = if ($env:PIBOLT_PI) { $env:PIBOLT_PI } else { $Root } }
$Pi = (Resolve-Path $Pi).Path
$Agent = Join-Path $Pi 'packages\coding-agent'
if (-not (Test-Path (Join-Path $Agent 'dist\bun\cli.js'))) { Die "$Agent\dist\bun\cli.js not found: build Pi first (npm ci --ignore-scripts; npm run build:offline)" }
$Version = (Get-Content (Join-Path $Agent 'package.json') -Raw | ConvertFrom-Json).version
# The executable is made from Pi's built output (each package's dist), not from its sources: a source changed since its package
# was built would be left out.
foreach ($package in Get-ChildItem (Split-Path $Agent) -Directory) {
	$src = Join-Path $package.FullName 'src'; $dist = Join-Path $package.FullName 'dist'
	if (-not (Test-Path $src) -or -not (Test-Path $dist)) { continue }
	$built = Get-ChildItem $dist -Filter *.js -File | Select-Object -First 1
	if (-not $built) { continue }
	$stale = Get-ChildItem $src -Recurse -Filter *.ts -File | Where-Object { $_.Name -notlike '*.d.ts' -and $_.LastWriteTime -gt $built.LastWriteTime } | Select-Object -First 1
	if ($stale) { Die "Pi's sources changed since it was built ($($stale.FullName)): build it again (npm run build:offline in $Pi)" }
}
$PiboltVersion = (Get-Content (Join-Path $Root 'VERSION') -Raw).Trim()
$Platform = 'win32-x64'
$CpuVariant = 'x64'; if ($Cpu -eq 'baseline') { $CpuVariant = 'x64-baseline' }; if ($Jit -eq 'on') { $CpuVariant = 'x64-jit' }
if (-not $Out) { $Out = Join-Path $Root 'out\pi-bolt' }
$Out = [System.IO.Path]::GetFullPath($Out)
if (-not $Profile) { $Profile = Join-Path $Root "profiles\pi-$Version" }

# Stage-Plugins MANIFEST: copies the manifest's folder into the Pi tree and writes the entry point that starts Pi with those
# extensions (scripts/lib/common.sh's stage_plugins). Inside the Pi tree, the packages Pi provides to extensions
# (@earendil-works/pi-*, typebox) resolve to Pi's own modules, as they do when Pi loads an extension at run time; a copy of one
# inside the plugin would be a second Pi (duplicate classes and registries), so those are left out, and so is .git. Returns the
# entry, relative to the agent dir.
function Stage-Plugins($manifest) {
	$script:Stage = Join-Path $Agent '.pibolt-plugins'
	$src = Split-Path $manifest
	if (Test-Path -LiteralPath $Stage) { Remove-Item -LiteralPath $Stage -Recurse -Force }
	$script:Staged = $true
	New-Item -ItemType Directory -Force (Join-Path $Stage 'src') | Out-Null
	# (robocopy, which copies the long paths node_modules has. rsync's patterns match at any depth: here they are the folders they
	# match, by full path. The staging folder too, for a manifest whose folder holds the Pi tree.)
	$exclude = @($Stage)
	foreach ($modules in @(Get-ChildItem -LiteralPath $src -Recurse -Directory -Force -Filter node_modules)) {
		$scope = Join-Path $modules.FullName '@earendil-works'
		if (Test-Path -LiteralPath $scope) { $exclude += @(Get-ChildItem -LiteralPath $scope -Force -Filter 'pi-*' | ForEach-Object { $_.FullName }) }
		if (Test-Path -LiteralPath (Join-Path $modules.FullName 'typebox')) { $exclude += Join-Path $modules.FullName 'typebox' }
	}
	& robocopy $src (Join-Path $Stage 'src') /E /R:0 /W:0 /NFL /NDL /NJH /NJS /NP /XD .git @exclude /XF .git | Out-Null
	if ($LASTEXITCODE -ge 8) { Die "-Plugins: could not copy $src to $Stage\src (robocopy exit $LASTEXITCODE)" }
	$lines = @(
		"// Generated by Pi-Bolt: Pi's Bun entry (packages/coding-agent/src/bun/cli.ts), with extensions compiled in."
		'import "../dist/bun/sandbox-env-setup.js";'
		'// (pi-bolt --version answered before the rest of Pi, and the plugins, are loaded: as Pi''s own entry does.)'
		'import "../dist/bun/version-fast-path.js";'
		'import "../dist/bun/runtime-setup.js";'
		'import { setupCli } from "../dist/cli/setup.js";'
		'import { main } from "../dist/main.js";'
		"import plugins from `"./src/$(Split-Path $manifest -Leaf)`";"
		''
		'// A named plugin is a built-in extension (`builtin:<name>`): it can be turned off (`-builtin:<name>` in the `extensions` setting,'
		'// or the config screen), and an extension of the same tools loaded at run time replaces it (replaceable) instead of conflicting.'
		'// The manifest decides where it says (builtin or replaceable set); an unnamed factory stays an inline extension.'
		'const extensionFactories = plugins.map((plugin) =>'
		'	typeof plugin === "function" || plugin.builtin !== undefined'
		'		? plugin'
		'		: { ...plugin, builtin: true, replaceable: plugin.replaceable ?? true },'
		');'
		''
		'setupCli();'
		'main(process.argv.slice(2), { extensionFactories });'
	)
	[IO.File]::WriteAllText((Join-Path $Stage 'entry.ts'), ($lines -join "`n") + "`n", (New-Object Text.UTF8Encoding $false))
	return './.pibolt-plugins/entry.ts'
}

$Entry = './dist/bun/cli.js'
# (-PluginWorker a,b through powershell -File arrives as one string.)
$PluginWorker = @($PluginWorker | ForEach-Object { $_ -split ',' } | Where-Object { $_ })
if ($Plugins) {
	if (-not (Test-Path -LiteralPath $Plugins -PathType Leaf)) { Die "-Plugins: $Plugins not found" }
	$Plugins = (Resolve-Path -LiteralPath $Plugins).Path
}
foreach ($worker in $PluginWorker) {
	if (-not $Plugins) { Die '-PluginWorker needs -Plugins' }
	if (-not (Test-Path -LiteralPath (Join-Path (Split-Path $Plugins) $worker) -PathType Leaf)) { Die "-PluginWorker: $(Join-Path (Split-Path $Plugins) $worker) not found" }
}
if ($Plugins) { $Entry = Stage-Plugins $Plugins }
$Entries = @($Entry, './src/utils/image-resize-worker.ts')
if (Test-Path (Join-Path $Agent 'src\extensions\codemode\worker.ts')) { $Entries += './src/extensions/codemode/worker.ts' }
# Workers a plugin starts (new Worker(new URL("./worker.ts", import.meta.url))) are entry points of their own, by the path a
# compiled-in plugin starts them with (docs\PLUGINS.md).
foreach ($worker in $PluginWorker) { $Entries += "./.pibolt-plugins/src/$($worker -replace '\\', '/')" }

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
	} finally { Pop-Location; Remove-PluginStage }
	Stage-Assets $Out
	Log "done: $Out\pi.exe"
	exit 0
}

$Bun = if ($env:PIBOLT_BUN) { $env:PIBOLT_BUN } else { Join-Path $Work 'runtime\bun.exe' }
if (-not (Test-Path $Bun)) { Die "no Pi-Bolt runtime at ${Bun}: build it (scripts\build-runtime.ps1), or set PIBOLT_BUN" }
# Which runtime this is, for pi-bolt.txt (and from it the benchmarks' environment.txt), so that two builds made with different
# runtimes say so: its version, hash, whether it has Control Flow Guard (the PE header's GUARD_CF), and the build of it in
# .work\bun\build it is a copy of, with that build's LTO setting. A build of the runtime newer than this one is warned about.
function Get-RuntimeStamp($exe) {
	$stream = [IO.File]::OpenRead($exe)
	try { $header = New-Object byte[] 4096; [void]$stream.Read($header, 0, $header.Length) } finally { $stream.Dispose() }
	$pe = [BitConverter]::ToInt32($header, 0x3C)
	$cfg = if (([BitConverter]::ToUInt16($header, $pe + 24 + 70) -band 0x4000) -ne 0) { 'CFG' } else { 'no CFG' }
	$file = Get-Item -LiteralPath $exe
	$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $exe).Hash.ToLower()
	$from = 'not a build in .work\bun\build'
	foreach ($dir in @(Get-ChildItem (Join-Path $Work 'bun\build') -Directory -ErrorAction SilentlyContinue)) {
		$built = Get-Item -LiteralPath (Join-Path $dir.FullName 'bun.exe') -ErrorAction SilentlyContinue
		if (-not $built) { continue }
		if ($built.Length -eq $file.Length -and (Get-FileHash -Algorithm SHA256 -LiteralPath $built.FullName).Hash.ToLower() -eq $hash) {
			$lto = try { if ((Get-Content (Join-Path $dir.FullName 'configure.json') -Raw | ConvertFrom-Json).overrides.lto) { 'LTO' } else { 'no LTO' } } catch { 'LTO unknown' }
			$from = "build/$($dir.Name), $lto"
		} elseif ($built.LastWriteTime -gt $file.LastWriteTime) {
			Write-Host "warning: $($built.FullName) is newer than the runtime used, $exe (set PIBOLT_BUN, or install it with scripts\build-runtime.ps1)" -ForegroundColor Yellow
		}
	}
	$ErrorActionPreference = 'Continue'
	$revision = (& $exe --revision 2>$null | Select-Object -First 1)
	"runtime: Bun $revision, $cfg, $from, $($file.Length) bytes, sha256 $($hash.Substring(0, 16))"
}
$RuntimeStamp = Get-RuntimeStamp $Bun
Log "$RuntimeStamp ($Bun)"
$OrderArgs = @(); $Regexps = ''
if (Test-Path (Join-Path $Profile 'bytecode.order')) {
	$OrderArgs = @("--bytecode-order=$(Join-Path $Profile 'bytecode.order')")
	if (Test-Path (Join-Path $Profile 'regexps.txt')) { $Regexps = Join-Path $Profile 'regexps.txt' }
} else {
	Write-Host "warning: no training profile at ${Profile}: building without one" -ForegroundColor Yellow
}
Log "Pi $Version, ahead of time: JIT $Jit, CPU $Cpu, $(if ($KeepBytecode) { 'bytecode kept' } else { 'bytecode left out' })$(if ($Plugins) { ", plugins from $Plugins" }) -> $Out"
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
# The functions whose executables Pi's runs touch (scripts\train-heap.ps1): made side by side in the prebuilt heap.
if (Test-Path (Join-Path $Profile 'heap-functions.txt')) { $vars.BUN_STATIC_HEAP_FUNCTIONS_FIRST = Join-Path $Profile 'heap-functions.txt' }
if ($FunctionCellsOut) { $vars.BUN_STATIC_HEAP_FUNCTION_CELLS_OUT = [System.IO.Path]::GetFullPath($FunctionCellsOut) }
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
	# (The writer's notes and warnings about the prebuilt heap are shown wherever they are in the log: a release build's log keeps
	# them.)
	Get-Content $log | Where-Object { $_ -match '^(note|warning): ' }
	Get-Content $log | Where-Object { $_ -notmatch '^AOT: ' -and $_ -notmatch '^(note|warning): ' } | Select-Object -Last 3
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
	Remove-PluginStage
}
if ($VerifyDeterminism) {
	$python = if (Get-Command py -ErrorAction SilentlyContinue) { 'py' } else { 'python' }
	& $python (Join-Path $Root 'scripts\lib\compare-static-heaps.py') (Join-Path $Out 'pi.exe') (Join-Path $verify 'pi.exe')
	# (Exit code 3, apart from other failures: the release workflow builds again for this, and only for this. docs/RESUME.md says
	# what differs when it does.)
	if ($LASTEXITCODE -ne 0) { Write-Host 'error: the prebuilt heap depends on where the runtime was loaded (see above)' -ForegroundColor Red; exit 3 }
	Log 'the prebuilt heap is the same from both builds'
}
Stage-Assets $Out
$about = @("Pi-Bolt $PiboltVersion (Pi $Version), win32-$CpuVariant, JIT $Jit, built $((Get-Date).ToUniversalTime().ToString('yyyy-MM-dd'))", $RuntimeStamp)
# The packages compiled in, by the manifest folder's package.json (`name version`, comma-separated): the installer does not offer
# to install from npm what the executable has already.
if ($Plugins) {
	$packageJson = Join-Path (Split-Path $Plugins) 'package.json'
	$compiledIn = if (Test-Path -LiteralPath $packageJson) {
		$dependencies = (Get-Content -Raw -LiteralPath $packageJson | ConvertFrom-Json).dependencies
		@($dependencies.PSObject.Properties | ForEach-Object { "$($_.Name) $($_.Value)" }) -join ', '
	} else { Split-Path $Plugins -Leaf }
	$about += "plugins: $compiledIn"
}
$about | Set-Content -Encoding ascii (Join-Path $Out 'pi-bolt.txt')

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
