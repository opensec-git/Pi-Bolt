# install.ps1's Ed25519 (Test-Ed25519Signature), with its functions only (PIBOLT_INSTALLER_NO_MAIN): RFC 8032's test vectors
# 1-3 verify; changed messages and signatures, an s that is not below L, encodings of R that are not canonical, points of small
# order (the neutral point as the key "signs" any message in the check without them) and inputs of the wrong length do not.
# Needs nothing but Windows PowerShell.
#
# Usage: tests\installer\ed25519.ps1
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$env:PIBOLT_INSTALLER_NO_MAIN = '1'
. (Join-Path $root 'install.ps1')
$script:status = 0

function ConvertFrom-PiBoltTestHex([string]$hex) {
	$bytes = [byte[]]::new($hex.Length / 2)
	for ($i = 0; $i -lt $bytes.Length; $i++) { $bytes[$i] = [Convert]::ToByte($hex.Substring(2 * $i, 2), 16) }
	, $bytes
}

$vectors = @(
	@{ Name = 'RFC 8032 test 1'; Valid = $true
		Key = 'd75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a'; Message = ''
		Signature = 'e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b' }
	@{ Name = 'RFC 8032 test 2'; Valid = $true
		Key = '3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c'; Message = '72'
		Signature = '92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00' }
	@{ Name = 'RFC 8032 test 3'; Valid = $true
		Key = 'fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025'; Message = 'af82'
		Signature = '6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a' }
	@{ Name = 'test 2, another message'; Valid = $false
		Key = '3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c'; Message = '73'
		Signature = '92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00' }
	@{ Name = 'test 1, a bit of R changed'; Valid = $false
		Key = 'd75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a'; Message = ''
		Signature = 'e4564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b' }
	@{ Name = 'test 1, s + L (not below L)'; Valid = $false
		Key = 'd75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a'; Message = ''
		Signature = 'e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901554c8c7872aa064e049dbb3013fbf29380d25bf5f0595bbe24655141438e7a101b' }
	@{ Name = 'test 1, R not canonical (y = p)'; Valid = $false
		Key = 'd75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a'; Message = ''
		Signature = 'edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b' }
	@{ Name = 'test 1, R with x = 0 and its sign bit set'; Valid = $false
		Key = 'd75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a'; Message = ''
		Signature = '01000000000000000000000000000000000000000000000000000000000000805fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b' }
	@{ Name = 'test 1, R the neutral point, s = 0'; Valid = $false
		Key = 'd75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a'; Message = ''
		Signature = '01000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000' }
	@{ Name = 'the neutral point as the key, R the neutral point, s = 0'; Valid = $false
		Key = '0100000000000000000000000000000000000000000000000000000000000000'; Message = ''
		Signature = '01000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000' }
	@{ Name = 'the neutral point as the key, another message'; Valid = $false
		Key = '0100000000000000000000000000000000000000000000000000000000000000'; Message = '70692d626f6c74'
		Signature = '01000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000' }
	@{ Name = 'test 1, signature of 63 bytes'; Valid = $false
		Key = 'd75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a'; Message = ''
		Signature = 'e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a10' }
	@{ Name = 'test 1, key of 31 bytes'; Valid = $false
		Key = 'd75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f70751'; Message = ''
		Signature = 'e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b' }
)
foreach ($v in $vectors) {
	$got = Test-Ed25519Signature (ConvertFrom-PiBoltTestHex $v.Key) (ConvertFrom-PiBoltTestHex $v.Message) (ConvertFrom-PiBoltTestHex $v.Signature)
	if ($got -eq $v.Valid) { Write-Host "PASS $($v.Name): $got" } else { Write-Host "FAIL $($v.Name): $got, not $($v.Valid)"; $script:status = 1 }
}
exit $script:status
