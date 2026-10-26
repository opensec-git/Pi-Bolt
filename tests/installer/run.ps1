# Windows' tests/installer/run.sh: install.ps1's checks of what it downloads, against the latest release served from a local
# folder (PIBOLT_DOWNLOAD_BASE):
#   - the release as published installs, its signature verified;
#   - without SHA256SUMS.sig it is not installed (every Windows release is signed), with PIBOLT_ALLOW_UNSIGNED=1 neither: only
#     with PIBOLT_ALLOW_UNSIGNED set to the SHA-256 of that SHA256SUMS, which is then said in a warning;
#   - an archive replaced together with its checksum is not installed (the signature of SHA256SUMS does not match);
#   - an update keeps the build it replaces as <name>.previous (for pi-bolt update --rollback), and the same build installed
#     again does not replace the one kept;
#   - PIBOLT_CHANNEL=beta is kept for the installation's updates (<install>\channel), and stable takes it away.
# Each run installs into a folder of its own and leaves PATH as it is (PIBOLT_NO_PATH). Needs Python 3 and gh (or a
# GitHub token for gh).
#
# Usage: tests\installer\run.ps1
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$name = 'pi-bolt-win32-x64'
$work = Join-Path ([IO.Path]::GetTempPath()) "pibolt-installer-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$script:status = 0
function Write-Pass([string]$what) { Write-Host "PASS $what" }
function Write-Fail([string]$what, [string]$out = '') { Write-Host "FAIL $what"; if ($out) { Write-Host $out }; $script:status = 1 }

$good = Join-Path $work 'good'
New-Item -ItemType Directory -Force -Path $good | Out-Null
# (Tried three times: GitHub's API answers a download with an occasional HTTP 500.)
for ($attempt = 1; $attempt -le 3; $attempt++) {
	& gh release download --repo opensec-git/Pi-Bolt --dir $good --clobber --pattern SHA256SUMS --pattern SHA256SUMS.sig --pattern "$name.zip"
	if ($LASTEXITCODE -eq 0) { break }
	if ($attempt -eq 3) { exit 1 }
	Start-Sleep -Seconds 10
}
$nosig = Join-Path $work 'nosig'
Copy-Item -LiteralPath $good -Destination $nosig -Recurse
Remove-Item -LiteralPath (Join-Path $nosig 'SHA256SUMS.sig')
$tampered = Join-Path $work 'tampered'
Copy-Item -LiteralPath $good -Destination $tampered -Recurse
$zip = Join-Path $tampered "$name.zip"
Remove-Item -LiteralPath $zip
$x = Join-Path $work 'x'
[IO.File]::WriteAllText($x, "not Pi-Bolt`n")
Compress-Archive -LiteralPath $x -DestinationPath $zip
$sum = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
$sums = Join-Path $tampered 'SHA256SUMS'
$lines = @(Get-Content -LiteralPath $sums | Where-Object { $_ -notmatch " [ *]?$([regex]::Escape("$name.zip"))$" }) + "$sum  $name.zip"
[IO.File]::WriteAllText($sums, (($lines -join "`n") + "`n"))

# (install.ps1 takes a mirror's release by its version: PIBOLT_DOWNLOAD_BASE needs PIBOLT_VERSION.)
$version = (Get-Content -LiteralPath (Join-Path $good 'SHA256SUMS') -TotalCount 1) -replace '^# pi-bolt ', ''

$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()
# (py -3 where the launcher is installed, as the other Windows scripts run it; python otherwise.)
$python = if (Get-Command py -ErrorAction SilentlyContinue) { @('py', '-3') } else { @('python') }
$arguments = @($python | Select-Object -Skip 1) + '-m', 'http.server', $port, '--bind', '127.0.0.1', '--directory', "`"$work`""
$server = Start-Process -FilePath $python[0] -ArgumentList $arguments -WindowStyle Hidden -PassThru
for ($i = 0; $i -lt 50; $i++) {
	try { $null = Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$port/" -TimeoutSec 2; break } catch { Start-Sleep -Milliseconds 200 }
}

$inst = Join-Path $work 'inst'
$exe = Join-Path $inst "$name\pi-bolt.exe"
# Runs install.ps1 against $work\<case> into $inst, in a PowerShell of its own, with $vars besides; returns what it printed.
function Invoke-Installer([string]$case, [hashtable]$vars = @{}, [switch]$Again) {
	if (-not $Again) { Remove-Item -LiteralPath $inst -Recurse -Force -ErrorAction SilentlyContinue }
	$set = @{
		PIBOLT_DOWNLOAD_BASE = "http://127.0.0.1:$port/$case"; PIBOLT_INSTALL = $inst; PIBOLT_EXTENSIONS = 'no'; PIBOLT_NO_START = '1'
		PIBOLT_YES = '1'; PIBOLT_NO_PATH = '1'; PIBOLT_VERSION = "bolt-v$version"; PIBOLT_CHANNEL = $null
	}
	foreach ($k in $vars.Keys) { $set[$k] = $vars[$k] }
	$saved = @{}
	foreach ($k in $set.Keys) { $saved[$k] = [Environment]::GetEnvironmentVariable($k); [Environment]::SetEnvironmentVariable($k, $set[$k]) }
	try {
		(& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'install.ps1') 2>&1 | Out-String)
	} finally {
		foreach ($k in $saved.Keys) { [Environment]::SetEnvironmentVariable($k, $saved[$k]) }
	}
}
function Test-Installed { Test-Path -LiteralPath $exe }

try {
	$out = Invoke-Installer good
	if ((Test-Installed) -and $out -match 'signature verified') { Write-Pass 'the release installs, signature verified' }
	else { Write-Fail 'the release installs, signature verified' $out }

	$out = Invoke-Installer nosig
	if (-not (Test-Installed) -and $out -match 'the release has no signature') { Write-Pass 'no signature: not installed' }
	else { Write-Fail 'no signature: not installed' $out }

	# (An opt-in left in the environment: it is not one for this download.)
	$out = Invoke-Installer nosig @{ PIBOLT_ALLOW_UNSIGNED = '1' }
	if (-not (Test-Installed) -and $out -match 'the release has no signature') { Write-Pass 'no signature, PIBOLT_ALLOW_UNSIGNED=1: not installed' }
	else { Write-Fail 'no signature, PIBOLT_ALLOW_UNSIGNED=1: not installed' $out }
	$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $nosig 'SHA256SUMS')).Hash.ToLowerInvariant()
	$out = Invoke-Installer nosig @{ PIBOLT_ALLOW_UNSIGNED = $hash }
	if ((Test-Installed) -and $out -match 'WARNING:') { Write-Pass 'no signature, PIBOLT_ALLOW_UNSIGNED=<its SHA-256>: installed, with a warning' }
	else { Write-Fail 'no signature, PIBOLT_ALLOW_UNSIGNED=<its SHA-256>: installed, with a warning' $out }
	$out = Invoke-Installer tampered @{ PIBOLT_ALLOW_UNSIGNED = $hash }
	if (-not (Test-Installed) -and $out -match 'signature does not verify') { Write-Pass 'a signature that does not verify stops it, PIBOLT_ALLOW_UNSIGNED or not' }
	else { Write-Fail 'a signature that does not verify stops it, PIBOLT_ALLOW_UNSIGNED or not' $out }

	$out = Invoke-Installer tampered
	if (-not (Test-Installed) -and $out -match 'signature does not verify') { Write-Pass 'replaced archive and checksum: not installed' }
	else { Write-Fail 'replaced archive and checksum: not installed' $out }

	$previous = Join-Path $inst "$name.previous"
	$out = Invoke-Installer good
	if (-not (Test-Path -LiteralPath $previous)) { Write-Pass 'a first install keeps no previous build' }
	else { Write-Fail 'a first install keeps no previous build' $out }
	Add-Content -LiteralPath (Join-Path $inst "$name\pi-bolt.txt") -Value 'an older build'
	$out = Invoke-Installer good -Again
	if ((Test-Installed) -and (Select-String -LiteralPath (Join-Path $previous 'pi-bolt.txt') -Pattern 'an older build' -Quiet -ErrorAction SilentlyContinue) -and
		-not (Select-String -LiteralPath (Join-Path $inst "$name\pi-bolt.txt") -Pattern 'an older build' -Quiet) -and
		(Test-Path -LiteralPath (Join-Path $previous 'pi-bolt.exe'))) { Write-Pass 'an update keeps the build it replaces as .previous' }
	else { Write-Fail 'an update keeps the build it replaces as .previous' $out }
	$out = Invoke-Installer good -Again
	if (Select-String -LiteralPath (Join-Path $previous 'pi-bolt.txt') -Pattern 'an older build' -Quiet -ErrorAction SilentlyContinue) {
		Write-Pass 'the same build installed again leaves the previous one'
	} else { Write-Fail 'the same build installed again leaves the previous one' $out }

	$channel = Join-Path $inst 'channel'
	$out = Invoke-Installer good @{ PIBOLT_CHANNEL = 'beta' } -Again
	if ((Test-Path -LiteralPath $channel) -and (Get-Content -LiteralPath $channel -Raw).Trim() -eq 'beta') { Write-Pass 'PIBOLT_CHANNEL=beta is kept for updates' }
	else { Write-Fail 'PIBOLT_CHANNEL=beta is kept for updates' $out }
	$out = Invoke-Installer good @{ PIBOLT_CHANNEL = 'stable' } -Again
	if (-not (Test-Path -LiteralPath $channel)) { Write-Pass 'PIBOLT_CHANNEL=stable takes it away' } else { Write-Fail 'PIBOLT_CHANNEL=stable takes it away' $out }
	$out = Invoke-Installer good @{ PIBOLT_CHANNEL = 'nightly' } -Again
	if ($out -match 'PIBOLT_CHANNEL must be stable or beta') { Write-Pass 'another channel is refused' } else { Write-Fail 'another channel is refused' $out }
} finally {
	if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
	Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
exit $script:status
