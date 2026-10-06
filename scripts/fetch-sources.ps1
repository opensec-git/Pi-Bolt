# Clones oven-sh/WebKit and oven-sh/bun at the commits in sources.json and applies Pi-Bolt's patches on top: Windows'
# scripts/fetch-sources.sh. Sources go to $env:PIBOLT_WORK (default .work\) as webkit\ and bun\.
#
# WebKit is fetched without file contents and checked out sparsely (Source, Tools, WebKitLibraries, JSTests, icu, scripts,
# resources and the top-level files): its LayoutTests alone are hundreds of thousands of files, which Windows and its virus
# scanner take a long time to write.
# Usage: scripts\fetch-sources.ps1
$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$Work = if ($env:PIBOLT_WORK) { $env:PIBOLT_WORK } else { Join-Path $Root '.work' }
$sources = Get-Content (Join-Path $Root 'sources.json') -Raw | ConvertFrom-Json
function Log($message) { Write-Host "==> $message" -ForegroundColor Cyan }
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw "'git' is required" }

function Fetch($name, [string[]]$sparse) {
	$s = $sources.$name
	$dir = Join-Path $Work $name
	if (Test-Path (Join-Path $dir '.git')) { Log "${name}: already at $dir"; return }
	Log "${name}: cloning $($s.repository) @ $($s.commit.Substring(0, 12))"
	New-Item -ItemType Directory -Force $Work | Out-Null
	git init -q $dir
	git -C $dir config core.longpaths true
	git -C $dir config core.autocrlf false
	git -C $dir remote add origin $s.repository
	$filter = @()
	if ($sparse) {
		git -C $dir config extensions.partialClone origin
		git -C $dir config remote.origin.promisor true
		git -C $dir config remote.origin.partialclonefilter blob:none
		git -C $dir sparse-checkout set --cone @sparse
		$filter = @('--filter=blob:none')
	}
	# A commit by its hash where the server allows it; otherwise the branch it is on.
	git -C $dir fetch -q --depth 1 @filter origin $s.commit
	if ($LASTEXITCODE -ne 0) {
		if (-not $s.branch) { throw "${name}: cannot fetch $($s.commit)" }
		git -C $dir fetch -q @filter origin $s.branch
		if ($LASTEXITCODE -ne 0) { throw "${name}: cannot fetch $($s.branch)" }
	}
	git -C $dir checkout -q --detach $s.commit
	if ($LASTEXITCODE -ne 0) { throw "${name}: checkout failed" }
	Log "${name}: applying $(Split-Path -Leaf $s.patches)"
	git -C $dir -c user.name=pi-bolt -c user.email=pi-bolt@localhost am -q (Join-Path $Root $s.patches)
	if ($LASTEXITCODE -ne 0) { throw "${name}: the patch did not apply" }
}

Fetch webkit @('Source', 'Tools', 'WebKitLibraries', 'JSTests', 'icu', 'scripts', 'resources')
Fetch bun
Log "sources ready in $Work"
