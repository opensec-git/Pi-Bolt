# Builds and packages the Windows part of a release: Pi's executable, the runtime, and their SHA256SUMS, in dist\<version>\.
# Windows' scripts/package-release.sh.
#
# Usage: scripts\package-release.ps1 [-Pi DIR] [-NoBuild] [-WithoutOpenSec]
#   -Pi DIR     the built Pi tree (default: this repository)
#   -NoBuild    package the builds already in out\pi-bolt and out\pi-bolt-jit instead of building them
#   -WithoutOpenSec  build without OpenSec's extensions compiled in (plugins\opensec; by default they are, and can be turned off
#               with `-builtin:<name>` in the extensions setting)
# Archives (the names stay the same from release to release, so that releases/latest/download/<name> always works):
#   pi-bolt-win32-x64.zip           JIT off, code for AVX2-class CPUs (falls back to bytecode on others)
#   pi-bolt-win32-x64-jit.zip       JIT on, for extensions that do heavy JavaScript work at run time (docs/PLUGINS.md)
#   pi-bolt-runtime-win32-x64.zip   the Pi-Bolt Bun runtime, to build Pi with plugins (docs/PLUGINS.md)
# .zip, which install.ps1 unpacks with what Windows has. In the archive the executable is pi-bolt.exe: the installer puts its
# folder on PATH, and the command is then `pi-bolt` in cmd, PowerShell and any terminal, with no launcher in between.
# A release puts the archives of every platform together with one SHA256SUMS (docs/RELEASING.md). An Authenticode signature,
# with the owner's certificate, goes on pi-bolt.exe before the archive is made (it changes the file, and so its checksum).
param(
	[string]$Pi = '',
	[switch]$NoBuild,
	[switch]$WithoutOpenSec
)
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
function Log($message) { Write-Host "==> $message" -ForegroundColor Cyan }
function Die($message) { Write-Host "error: $message" -ForegroundColor Red; exit 1 }

$Version = (Get-Content (Join-Path $Root 'VERSION') -Raw).Trim()
# The npm launcher downloads the release of its own version: the two have to agree.
$NpmVersion = (Get-Content (Join-Path $Root 'npm\package.json') -Raw | ConvertFrom-Json).version
if ($NpmVersion -ne $Version) { Die "npm/package.json is $NpmVersion, VERSION is $Version" }
if (-not $Pi) { $Pi = if ($env:PIBOLT_PI) { $env:PIBOLT_PI } else { $Root } }
$Pi = (Resolve-Path $Pi).Path
$Out = Join-Path $Root 'out\pi-bolt'
$OutJit = Join-Path $Root 'out\pi-bolt-jit'
if (-not $NoBuild) {
	# OpenSec's extensions compiled in (plugins\opensec: the versions extensions.txt pins, by their lockfile's integrity), unless
	# -WithoutOpenSec. The profile is trained with them (scripts\train-heap.ps1 -Plugins plugins\opensec\plugins.ts).
	$withPlugins = @{}
	if (-not $WithoutOpenSec) {
		$opensec = Join-Path $Root 'plugins\opensec'
		Push-Location $opensec
		try {
			& npm ci --ignore-scripts --no-audit --no-fund
			if ($LASTEXITCODE -ne 0) { Die 'npm ci in plugins\opensec failed' }
			& node prepare.mjs
			if ($LASTEXITCODE -ne 0) { Die 'plugins\opensec\prepare.mjs failed' }
		} finally { Pop-Location }
		$withPlugins.Plugins = Join-Path $opensec 'plugins.ts'
	}
	& (Join-Path $Root 'scripts\build-pi.ps1') -Pi $Pi -Out $Out -VerifyDeterminism @withPlugins
	if ($LASTEXITCODE -ne 0) { Die 'the build failed' }
	& (Join-Path $Root 'scripts\build-pi.ps1') -Pi $Pi -Out $OutJit -Jit on -VerifyDeterminism @withPlugins
	if ($LASTEXITCODE -ne 0) { Die 'the JIT build failed' }
}
foreach ($dir in $Out, $OutJit) {
	if (-not (Test-Path (Join-Path $dir 'pi.exe'))) { Die "$dir\pi.exe not found: build it, or run without -NoBuild" }
}
$exe = Join-Path $Out 'pi.exe'
[Environment]::SetEnvironmentVariable('BUN_STATIC_HEAP_VERBOSE', '1')
# (What it says on stderr is what is looked for; Windows PowerShell makes each such line an error, which 'Stop' would end the
# script at.)
$ErrorActionPreference = 'Continue'
$check = (& $exe --version 2>&1 | Out-String)
$ErrorActionPreference = 'Stop'
[Environment]::SetEnvironmentVariable('BUN_STATIC_HEAP_VERBOSE', $null)
if ($check -notmatch 'image registered: true') { Die "$exe does not use its compiled code" }

$Dist = Join-Path $Root "dist\$Version"
if (Test-Path $Dist) {
	$old = "$Dist.old-$(Get-Date -Format yyyyMMddHHmmss)"
	Move-Item $Dist $old
	Log "the previous packages are in $old"
}
New-Item -ItemType Directory -Force $Dist | Out-Null
$Stage = Join-Path ([IO.Path]::GetTempPath()) "pi-bolt-package-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
New-Item -ItemType Directory -Force $Stage | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Add-Notices($dir) {
	Copy-Item (Join-Path $Root 'LICENSE') (Join-Path $dir 'LICENSE')
	Copy-Item (Join-Path $Root 'THIRD_PARTY_NOTICES.md') $dir
	if (Test-Path (Join-Path $Pi 'LICENSE')) { Copy-Item (Join-Path $Pi 'LICENSE') (Join-Path $dir 'LICENSE.pi') }
}
function New-Zip($dir, $zip) {
	[IO.Compression.ZipFile]::CreateFromDirectory($dir, $zip, [IO.Compression.CompressionLevel]::Optimal, $true)
}
try {
	foreach ($build in @(@{ Name = 'pi-bolt-win32-x64'; Dir = $Out }, @{ Name = 'pi-bolt-win32-x64-jit'; Dir = $OutJit })) {
		$name = $build.Name
		$dir = Join-Path $Stage $name
		Copy-Item -Recurse $build.Dir $dir
		Move-Item (Join-Path $dir 'pi.exe') (Join-Path $dir 'pi-bolt.exe')
		Add-Notices $dir
		Log "$name.zip (Pi $(& (Join-Path $dir 'pi-bolt.exe') --version))"
		New-Zip $dir (Join-Path $Dist "$name.zip")
	}

	$runtime = if ($env:PIBOLT_BUN) { $env:PIBOLT_BUN } else { Join-Path $Root '.work\runtime\bun.exe' }
	$dir = Join-Path $Stage 'pi-bolt-runtime-win32-x64'
	New-Item -ItemType Directory -Force $dir | Out-Null
	Copy-Item $runtime (Join-Path $dir 'bun.exe')
	Add-Notices $dir
	Log "pi-bolt-runtime-win32-x64.zip (Bun $(& $runtime --version))"
	New-Zip $dir (Join-Path $Dist 'pi-bolt-runtime-win32-x64.zip')
} finally {
	Remove-Item -Recurse -Force $Stage -ErrorAction SilentlyContinue
}

# The extensions the installers offer, pinned (extensions.txt), with LF line ends as everywhere else.
[IO.File]::WriteAllText((Join-Path $Dist 'extensions.txt'), ((Get-Content (Join-Path $Root 'extensions.txt')) -join "`n") + "`n")

# sha256sum's format (two spaces, then the name), with LF line ends, after a first line that says which release it is (the
# installers refuse checksums of another version: an older release, signed all the same, served as a newer one). The release's
# SHA256SUMS is made over every platform's archives together, and signed on the owner's machine (docs/RELEASING.md).
$lines = @("# pi-bolt $Version") + (Get-ChildItem $Dist -File | Where-Object { $_.Name -like '*.zip' -or $_.Name -eq 'extensions.txt' } | Sort-Object Name | ForEach-Object { "$((Get-FileHash -Algorithm SHA256 $_.FullName).Hash.ToLowerInvariant())  $($_.Name)" })
[IO.File]::WriteAllText((Join-Path $Dist 'SHA256SUMS'), (($lines -join "`n") + "`n"))
Log "release $Version (Windows) in ${Dist}:"
Get-ChildItem $Dist | ForEach-Object { '    {0,8:N1} MB  {1}' -f ($_.Length / 1MB), $_.Name }
