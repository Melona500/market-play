param(
    [Parameter(Mandatory = $true)]
    [string]$RepoPath
)

$ErrorActionPreference = 'Stop'
$webPath = [IO.Path]::GetFullPath((Join-Path $RepoPath 'rpgmaker-web-editor'))

$listeners = @(Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue)
$ownerPids = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)

foreach ($ownerPid in $ownerPids) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $ownerPid" -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        continue
    }

    $name = [string]$process.Name
    $commandLine = [string]$process.CommandLine
    $belongsToWebEditor = $name -ieq 'node.exe' -and (
        $commandLine.IndexOf($webPath, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -or
        $commandLine -match '(?i)rpgmaker-web-editor[\\/].*node_modules[\\/].*vite'
    )

    if (-not $belongsToWebEditor) {
        continue
    }

    Write-Host "Stopping stale RPGMaker web editor process on port 5173. PID: $ownerPid"
    & taskkill.exe /PID $ownerPid /T /F | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to stop stale RPGMaker web editor process PID $ownerPid."
    }
}

if ($ownerPids.Count -gt 0) {
    Start-Sleep -Milliseconds 300
}
