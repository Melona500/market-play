param([switch]$SelfTest, [switch]$CheckClean)

$ErrorActionPreference = 'Stop'
$RepoPath = $PSScriptRoot

function Test-SyncPath([string]$Path) {
    $path = $Path.Replace('\', '/')
    return $path -match '^(start\.bat|start-market-play\.ps1|build-resource-pack\.ps1|sync\.ps1|server\.properties|\.gitignore)$' -or
        $path -match '^MarketPlay-Pack-[^/]+\.zip$' -or
        $path -match '^plugins/RPGMaker(\.jar|/config\.yml)$' -or
        $path -match '^dialogue-display-plugin/(src/|build\.gradle\.kts$|settings\.gradle\.kts$)' -or
        $path -match '^dialogue-resource-pack/' -or
        $path -match '^rpgmaker-web-editor/(src/|public/|scripts/|package(-lock)?\.json$|index\.html$|README\.md$|\.gitignore$|tsconfig[^/]*\.json$|vite\.config\.ts$)'
}

function Invoke-Git([string[]]$Arguments) {
    & git @Arguments
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed with exit code $LASTEXITCODE" }
}

function Get-ChangedPaths {
    $paths = @(
        & git -c core.quotepath=false ls-files --modified --deleted --others --exclude-standard
        & git -c core.quotepath=false diff --cached --name-only
    )
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect local Git changes.' }
    return @($paths | Where-Object { $_ } | Sort-Object -Unique)
}

if ($SelfTest) {
    $cases = @{
        'server.properties' = $true
        'MarketPlay-Pack-20260811-120000.zip' = $true
        'dialogue-resource-pack/pack.mcmeta' = $true
        'rpgmaker-web-editor/src/App.tsx' = $true
        'plugins/RPGMaker/config.yml' = $true
        'world/level.dat' = $false
        'logs/latest.log' = $false
        'plugins/Citizens/saves.yml' = $false
        'plugins/RPGMaker/backups/config.yml.bak' = $false
    }
    foreach ($case in $cases.GetEnumerator()) {
        if ((Test-SyncPath $case.Key) -ne $case.Value) { throw "Sync path rule failed: $($case.Key)" }
    }
    Write-Host 'Automatic merge path rules passed.'
    exit 0
}

Push-Location $RepoPath
try {
    $branch = (& git branch --show-current).Trim()
    if ($branch -ne 'main') { throw "Automatic merge requires main. Current branch: $branch" }
    if ($CheckClean) {
        $existing = @(Get-ChangedPaths | Where-Object { Test-SyncPath $_ })
        if ($existing) { throw "Automatic sync targets are already changed: $($existing -join ', ')" }
        Write-Host 'Automatic sync targets are clean.'
        exit 0
    }
    if (& git diff --cached --name-only) { throw 'Automatic merge refused because the index already contains staged changes.' }

    $paths = @(Get-ChangedPaths | Where-Object { Test-SyncPath $_ })
    if (-not $paths) {
        Write-Host 'No approved server, web, plugin, or resource-pack changes to merge.'
        exit 0
    }

    $syncBranch = 'codex/server-sync-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
    $baseCommit = (& git rev-parse HEAD).Trim()
    Invoke-Git @('switch', '-c', $syncBranch)
    Invoke-Git (@('add', '-A', '--') + $paths)
    Invoke-Git (@('commit', '--only', '-m', 'Automatic server shutdown sync', '--') + $paths)

    Invoke-Git @('fetch', 'origin', 'main')
    $remote = (& git rev-parse origin/main).Trim()
    if ($baseCommit -ne $remote) { throw "origin/main changed. Local changes are preserved on $syncBranch." }
    Invoke-Git @('push', '-u', 'origin', $syncBranch)

    $body = "Approved changes after a clean server shutdown.`n`n- Sync web and plugin sources`n- Sync the new resource pack and URL`n- Exclude worlds, logs, and caches"
    $prUrl = (& gh pr create --repo 'Melona500/market-play' --base main --head $syncBranch --title 'Automatic server shutdown sync' --body $body).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $prUrl) { throw 'Pull request creation failed. The pushed branch was preserved.' }

    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        $mergeable = (& gh pr view $prUrl --repo 'Melona500/market-play' --json mergeable --jq '.mergeable').Trim()
        if ($mergeable -eq 'MERGEABLE') { break }
        if ($mergeable -eq 'CONFLICTING') { throw "Pull request conflicts; preserved for manual review: $prUrl" }
        Start-Sleep -Seconds 2
    }
    if ($mergeable -ne 'MERGEABLE') { throw "GitHub did not finish mergeability checks; preserved: $prUrl" }

    & gh pr merge $prUrl --repo 'Melona500/market-play' --squash
    if ($LASTEXITCODE -ne 0) { throw "Pull request was preserved for manual review: $prUrl" }

    $prState = (& gh pr view $prUrl --repo 'Melona500/market-play' --json state --jq '.state').Trim()
    if ($LASTEXITCODE -ne 0 -or $prState -ne 'MERGED') {
        Write-Host "Automatic merge is queued; local changes remain on $syncBranch`: $prUrl"
        return
    }

    Invoke-Git @('switch', 'main')
    Invoke-Git @('pull', '--ff-only', 'origin', 'main')
    Write-Host "Merged: $prUrl"
}
finally { Pop-Location }
