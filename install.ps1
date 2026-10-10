# Pi-Bolt installer for Windows (x64).
#
#   powershell -c "[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor 3072; irm https://pi-bolt.opensec.in/install.ps1 | iex"
#
# Downloads a release, verifies its SHA-256 checksum and the Ed25519 signature of the checksums, installs it to
# %USERPROFILE%\.pi-bolt and puts `pi-bolt` on your PATH (it asks first). Run it again to reinstall, update or uninstall. The
# executable comes from the npm registry (a CDN, fast in most places) and falls back to GitHub; the checksums and their signature
# always come from the GitHub release, so a download from npm is checked against them. Nothing here needs administrator rights,
# and nothing changes a security setting: the installation is the user's own folder and the user's own PATH.
#
# Environment:
#   PIBOLT_VERSION   a release tag such as bolt-v0.8.0 (default: the latest release)
#   PIBOLT_VARIANT   x64 (default), x64-baseline (any x86-64 CPU; picked when the CPU has no AVX2 and the release has one) or
#                    x64-jit
#   PIBOLT_INSTALL   where to install (default: %USERPROFILE%\.pi-bolt)
#   PIBOLT_NO_PATH=1 do not add the installation to PATH
#   PIBOLT_YES=1     do not ask: take the default action and do not offer to start Pi-Bolt
#   PIBOLT_LAUNCHER=1  used by the npm package's first run: download only, no menu, PATH or prompts
#   PIBOLT_SOURCE    where to download the executable from: auto (npm, then GitHub; the default), npm or github
#   PIBOLT_NPM_REGISTRY  the npm registry or mirror to use (default: https://registry.npmjs.org)
#   PIBOLT_EXTENSIONS  yes or no: whether to install OpenSec's optional extensions (opensec-pi-subagents, opensec-pi-todo)
#                    without asking. Without it the installer asks when it can; a run that cannot ask, or PIBOLT_YES=1,
#                    installs none.
#   PIBOLT_DOWNLOAD_BASE  a mirror of a release to download everything from (its SHA256SUMS.sig too)
#   PIBOLT_ALLOW_UNSIGNED=1  install a release whose checksums have no signature (a build of your own); never needed otherwise
#
# A folder given in PIBOLT_INSTALL outside your profile is made yours, SYSTEM's and the Administrators' only.

# The public key that releases are signed with (keys/release.pub in the repository; scripts/sign-release.sh).
$PiBoltReleaseKey = 'MCowBQYDK2VwAyEAoLboJqtKaoISPqffk03vHZr+1sRBG3uIRIWeKOew+aY='
$PiBoltRepo = 'https://github.com/opensec-git/Pi-Bolt'

# --- Ed25519 ----------------------------------------------------------------------------------------------------------
# Signature verification (RFC 8032, section 5.1.7) with System.Numerics.BigInteger: Windows has no Ed25519 of its own (neither
# .NET Framework nor .NET has one, and there is no openssl), and the signature is what says that a download is Pi-Bolt's. About
# a second, once. (PowerShell's names are not case-sensitive, and a comma binds tighter than %: hence some of the names and
# parentheses below.)

function New-Ed25519 {
	$big = [System.Numerics.BigInteger]
	$p = $big::Pow(2, 255) - 19
	$L = $big::Pow(2, 252) + $big::Parse('27742317777372353535851937790883648493')
	$d = (($p - 121665) * $big::ModPow(121666, $p - 2, $p)) % $p
	$curve = @{ p = $p; L = $L; d = $d; sqrtM1 = $big::ModPow(2, ($p - 1) / 4, $p) }
	$gy = (4 * $big::ModPow(5, $p - 2, $p)) % $p
	$gx = Get-Ed25519X $curve $gy 0
	$curve.G = @($gx, $gy, $big::One, (($gx * $gy) % $p))
	$curve
}

function Get-Ed25519Mod($curve, $value) { $r = $value % $curve.p; if ($r.Sign -lt 0) { $r + $curve.p } else { $r } }

# x from y and the sign of x, or $null if there is none.
function Get-Ed25519X($curve, $y, [int]$sign) {
	$p = $curve.p
	if ($y -ge $p) { return $null }
	$x2 = Get-Ed25519Mod $curve (($y * $y - 1) * [System.Numerics.BigInteger]::ModPow((Get-Ed25519Mod $curve ($curve.d * $y * $y + 1)), $p - 2, $p))
	if ($x2.IsZero) { if ($sign) { return $null } else { return [System.Numerics.BigInteger]::Zero } }
	$x = [System.Numerics.BigInteger]::ModPow($x2, ($p + 3) / 8, $p)
	if (-not (Get-Ed25519Mod $curve ($x * $x - $x2)).IsZero) { $x = ($x * $curve.sqrtM1) % $p }
	if (-not (Get-Ed25519Mod $curve ($x * $x - $x2)).IsZero) { return $null }
	if ([int]($x % 2) -ne $sign) { $x = $p - $x }
	$x
}

function ConvertFrom-Ed25519Bytes([byte[]]$bytes) { [System.Numerics.BigInteger]::new([byte[]]($bytes + [byte]0)) } # little-endian, unsigned

function Get-Ed25519Point($curve, [byte[]]$bytes) {
	if ($bytes.Length -ne 32) { return $null }
	$copy = [byte[]]$bytes.Clone()
	$sign = $copy[31] -shr 7
	$copy[31] = $copy[31] -band 0x7f
	$y = ConvertFrom-Ed25519Bytes $copy
	$x = Get-Ed25519X $curve $y $sign
	if ($null -eq $x) { return $null }
	@($x, $y, [System.Numerics.BigInteger]::One, (($x * $y) % $curve.p))
}

function Add-Ed25519Point($curve, $one, $two) {
	$m = $curve.p
	$a1 = (($one[1] - $one[0]) * ($two[1] - $two[0])) % $m
	$b1 = (($one[1] + $one[0]) * ($two[1] + $two[0])) % $m
	$c1 = (2 * $one[3] * $two[3] * $curve.d) % $m
	$d1 = (2 * $one[2] * $two[2]) % $m
	$e1 = $b1 - $a1; $f1 = $d1 - $c1; $g1 = $d1 + $c1; $h1 = $b1 + $a1
	@((($e1 * $f1) % $m), (($g1 * $h1) % $m), (($f1 * $g1) % $m), (($e1 * $h1) % $m))
}

function Get-Ed25519Multiple($curve, $scalar, $point) {
	$big = [System.Numerics.BigInteger]
	$sum = @($big::Zero, $big::One, $big::One, $big::Zero)
	while ($scalar.Sign -gt 0) {
		if (-not $scalar.IsEven) { $sum = Add-Ed25519Point $curve $sum $point }
		$point = Add-Ed25519Point $curve $point $point
		$scalar = $scalar -shr 1
	}
	$sum
}

# Whether $signature (64 bytes) is $publicKey's (32 bytes) signature of $message.
function Test-Ed25519Signature([byte[]]$publicKey, [byte[]]$message, [byte[]]$signature) {
	if ($publicKey.Length -ne 32 -or $signature.Length -ne 64) { return $false }
	if ($null -eq $message) { $message = [byte[]]::new(0) }
	$curve = New-Ed25519
	$A = Get-Ed25519Point $curve $publicKey
	$R = Get-Ed25519Point $curve ([byte[]]$signature[0..31])
	if ($null -eq $A -or $null -eq $R) { return $false }
	$s = ConvertFrom-Ed25519Bytes ([byte[]]$signature[32..63])
	if ($s -ge $curve.L) { return $false }
	$sha = [System.Security.Cryptography.SHA512]::Create()
	$k = (ConvertFrom-Ed25519Bytes $sha.ComputeHash([byte[]]($signature[0..31] + $publicKey + $message))) % $curve.L
	$left = Get-Ed25519Multiple $curve $s $curve.G
	$right = Add-Ed25519Point $curve $R (Get-Ed25519Multiple $curve $k $A)
	(Get-Ed25519Mod $curve ($left[0] * $right[2] - $right[0] * $left[2])).IsZero -and (Get-Ed25519Mod $curve ($left[1] * $right[2] - $right[1] * $left[2])).IsZero
}

# --- Look -------------------------------------------------------------------------------------------------------------

function Initialize-PiBoltStyle {
	$script:esc = [char]27
	$plain = [Console]::IsOutputRedirected -or $env:NO_COLOR -or $env:TERM -eq 'dumb'
	# Windows Terminal, and conhost from Windows 10 1809 with virtual terminal processing (PowerShell turns it on).
	if (-not $plain -and $Host.UI.SupportsVirtualTerminal) {
		$script:reset = "$esc[0m"; $script:dim = "$esc[2m"; $script:bold = "$esc[1m"; $script:cyan = "$esc[36m"
		$script:green = "$esc[32m"; $script:red = "$esc[31m"; $script:amber = "$esc[38;2;247;192;74m"
	} else {
		$script:reset = ''; $script:dim = ''; $script:bold = ''; $script:cyan = ''; $script:green = ''; $script:red = ''; $script:amber = ''
	}
}

function Write-PiBolt([string]$text) { [Console]::Out.Write($text) }

function Format-MB([double]$bytes) { '{0:N1}' -f ($bytes / 1MB) }

function Show-PiBoltProgress([string]$text) {
	if ([Console]::IsOutputRedirected) { return }
	Write-PiBolt "`r$esc[K  $amber>$reset ${bold}Installing Pi-Bolt$reset $dim$text$reset"
}

function Clear-PiBoltProgress { if (-not [Console]::IsOutputRedirected) { Write-PiBolt "`r$esc[K" } }

# A failure: said, and the installation stopped (by an exception, not `exit`: with `irm | iex` that would close the window).
function Stop-PiBolt([string]$message) {
	Clear-PiBoltProgress
	[Console]::Error.WriteLine("${red}error:$reset $message")
	throw [System.OperationCanceledException]::new('Pi-Bolt installer')
}

# --- Steps ------------------------------------------------------------------------------------------------------------

function Test-PiBoltPrerequisites {
	$problems = @()
	if ($ExecutionContext.SessionState.LanguageMode -ne 'FullLanguage') {
		$problems += "PowerShell is in $($ExecutionContext.SessionState.LanguageMode) mode here, in which the installer cannot check the download's signature. Download the release from $PiBoltRepo/releases and check it yourself."
	}
	if (-not [Environment]::Is64BitOperatingSystem) { $problems += 'Pi-Bolt runs on 64-bit Windows only.' }
	$arch = $env:PROCESSOR_ARCHITEW6432; if (-not $arch) { $arch = $env:PROCESSOR_ARCHITECTURE }
	if ($arch -ne 'AMD64' -and $arch -ne 'ARM64') { $problems += "Pi-Bolt runs on x86-64 (this is $arch)." }
	# ConPTY, and the console's virtual terminal sequences, which Pi's interface needs: Windows 10 1809 (build 17763).
	$build = [Environment]::OSVersion.Version.Build
	if ($build -lt 17763) { $problems += "Pi-Bolt needs Windows 10 version 1809 or later (this is build $build)." }
	# Windows on ARM runs x64 code from Windows 11 on; Windows 10 on ARM emulates 32-bit x86 only.
	if ($arch -eq 'ARM64' -and $build -lt 22000) { $problems += "On ARM, Pi-Bolt needs Windows 11, whose x64 emulation runs it (this is Windows 10, build $build)." }
	foreach ($p in $problems) { [Console]::Error.WriteLine("${red}error:$reset $p") }
	if ($problems) { throw [System.OperationCanceledException]::new('Pi-Bolt installer') }
	if ($arch -eq 'ARM64') { Write-Host "  ${dim}note: this is Windows on ARM; Pi-Bolt is x64 code and runs under its emulation$reset" }
}

# Whether the CPU has AVX2 (what the x64 build's compiled code is for; without it, that build runs its bytecode instead).
function Test-PiBoltAvx2 {
	try {
		if (-not ('PiBoltInstaller.Cpu' -as [type])) {
			Add-Type -Namespace PiBoltInstaller -Name Cpu -MemberDefinition '[DllImport("kernel32.dll")] public static extern bool IsProcessorFeaturePresent(uint feature);'
		}
		if ([PiBoltInstaller.Cpu]::IsProcessorFeaturePresent(40)) { return $true } # PF_AVX2_INSTRUCTIONS_AVAILABLE
		# Windows 10 may not fill that feature in (it is newer than Windows 10's first builds): no is not known there.
		return [Environment]::OSVersion.Version.Build -lt 22000
	} catch {
		return $true # Not known: the default build, which works on any x86-64 CPU all the same.
	}
}

function New-PiBoltClient {
	# TLS 1.2 or later (Windows PowerShell's .NET Framework may otherwise offer only older versions).
	[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
	Add-Type -AssemblyName System.Net.Http
	$handler = [System.Net.Http.HttpClientHandler]::new()
	$client = [System.Net.Http.HttpClient]::new($handler)
	$client.Timeout = [TimeSpan]::FromMinutes(30)
	$client.DefaultRequestHeaders.UserAgent.ParseAdd('pi-bolt-installer')
	$client
}

# Downloads $url to $file with a progress line; $false if it could not.
function Get-PiBoltFile($client, [string]$url, [string]$file, [string]$what) {
	try {
		$response = $client.GetAsync($url, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
		if (-not $response.IsSuccessStatusCode) { return $false }
		$total = $response.Content.Headers.ContentLength
		$in = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
		$out = [System.IO.File]::Create($file)
		try {
			$buffer = [byte[]]::new(1MB)
			$got = 0
			$clock = [Diagnostics.Stopwatch]::StartNew()
			$shown = -1
			while (($n = $in.Read($buffer, 0, $buffer.Length)) -gt 0) {
				$out.Write($buffer, 0, $n)
				$got += $n
				if ($what -and $clock.ElapsedMilliseconds - $shown -ge 100) {
					$shown = $clock.ElapsedMilliseconds
					$rate = if ($clock.Elapsed.TotalSeconds -gt 0.5) { ", $(Format-MB ($got / $clock.Elapsed.TotalSeconds)) MB/s" } else { '' }
					if ($total) { Show-PiBoltProgress "$what $(Format-MB $got) / $(Format-MB $total) MB$rate" } else { Show-PiBoltProgress "$what $(Format-MB $got) MB$rate" }
				}
			}
		} finally { $out.Dispose(); $in.Dispose() }
		return (-not $total) -or $got -eq $total
	} catch {
		return $false
	}
}

# Copies one file, by its path in the archive, out of a .tgz (gzip, then tar: what npm publishes) to $out; $false if it is not
# there or the archive is not one. Read here rather than by tar.exe: the tarball is not verified (the .zip in it is, by the signed
# checksums, once it is out), so nothing but this reads it: a header at a time, the one file's bytes, nothing else of it kept.
function Get-PiBoltTarEntry([string]$tgz, [string]$entry, [string]$out) {
	$in = $null; $gz = $null
	try {
		$in = [System.IO.File]::OpenRead($tgz)
		$gz = [System.IO.Compression.GZipStream]::new($in, [System.IO.Compression.CompressionMode]::Decompress)
		$header = [byte[]]::new(512)
		$ascii = [System.Text.Encoding]::ASCII
		$longName = $null
		$read = {
			param([byte[]]$buffer, [long]$count)
			$got = 0
			while ($got -lt $count) { $n = $gz.Read($buffer, $got, [int]($count - $got)); if ($n -le 0) { return $false }; $got += $n }
			$true
		}
		$skip = {
			param([long]$count)
			$chunk = [byte[]]::new(65536)
			while ($count -gt 0) { $n = $gz.Read($chunk, 0, [int][Math]::Min($count, 65536)); if ($n -le 0) { return $false }; $count -= $n }
			$true
		}
		while (& $read $header 512) {
			if ($header[0] -eq 0) { return $false } # the end: two blocks of zeros
			$octal = $ascii.GetString($header, 124, 12).Trim([char]0, ' ')
			if ($octal -notmatch '^[0-7]{1,11}$') { return $false }
			$size = [Convert]::ToInt64($octal, 8)
			$padded = [long]([Math]::Ceiling($size / 512) * 512)
			$type = [char]$header[156]
			$name = $ascii.GetString($header, 0, 100).Split([char]0)[0]
			if ($ascii.GetString($header, 257, 5) -eq 'ustar') {
				$prefix = $ascii.GetString($header, 345, 155).Split([char]0)[0]
				if ($prefix) { $name = "$prefix/$name" }
			}
			if ($longName) { $name = $longName; $longName = $null }
			if ($type -eq 'L' -and $size -le 4096) {
				# (GNU's long name: the next entry's name, in this one's data.)
				$data = [byte[]]::new($padded)
				if (-not (& $read $data $padded)) { return $false }
				$longName = $ascii.GetString($data, 0, [int]$size).Split([char]0)[0]
				continue
			}
			if (($type -eq '0' -or $type -eq [char]0) -and $name -eq $entry) {
				# (Before it is verified, a size it says is only believed so far: a release's archive is a few hundred MB.)
				if ($size -gt 1GB) { return $false }
				$file = [System.IO.File]::Create($out)
				try {
					$chunk = [byte[]]::new(1MB)
					$left = $size
					while ($left -gt 0) {
						$n = $gz.Read($chunk, 0, [int][Math]::Min($left, $chunk.Length))
						if ($n -le 0) { return $false }
						$file.Write($chunk, 0, $n)
						$left -= $n
					}
				} finally { $file.Dispose() }
				return $true
			}
			if (-not (& $skip $padded)) { return $false }
		}
		$false
	} catch {
		$false
	} finally {
		if ($gz) { $gz.Dispose() }
		if ($in) { $in.Dispose() }
	}
}

# The line of SHA256SUMS for $name, and whether $file matches it. (A file that SHA256SUMS has no line for does not match.)
function Test-PiBoltChecksum([string]$sums, [string]$name, [string]$file) {
	$pattern = "^([0-9a-fA-F]{64}) [ *]?$([regex]::Escape($name))$"
	$line = (Get-Content -LiteralPath $sums) | Where-Object { $_ -match $pattern } | Select-Object -First 1
	if (-not $line -or $line -notmatch $pattern) { return $false }
	(Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash -eq $Matches[1].ToUpperInvariant()
}

function Get-PiBoltInstalledVersion([string]$dir) {
	$file = Join-Path $dir 'pi-bolt.txt'
	if ((Test-Path -LiteralPath $file) -and ((Get-Content -LiteralPath $file -TotalCount 1) -match '^Pi-Bolt ([0-9.]+) ')) { return $Matches[1] }
	''
}

# Moves the new build into place over the old one. A running Pi-Bolt (`pi-bolt update`) keeps its executable and DLLs open, and
# Windows lets those be renamed but not replaced or deleted: each file that is there is renamed out of the way first, and what was
# renamed is deleted now if it can be, or by the next installation.
function Install-PiBoltFiles([string]$from, [string]$to) {
	if (Test-Path -LiteralPath $to) {
		Get-ChildItem -LiteralPath $to -Recurse -File | Where-Object { $_.Name -like '*.pibolt-old' -or $_.Name -like '*.pibolt-new' } |
			ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue }
	}
	New-Item -ItemType Directory -Force -Path $to | Out-Null
	$stamp = [DateTime]::UtcNow.Ticks
	# pi-bolt.exe last: until it is in place, the one that runs is the old one. (If a move fails part of the way, the files moved
	# before it are the new ones; the installer says so and stops, and running it again puts the rest in place.) Each new file is
	# first moved next to its target (from %TEMP%, perhaps another drive: a copy, which can fail on a full disk), and only then is
	# the old one renamed aside and the new one renamed into place, in the same folder; if that last step fails, the old one is
	# put back. A file is never missing because a copy failed.
	$files = @(Get-ChildItem -LiteralPath $from -Recurse -File | Sort-Object { $_.Name -ieq 'pi-bolt.exe' })
	foreach ($file in $files) {
		$relative = $file.FullName.Substring($from.Length).TrimStart('\')
		$target = Join-Path $to $relative
		$staged = "$target.$stamp.pibolt-new"
		$aside = "$target.$stamp.pibolt-old"
		try {
			New-Item -ItemType Directory -Force -Path (Split-Path $target) -ErrorAction Stop | Out-Null
			Move-Item -LiteralPath $file.FullName -Destination $staged -ErrorAction Stop
			if (Test-Path -LiteralPath $target) { Move-Item -LiteralPath $target -Destination $aside -Force -ErrorAction Stop }
			try {
				Move-Item -LiteralPath $staged -Destination $target -ErrorAction Stop
			} catch {
				if ((Test-Path -LiteralPath $aside) -and -not (Test-Path -LiteralPath $target)) { Move-Item -LiteralPath $aside -Destination $target -ErrorAction SilentlyContinue }
				throw
			}
			Remove-Item -LiteralPath $aside -Force -ErrorAction SilentlyContinue
		} catch {
			Remove-Item -LiteralPath $staged -Force -ErrorAction SilentlyContinue
			Stop-PiBolt "could not put $target in place ($($_.Exception.Message)). Close Pi-Bolt and anything that has its files open, then run the installer again."
		}
	}
}

# A folder Pi-Bolt is installed in outside the user's profile (PIBOLT_INSTALL=C:\tools\pi-bolt) inherits its parent's
# permissions, and a folder made in C:\ lets every user change what is in it: anyone on the machine could then replace
# pi-bolt.exe, which is on this user's PATH. Such a folder is made the user's, SYSTEM's and the Administrators' only.
function Protect-PiBoltFolder([string]$dir) {
	$inProfile = foreach ($root in $env:USERPROFILE, $env:LOCALAPPDATA) {
		if ($root -and $dir.StartsWith($root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { $true }
	}
	if ($inProfile) { return }
	$acl = New-Object System.Security.AccessControl.DirectorySecurity
	$acl.SetAccessRuleProtection($true, $false)
	$owners = @([Security.Principal.WindowsIdentity]::GetCurrent().User, [Security.Principal.SecurityIdentifier]::new('S-1-5-18'), [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))
	foreach ($sid in $owners) {
		$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'ContainerInherit, ObjectInherit', 'None', 'Allow'))
	}
	try { Set-Acl -LiteralPath $dir -AclObject $acl -ErrorAction Stop }
	catch { Write-Host "  ${dim}note: could not limit who can change $dir ($($_.Exception.Message))$reset" }
}

function Install-PiBoltRelease($state) {
	$name = $state.Name
	$tmp = Join-Path ([IO.Path]::GetTempPath()) "pi-bolt-$([Guid]::NewGuid().ToString('N').Substring(0, 12))"
	New-Item -ItemType Directory -Force -Path $tmp | Out-Null
	try {
		$client = New-PiBoltClient
		Show-PiBoltProgress 'connecting'
		$sums = Join-Path $tmp 'SHA256SUMS'
		if (-not (Get-PiBoltFile $client "$($state.Base)/SHA256SUMS" $sums '')) { Stop-PiBolt "download failed: $($state.Base)/SHA256SUMS" }
		$archive = "$name.zip"
		if (-not ((Get-Content -LiteralPath $sums) -match " [ *]?$([regex]::Escape($archive))$")) {
			Stop-PiBolt "this release has no Windows build ($archive is not in its SHA256SUMS)"
		}
		# The signature first: nothing else is looked at in a release whose checksums are not Pi-Bolt's.
		$signed = $false
		$sigFile = Join-Path $tmp 'SHA256SUMS.sig'
		if (Get-PiBoltFile $client "$($state.Base)/SHA256SUMS.sig" $sigFile '') {
			Show-PiBoltProgress 'verifying the signature'
			$der = [Convert]::FromBase64String($PiBoltReleaseKey)
			if (-not (Test-Ed25519Signature ([byte[]]$der[12..43]) ([IO.File]::ReadAllBytes($sums)) ([IO.File]::ReadAllBytes($sigFile)))) {
				Stop-PiBolt "the release's signature does not verify: the download is not Pi-Bolt's. Nothing was installed."
			}
			$signed = $true
		} elseif ($env:PIBOLT_ALLOW_UNSIGNED -ne '1') {
			# (A mirror, PIBOLT_DOWNLOAD_BASE, copies the signature too: it is not a reason to do without it.)
			Stop-PiBolt "the release has no signature (SHA256SUMS.sig), and every Windows release is signed. Nothing was installed."
		}
		# The signature covers the files, and the first line says which release they are: without it, an older release (signed all the
		# same) could be served as a newer one.
		$first = Get-Content -LiteralPath $sums -TotalCount 1
		if ($first -notmatch '^# pi-bolt (\d+\.\d+\.\d+)$') { Stop-PiBolt 'the release''s SHA256SUMS does not say which version it is. Nothing was installed.' }
		if ($state.Version -and $Matches[1] -ne $state.Version) {
			Stop-PiBolt "the download is Pi-Bolt $($Matches[1]), not $($state.Version) as asked for (an older release served as a newer one?). Nothing was installed."
		}
		$state.Version = $Matches[1]; $state.Shown = $Matches[1]
		# The optional extensions this release pins (extensions.txt, covered by the signed checksums).
		$state.Extensions = @()
		$pins = Join-Path $tmp 'extensions.txt'
		if ((Get-PiBoltFile $client "$($state.Base)/extensions.txt" $pins '') -and (Test-PiBoltChecksum $sums 'extensions.txt' $pins)) {
			foreach ($line in Get-Content -LiteralPath $pins) {
				if ($line -match '^([a-z0-9][a-z0-9._-]*) (\d+\.\d+\.\d+) (sha512-[A-Za-z0-9+/]+=*)$') {
					$state.Extensions += @{ Package = $Matches[1]; Version = $Matches[2]; Integrity = $Matches[3] }
				}
			}
		}
		$file = Join-Path $tmp $archive
		$from = ''
		if ($state.Source -ne 'github' -and -not $env:PIBOLT_DOWNLOAD_BASE) {
			# The npm package pi-bolt-win32-x64 (of the same version) is a .tgz with the release's .zip in it.
			$registry = if ($env:PIBOLT_NPM_REGISTRY) { $env:PIBOLT_NPM_REGISTRY.TrimEnd('/') } else { 'https://registry.npmjs.org' }
			$tgz = Join-Path $tmp 'npm.tgz'
			if ((Get-PiBoltFile $client "$registry/$name/-/$name-$($state.Version).tgz" $tgz 'downloading')) {
				if (Get-PiBoltTarEntry $tgz "package/$archive" $file) {
					Show-PiBoltProgress 'verifying the checksum'
					if (Test-PiBoltChecksum $sums $archive $file) { $from = 'npm' }
				}
			}
			if (-not $from) {
				if ($state.Source -eq 'npm') { Stop-PiBolt "npm does not have Pi-Bolt $($state.Version), or its download does not match the release's checksum" }
				Clear-PiBoltProgress
				Write-Host "  ${dim}the download from npm failed; downloading from GitHub instead$reset"
			}
		} elseif ($state.Source -eq 'npm') {
			Stop-PiBolt 'PIBOLT_SOURCE=npm needs the release from GitHub (no PIBOLT_DOWNLOAD_BASE)'
		}
		if (-not $from) {
			if (-not (Get-PiBoltFile $client "$($state.Base)/$archive" $file 'downloading')) { Stop-PiBolt "download failed: $($state.Base)/$archive" }
			Show-PiBoltProgress 'verifying the checksum'
			if (-not (Test-PiBoltChecksum $sums $archive $file)) { Stop-PiBolt 'checksum mismatch: the download is corrupt or incomplete' }
			$from = if ($env:PIBOLT_DOWNLOAD_BASE) { $env:PIBOLT_DOWNLOAD_BASE } else { 'GitHub' }
		}
		Show-PiBoltProgress 'extracting'
		Add-Type -AssemblyName System.IO.Compression.FileSystem
		$unpacked = Join-Path $tmp 'unpacked'
		[System.IO.Compression.ZipFile]::ExtractToDirectory($file, $unpacked)
		$exe = Join-Path $unpacked "$name\pi-bolt.exe"
		if (-not (Test-Path -LiteralPath $exe)) { Stop-PiBolt "$archive does not have $name\pi-bolt.exe" }
		Show-PiBoltProgress 'checking the executable'
		$null = & $exe --version 2>&1
		if ($LASTEXITCODE -ne 0) { Stop-PiBolt 'the downloaded executable does not run on this system' }
		# (The folder that holds the variant's too, if the installer is making it: whoever may change that may rename the other.)
		$madeInstall = -not (Test-Path -LiteralPath $state.Install)
		New-Item -ItemType Directory -Force -Path $state.Dir | Out-Null
		if ($madeInstall) { Protect-PiBoltFolder $state.Install }
		Protect-PiBoltFolder $state.Dir
		Install-PiBoltFiles (Join-Path $unpacked $name) $state.Dir
		Clear-PiBoltProgress
		$size = (Get-Item -LiteralPath $file).Length
		Write-Host ("  $green" + 'ok' + "$reset install complete $dim($($state.Platform)-$($state.Variant), $(Format-MB $size) MB from $from$(if ($signed) { ', signature verified' }))$reset")
		if (-not $signed) { Write-Host "  ${dim}note: $from has no signature for this release (SHA256SUMS.sig): only its checksum was verified$reset" }
	} finally {
		Clear-PiBoltProgress
		Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
	}
}

# --- PATH -------------------------------------------------------------------------------------------------------------

# The user's PATH as it is stored (with %VARIABLES% unexpanded), so that writing it back changes nothing else.
function Get-PiBoltUserPath {
	$key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment')
	try { [string]$key.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames) } finally { if ($key) { $key.Dispose() } }
}

function Set-PiBoltUserPath([string]$value) {
	$key = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Environment')
	try { $key.SetValue('Path', $value, [Microsoft.Win32.RegistryValueKind]::ExpandString) } finally { $key.Dispose() }
	# Tell running programs (Explorer, new terminals) that the environment changed: setting a variable through .NET broadcasts it.
	[Environment]::SetEnvironmentVariable('PIBOLT_INSTALLER_PATH_CHANGED', '1', 'User')
	[Environment]::SetEnvironmentVariable('PIBOLT_INSTALLER_PATH_CHANGED', $null, 'User')
}

function Test-PiBoltOnPath([string]$dir, [string]$path) {
	foreach ($entry in ($path -split ';')) {
		if ($entry -and [Environment]::ExpandEnvironmentVariables($entry).TrimEnd('\') -ieq $dir.TrimEnd('\')) { return $true }
	}
	$false
}

function Add-PiBoltToPath([string]$dir) {
	# Another variant of this installation on PATH (pi-bolt-win32-x64 when this is -x64-jit, say) would be found first, and the
	# build just installed would not be the one that runs: it comes off.
	$root = Split-Path $dir
	foreach ($other in Get-ChildItem -LiteralPath $root -Directory -Filter 'pi-bolt-win32-*' -ErrorAction SilentlyContinue) {
		if ($other.FullName.TrimEnd('\') -ine $dir.TrimEnd('\') -and (Test-PiBoltOnPath $other.FullName (Get-PiBoltUserPath))) {
			Remove-PiBoltFromPath $other.FullName
			Write-Host "  ${dim}took $($other.FullName) off your PATH: $dir is the one installed now$reset"
		}
	}
	$userPath = Get-PiBoltUserPath
	if (-not (Test-PiBoltOnPath $dir $userPath)) {
		if ($env:PIBOLT_NO_PATH -eq '1') {
			Write-Host "`n$dir is not on your PATH (PIBOLT_NO_PATH=1). Run Pi-Bolt as:`n`n  & '$dir\pi-bolt.exe'"
			return
		}
		if (Test-PiBoltCanAsk) {
			Write-Host -NoNewline "`nAdd $dir to your PATH (for your user account)? [Y/n] "
			$answer = Read-Host
			if ($answer -match '^(n|no)$') {
				Write-Host "Not added. Run Pi-Bolt as:`n`n  & '$dir\pi-bolt.exe'"
				return
			}
		}
		Set-PiBoltUserPath ((@($userPath.TrimEnd(';')) + $dir | Where-Object { $_ }) -join ';')
		Write-Host "  ${dim}added $dir to your PATH; new terminals will have it$reset"
	}
	# And this window.
	if (-not (Test-PiBoltOnPath $dir $env:Path)) { $env:Path = "$($env:Path.TrimEnd(';'));$dir" }
}

function Remove-PiBoltFromPath([string]$dir) {
	$userPath = Get-PiBoltUserPath
	$kept = ($userPath -split ';') | Where-Object { $_ -and [Environment]::ExpandEnvironmentVariables($_).TrimEnd('\') -ine $dir.TrimEnd('\') }
	$new = $kept -join ';'
	if ($new -ne $userPath.TrimEnd(';')) { Set-PiBoltUserPath $new }
}

# --- Questions --------------------------------------------------------------------------------------------------------

function Test-PiBoltCanAsk { $env:PIBOLT_YES -ne '1' -and [Environment]::UserInteractive -and -not [Console]::IsInputRedirected }

function Read-PiBoltKey {
	try { return [string][Console]::ReadKey($true).KeyChar } catch { return '' }
}

function Select-PiBoltAction($state) {
	$existing = Test-Path -LiteralPath (Join-Path $state.Dir 'pi-bolt.exe')
	$default = if ($existing) { 'reinstall' } else { 'install' }
	$installed = if ($existing) { Get-PiBoltInstalledVersion $state.Dir } else { '' }
	$state.Installed = $installed
	if ($existing) {
		if ($installed -and $installed -ne $state.Shown) { Write-Host "${bold}Pi-Bolt $installed is installed at:$reset`n`n  $($state.Dir)`n" }
		else { Write-Host "${bold}Pi-Bolt is already installed at:$reset`n`n  $($state.Dir)`n" }
	}
	$cpu = switch ($state.Variant) { 'x64' { 'for CPUs with AVX2' } 'x64-baseline' { 'for any x86-64 CPU' } default { 'with the JIT on' } }
	Write-Host "${bold}Installation:$reset`n"
	Write-Host "  ${amber}Pi-Bolt $($state.Shown)$reset, $($state.Platform)-$($state.Variant) build $cpu"
	Write-Host "  ${dim}installs to$reset  $($state.Dir)`n"
	Write-Host "${bold}Choose an action:$reset`n"
	if ($existing) {
		$action = if ($installed -and $installed -ne $state.Shown) { "Update to Pi-Bolt $($state.Shown)" } else { 'Reinstall Pi-Bolt' }
		Write-Host "  ${cyan}y   $reset $green$action$reset $dim(default)$reset"
		Write-Host "  ${cyan}u   $reset ${red}Uninstall Pi-Bolt$reset"
	} else {
		Write-Host "  ${cyan}y   $reset ${green}Install Pi-Bolt$reset $dim(default)$reset"
	}
	Write-Host "  ${cyan}n   $reset ${dim}Do nothing$reset"
	$choice = $default
	if (Test-PiBoltCanAsk) {
		while ($true) {
			$key = Read-PiBoltKey
			if ($key -match '^[yY\r ]$' -or $key -eq '') { $choice = $default; break }
			if ($key -match '^[uU]$' -and $existing) { $choice = 'uninstall'; break }
			if ($key -match '^[nN]$' -or $key -eq [string][char]27) { $choice = 'none'; break }
			Write-Host 'Please choose one of the listed keys.'
		}
	}
	switch ($choice) {
		'install' { Write-Host "`nWill install Pi-Bolt.`n" }
		'reinstall' { Write-Host "`nWill reinstall Pi-Bolt.`n" }
		'uninstall' { Write-Host "`nWill uninstall Pi-Bolt." }
		'none' { Write-Host "`nChose to do nothing. Exiting." }
	}
	$choice
}

# Whether the registry has the pinned version of an extension as pinned: its record says the pinned integrity, and its tarball has
# it. (The package manager then installs that version, and checks the tarball against the same record.)
function Test-PiBoltExtension([string]$registry, $extension) {
	try {
		$client = New-PiBoltClient
		$record = $client.GetStringAsync("$registry/$($extension.Package)/$($extension.Version)").GetAwaiter().GetResult() | ConvertFrom-Json
		if ($record.dist.integrity -cne $extension.Integrity) { return $false }
		$tarball = $client.GetByteArrayAsync($record.dist.tarball).GetAwaiter().GetResult()
		$digest = [Convert]::ToBase64String([System.Security.Cryptography.SHA512]::Create().ComputeHash($tarball))
		return "sha512-$digest" -ceq $extension.Integrity
	} catch {
		return $false
	}
}

# Whether what the package manager installed of an extension has the pinned integrity, by its lockfile (npm's, bun's or pnpm's,
# in the folder Pi installs npm packages to; `pi-bolt list` says where): 'same', 'different', or 'unknown' (no lockfile).
function Test-PiBoltInstalledExtension([string]$exe, $extension) {
	$lines = @(& $exe list 2>$null)
	for ($i = 0; $i -lt $lines.Count - 1; $i++) {
		if ($lines[$i] -match "^\s+npm:$([regex]::Escape($extension.Package))(@\S*)?\s*$") {
			$root = Split-Path (Split-Path $lines[$i + 1].Trim()) # <root>\node_modules\<package>
			foreach ($lock in 'package-lock.json', 'bun.lock', 'pnpm-lock.yaml') {
				$path = Join-Path $root $lock
				if (Test-Path -LiteralPath $path) {
					if ([IO.File]::ReadAllText($path).Contains($extension.Integrity)) { return 'same' } else { return 'different' }
				}
			}
			return 'unknown'
		}
	}
	'unknown'
}

# OpenSec's optional extensions (see install.sh, which offers the same), at the versions the release pins.
function Install-PiBoltExtensions($state) {
	$choice = $env:PIBOLT_EXTENSIONS
	if ($choice -and $choice -ne 'yes' -and $choice -ne 'no') { Write-Host "  ${dim}PIBOLT_EXTENSIONS must be yes or no: no extensions installed$reset"; return }
	if (-not $choice -and -not (Test-PiBoltCanAsk)) { return }
	if ($choice -eq 'no') { return }
	$exe = Join-Path $state.Dir 'pi-bolt.exe'
	$sources = (& $exe list 2>$null) -join "`n"
	$extensions = @(
		@{ Package = 'opensec-pi-subagents'; What = 'run specialized agents in separate sessions'; Kind = 'subagents|swarm' },
		@{ Package = 'opensec-pi-todo'; What = 'a todo list for the model, shown above the editor'; Kind = 'rpiv-todo|pi-todo' })
	# Only the versions the release pins, and only with the integrity it says they have.
	foreach ($e in $extensions) {
		$pin = $state.Extensions | Where-Object { $_.Package -eq $e.Package } | Select-Object -First 1
		if ($pin) { $e.Version = $pin.Version; $e.Integrity = $pin.Integrity }
	}
	$extensions = @($extensions | Where-Object { $_.Version })
	# What the executable has compiled in already (pi-bolt.txt's `plugins:` line, scripts\build-pi.ps1): not offered again. (It can be
	# turned off with `-builtin:<name>` in the extensions setting; installed from npm, a copy replaces the compiled one.)
	$about = Join-Path $state.Dir 'pi-bolt.txt'
	$compiledIn = if (Test-Path -LiteralPath $about) { (Get-Content -LiteralPath $about | Where-Object { $_ -like 'plugins: *' } | Select-Object -First 1) } else { $null }
	if ($compiledIn) {
		$extensions = @($extensions | Where-Object { $compiledIn -notmatch "(^plugins: |, )$([regex]::Escape($_.Package)) " })
		if (-not $extensions) { return }
	}
	if (-not $extensions) {
		if ($choice -eq 'yes') { Write-Host "  ${dim}this release pins no extensions (extensions.txt): none installed$reset" }
		return
	}
	$wanted = @()
	foreach ($e in $extensions) {
		if ($sources -match "npm:$($e.Package)(@\S*)?(\r?\n|$)") { continue }
		$other = ($sources -split "`n") | Where-Object { $_ -match '^  [^ ]' -and $_ -match $e.Kind } | Select-Object -First 1
		if ($other) { Write-Host "  $dim$($e.Package) is not offered: $($other.Trim()) is installed and registers the same tools$reset"; continue }
		$wanted += $e
	}
	if (-not $wanted) { return }
	if (-not $choice) {
		Write-Host "`n${bold}Optional extensions by OpenSec$reset $dim(Apache-2.0, built for Pi-Bolt)$reset`n"
		foreach ($e in $wanted) { Write-Host ("  $cyan{0,-22}$reset {1}" -f $e.Package, $e.What) }
		Write-Host -NoNewline "`nInstall them? [Y/n] "
		if ((Read-Host) -match '^(n|no)$') { Write-Host "${dim}You can install them later with: pi-bolt install npm:<name>$reset"; return }
	}
	$registry = if ($env:PIBOLT_NPM_REGISTRY) { $env:PIBOLT_NPM_REGISTRY.TrimEnd('/') } else { 'https://registry.npmjs.org' }
	foreach ($e in $wanted) {
		$spec = "$($e.Package)@$($e.Version)"
		if (-not (Test-PiBoltExtension $registry $e)) {
			Write-Host "  ${red}did not install $spec (Pi-Bolt itself is installed): the registry's package is not the one this release pins$reset"
			continue
		}
		# From the registry that was checked, and without running the package's install scripts: nothing of it runs until its
		# lockfile entry has been compared with the pin (below), and an extension of Pi needs none.
		$saved = @{ NPM_CONFIG_REGISTRY = $env:NPM_CONFIG_REGISTRY; NPM_CONFIG_IGNORE_SCRIPTS = $env:NPM_CONFIG_IGNORE_SCRIPTS }
		$env:NPM_CONFIG_REGISTRY = $registry
		$env:NPM_CONFIG_IGNORE_SCRIPTS = 'true'
		try {
			$out = & $exe install "npm:$spec" 2>&1
			$installed = $LASTEXITCODE -eq 0
		} finally {
			$env:NPM_CONFIG_REGISTRY = $saved.NPM_CONFIG_REGISTRY
			$env:NPM_CONFIG_IGNORE_SCRIPTS = $saved.NPM_CONFIG_IGNORE_SCRIPTS
		}
		if ($installed) {
			# What the package manager installed is a download of its own: its lockfile says what it got.
			$check = Test-PiBoltInstalledExtension $exe $e
			if ($check -ne 'same') {
				# (Not known is not the pin either: what was installed is a download the installer has not seen.)
				& $exe remove "npm:$spec" *> $null
				$why = if ($check -eq 'different') { 'is not the one this release pins' } else { 'cannot be checked against the pin (no lockfile says what it is)' }
				Write-Host "  ${red}removed $spec again: what the package manager installed $why$reset"
				Write-Host "  ${dim}Install it yourself if you want it: pi-bolt install npm:$spec$reset"
				continue
			}
		}
		if ($installed) { Write-Host "  ${green}ok$reset $spec installed" }
		else {
			Write-Host "  ${red}could not install $($e.Package) (Pi-Bolt itself is installed): $(($out | Select-Object -Last 1))$reset"
			Write-Host "  ${dim}Install it later with: pi-bolt install npm:$($e.Package)$reset"
		}
	}
}

# --- Main -------------------------------------------------------------------------------------------------------------

function Install-PiBolt {
	Initialize-PiBoltStyle
	$launcher = $env:PIBOLT_LAUNCHER -eq '1'
	if ($launcher) { Write-Host "${bold}  Pi-Bolt$reset`n${dim}  First run: getting the native executable$reset`n" }
	else { Write-Host "`n${bold}  Pi-Bolt Installer$reset`n${dim}  Pi, compiled ahead of time. Native speed, no JIT.$reset`n" }
	Test-PiBoltPrerequisites

	$version = if ($env:PIBOLT_VERSION) { $env:PIBOLT_VERSION } else { 'latest' }
	$source = if ($env:PIBOLT_SOURCE) { $env:PIBOLT_SOURCE } else { 'auto' }
	if ($source -notin 'auto', 'npm', 'github') { Stop-PiBolt 'PIBOLT_SOURCE must be auto, npm or github' }
	$variant = $env:PIBOLT_VARIANT
	if ($variant -and $variant -notin 'x64', 'x64-baseline', 'x64-jit') { Stop-PiBolt 'PIBOLT_VARIANT must be x64, x64-baseline or x64-jit' }
	# Which release: the latest is looked up once (where its page redirects to), and everything is downloaded from that tag, so a
	# release published meanwhile cannot mix two. The version is then checked against the signed checksums.
	$shown = ''
	if ($version -ne 'latest') {
		if ($version -notmatch '^bolt-v(\d+\.\d+\.\d+)$') { Stop-PiBolt 'PIBOLT_VERSION must be a release tag such as bolt-v0.8.0' }
		$shown = $Matches[1]
	} elseif (-not $env:PIBOLT_DOWNLOAD_BASE) {
		try {
			$response = (New-PiBoltClient).GetAsync("$PiBoltRepo/releases/latest").GetAwaiter().GetResult()
			if ($response.RequestMessage.RequestUri.AbsoluteUri -match '/tag/bolt-v(\d+\.\d+\.\d+)$') { $shown = $Matches[1] }
		} catch { }
		if (-not $shown) { Stop-PiBolt "could not find out which release is the latest ($PiBoltRepo/releases/latest): try again, or set PIBOLT_VERSION=bolt-vX.Y.Z" }
	}
	$base = "$PiBoltRepo/releases/download/bolt-v$shown"
	if ($env:PIBOLT_DOWNLOAD_BASE) { $base = $env:PIBOLT_DOWNLOAD_BASE.TrimEnd('/') }
	if (-not $variant) {
		$variant = 'x64'
		# The x64 build runs on any x86-64 CPU (its compiled code needs AVX2; without it, it runs its bytecode). A release that has
		# a baseline build has it for CPUs without AVX2.
		if (-not (Test-PiBoltAvx2)) { $variant = 'x64-baseline-if-there' }
	}
	$install = if ($env:PIBOLT_INSTALL) { $env:PIBOLT_INSTALL } else { Join-Path $env:USERPROFILE '.pi-bolt' }
	# A full path, which is what goes on PATH: a relative one there would be looked up from whatever folder a terminal is in.
	if (-not [System.IO.Path]::IsPathRooted($install)) { Stop-PiBolt "PIBOLT_INSTALL must be a full path (it is $install)" }
	$install = [System.IO.Path]::GetFullPath($install)
	$state = @{ Version = $shown; Shown = $(if ($shown) { $shown } else { '(latest)' }); Source = $source; Base = $base; Platform = 'win32'; Install = $install }
	if ($variant -eq 'x64-baseline-if-there') {
		$variant = 'x64'
		try {
			$sums = (New-PiBoltClient).GetStringAsync("$base/SHA256SUMS").GetAwaiter().GetResult()
			if ($sums -match ' [ *]?pi-bolt-win32-x64-baseline\.zip(\r?\n|$)') { $variant = 'x64-baseline' }
		} catch { }
	}
	$state.Variant = $variant
	$state.Name = "pi-bolt-win32-$variant"
	$state.Dir = Join-Path $install $state.Name

	if ($launcher) {
		Install-PiBoltRelease $state
		return
	}
	$action = Select-PiBoltAction $state
	if ($action -eq 'none') { return }
	if ($action -eq 'uninstall') {
		foreach ($dir in Get-ChildItem -LiteralPath $install -Directory -Filter 'pi-bolt-win32-*' -ErrorAction SilentlyContinue) {
			Remove-PiBoltFromPath $dir.FullName
			Remove-Item -LiteralPath $dir.FullName -Recurse -Force -ErrorAction SilentlyContinue
			if (Test-Path -LiteralPath $dir.FullName) { Write-Host "  ${dim}$($dir.FullName) is in use (is Pi-Bolt running?): close it, then remove the folder$reset" }
		}
		if ((Test-Path -LiteralPath $install) -and -not (Get-ChildItem -LiteralPath $install -Force)) { Remove-Item -LiteralPath $install -Force }
		Write-Host "`nPi-Bolt was uninstalled."
		return
	}
	Install-PiBoltRelease $state
	$exe = Join-Path $state.Dir 'pi-bolt.exe'
	$word = if ($action -eq 'reinstall' -and $state.Installed -and $state.Installed -ne $state.Shown) { 'updated' } elseif ($action -eq 'reinstall') { 'reinstalled' } else { 'installed' }
	Write-Host "`nPi-Bolt $($state.Shown) was $word successfully $dim(Pi $(& $exe --version))$reset."
	Add-PiBoltToPath $state.Dir
	Install-PiBoltExtensions $state
	if ((Get-Command pi-bolt -ErrorAction SilentlyContinue).Source -ieq $exe) { Write-Host "`nRun it with: ${bold}pi-bolt$reset" }
	if ((Test-PiBoltCanAsk) -and $env:PIBOLT_NO_START -ne '1') {
		Write-Host -NoNewline "`nStart pi-bolt now? [Y/n] "
		if ((Read-Host) -notmatch '^(n|no)$') { Write-Host ''; & $exe }
	}
}

if ($env:PIBOLT_INSTALLER_NO_MAIN -ne '1') {
	try {
		Install-PiBolt
		$global:LASTEXITCODE = 0
	} catch [System.OperationCanceledException] {
		$global:LASTEXITCODE = 1
	} catch {
		# Anything else is a failure too: said, with an exit status that says so (for `pi-bolt update`), not a success.
		Clear-PiBoltProgress
		[Console]::Error.WriteLine("${red}error:$reset the installer stopped: $($_.Exception.Message)")
		$global:LASTEXITCODE = 1
	}
}
