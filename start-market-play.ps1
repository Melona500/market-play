param([switch]$BuildOnly)

$ErrorActionPreference = 'Stop'
$utf8NoBom = [Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom

# Resolve the repository root from this script's own location.
$RepoPath          = $PSScriptRoot
$WebPath           = Join-Path $RepoPath 'rpgmaker-web-editor'
$ServerPath        = $RepoPath
$PluginProjectPath = Join-Path $RepoPath 'dialogue-display-plugin'
$PluginJar         = Join-Path $ServerPath 'plugins\RPGMaker.jar'
$MarketPlayProjectPath = Join-Path $RepoPath 'marketplay-plugin'
$MarketPlayJar     = Join-Path $ServerPath 'plugins\MarketPlay.jar'
$PackBuildScript   = Join-Path $RepoPath 'build-resource-pack.ps1'

$GradleVersion = '9.1.0'
$GradleSha256  = 'a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806'
$ToolsPath     = Join-Path $RepoPath '.tools'
$GradleHome    = Join-Path $ToolsPath "gradle-$GradleVersion"
$GradleBat     = Join-Path $GradleHome 'bin\gradle.bat'
$GradleZip     = Join-Path $ToolsPath "gradle-$GradleVersion-bin.zip"
$GradleUrl     = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"

$JdkMajor      = '21'
$JdkHome       = Join-Path $ToolsPath 'temurin-jdk-21'
$JdkZip        = Join-Path $ToolsPath 'temurin-jdk-21.zip'
$JdkChecksum   = Join-Path $ToolsPath 'temurin-jdk-21.zip.sha256.txt'
$JdkExtract    = Join-Path $ToolsPath '.temurin-jdk-21-extract'
$JdkApiUrl     = "https://api.adoptium.net/v3/binary/latest/$JdkMajor/ga/windows/x64/jdk/hotspot/normal/eclipse"
$BuildLog      = Join-Path $ToolsPath 'rpgmaker-gradle-build.log'
$ServerJava    = (Get-Command java.exe -ErrorAction SilentlyContinue).Source

$webProcess = $null
$tunnelProcess = $null
$serverExitCode = $null

function Assert-ServerJava {
    if (-not $ServerJava) {
        throw 'Java 25 is required to run the installed WorldEdit plugins, but java.exe was not found in PATH.'
    }
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $version = (& $ServerJava -version 2>&1 | Out-String) }
    finally { $ErrorActionPreference = $previousErrorAction }
    if ($LASTEXITCODE -ne 0 -or $version -notmatch 'version "25(?:\.|\")') {
        throw "The installed WorldEdit plugins require Java 25. java.exe: $ServerJava"
    }
}

function Assert-PortsAvailable {
    $busy = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in @(25565, 25566, 25567, 5173) } |
        Select-Object -ExpandProperty LocalPort -Unique)
    if ($busy) {
        throw "Existing server or web service is using required port(s): $($busy -join ', '). Stop it first. No process was terminated."
    }
    $oldTunnel = Get-CimInstance Win32_Process -Filter "Name='cloudflared.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match 'tunnel\s+--url\s+http://127\.0\.0\.1:25566' }
    if ($oldTunnel) {
        throw 'An existing resource-pack tunnel targets port 25566. Stop its owning server first. No process was terminated.'
    }
}

function Ensure-Tls12 {
    if ([Net.ServicePointManager]::SecurityProtocol -band [Net.SecurityProtocolType]::Tls12) {
        return
    }
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
}

function Get-RedirectLocation([string]$Uri) {
    $location = $null
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -MaximumRedirection 0 -ErrorAction Stop
        $location = $response.Headers['Location']
    }
    catch {
        $response = $_.Exception.Response
        if ($null -ne $response) {
            $location = $response.Headers['Location']
        }
        if (-not $location) {
            throw
        }
    }

    if ($location -is [System.Array]) {
        $location = $location[0]
    }
    if (-not $location) {
        throw "Download service did not return a redirect URL: $Uri"
    }
    return [string]$location
}

function Ensure-Gradle {
    if (Test-Path -LiteralPath $GradleBat) {
        return
    }

    New-Item -ItemType Directory -Force -Path $ToolsPath | Out-Null
    Ensure-Tls12

    if (-not (Test-Path -LiteralPath $GradleZip)) {
        Write-Host "Gradle $GradleVersion is not cached. Downloading it once..."
        $previousProgressPreference = $ProgressPreference
        try {
            $ProgressPreference = 'SilentlyContinue'
            Invoke-WebRequest -UseBasicParsing -Uri $GradleUrl -OutFile $GradleZip
        }
        finally {
            $ProgressPreference = $previousProgressPreference
        }
    }

    $actualHash = (Get-FileHash -LiteralPath $GradleZip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $GradleSha256) {
        Remove-Item -LiteralPath $GradleZip -Force -ErrorAction SilentlyContinue
        throw "Gradle download checksum mismatch. Expected $GradleSha256 but got $actualHash."
    }

    Remove-Item -LiteralPath $GradleHome -Recurse -Force -ErrorAction SilentlyContinue
    Expand-Archive -LiteralPath $GradleZip -DestinationPath $ToolsPath -Force
    Remove-Item -LiteralPath $GradleZip -Force -ErrorAction SilentlyContinue

    if (-not (Test-Path -LiteralPath $GradleBat)) {
        throw "Gradle $GradleVersion was downloaded but gradle.bat was not found: $GradleBat"
    }
}

function Ensure-Jdk21 {
    $javac = Join-Path $JdkHome 'bin\javac.exe'
    $java = Join-Path $JdkHome 'bin\java.exe'
    if ((Test-Path -LiteralPath $javac) -and (Test-Path -LiteralPath $java)) {
        return
    }

    New-Item -ItemType Directory -Force -Path $ToolsPath | Out-Null
    Ensure-Tls12

    Write-Host 'Temurin JDK 21 is not cached. Downloading it once...'
    $downloadUrl = Get-RedirectLocation $JdkApiUrl
    $checksumUrl = $downloadUrl + '.sha256.txt'

    Remove-Item -LiteralPath $JdkZip -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $JdkChecksum -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $JdkExtract -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $JdkHome -Recurse -Force -ErrorAction SilentlyContinue

    $previousProgressPreference = $ProgressPreference
    try {
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -UseBasicParsing -Uri $downloadUrl -OutFile $JdkZip
        Invoke-WebRequest -UseBasicParsing -Uri $checksumUrl -OutFile $JdkChecksum
    }
    finally {
        $ProgressPreference = $previousProgressPreference
    }

    $checksumText = Get-Content -LiteralPath $JdkChecksum -Raw
    $expectedHash = (($checksumText.Trim() -split '\s+')[0]).ToLowerInvariant()
    $actualHash = (Get-FileHash -LiteralPath $JdkZip -Algorithm SHA256).Hash.ToLowerInvariant()
    Remove-Item -LiteralPath $JdkChecksum -Force -ErrorAction SilentlyContinue
    if (-not $expectedHash -or $actualHash -ne $expectedHash) {
        Remove-Item -LiteralPath $JdkZip -Force -ErrorAction SilentlyContinue
        throw "Temurin JDK 21 checksum mismatch. Expected $expectedHash but got $actualHash."
    }

    New-Item -ItemType Directory -Force -Path $JdkExtract | Out-Null
    Expand-Archive -LiteralPath $JdkZip -DestinationPath $JdkExtract -Force
    Remove-Item -LiteralPath $JdkZip -Force -ErrorAction SilentlyContinue

    $javacFile = Get-ChildItem -LiteralPath $JdkExtract -Filter 'javac.exe' -File -Recurse |
        Where-Object { $_.Directory.Name -eq 'bin' } |
        Select-Object -First 1
    if ($null -eq $javacFile) {
        Remove-Item -LiteralPath $JdkExtract -Recurse -Force -ErrorAction SilentlyContinue
        throw 'Temurin JDK 21 archive did not contain bin\javac.exe.'
    }

    $candidateHome = Split-Path -Parent (Split-Path -Parent $javacFile.FullName)
    Move-Item -LiteralPath $candidateHome -Destination $JdkHome
    Remove-Item -LiteralPath $JdkExtract -Recurse -Force -ErrorAction SilentlyContinue

    if (-not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) {
        throw "Temurin JDK 21 was extracted but is incomplete: $JdkHome"
    }
}

function Test-PluginBuildRequired([string]$ProjectPath, [string]$JarPath) {
    if (-not (Test-Path -LiteralPath $JarPath)) {
        return $true
    }

    $jarTime = (Get-Item -LiteralPath $JarPath).LastWriteTimeUtc
    $inputs = @(
        Get-ChildItem -LiteralPath (Join-Path $ProjectPath 'src') -File -Recurse
        Get-ChildItem -LiteralPath $ProjectPath -File |
            Where-Object { $_.Name -in @('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties') }
    )
    return $null -ne ($inputs | Where-Object { $_.LastWriteTimeUtc -gt $jarTime } | Select-Object -First 1)
}

function Test-ResourcePackBuildRequired {
    $config = Get-Content -LiteralPath (Join-Path $ServerPath 'plugins\RPGMaker\config.yml') -Raw -Encoding UTF8
    $packName = [regex]::Match($config, '(?m)^pack-file:\s*(.+)$').Groups[1].Value.Trim()
    if (-not $packName.StartsWith('MarketPlay-Pack-')) { return $true }
    $pack = Join-Path $ServerPath $packName
    if (-not (Test-Path -LiteralPath $pack)) { return $true }
    $packTime = (Get-Item -LiteralPath $pack).LastWriteTimeUtc
    return $null -ne (Get-ChildItem -LiteralPath (Join-Path $RepoPath 'dialogue-resource-pack') -File -Recurse |
        Where-Object { $_.LastWriteTimeUtc -gt $packTime } | Select-Object -First 1)
}

function Build-WebEditor {
    if (-not (Test-Path -LiteralPath (Join-Path $WebPath 'node_modules'))) {
        & npm.cmd ci --prefix $WebPath
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed with exit code $LASTEXITCODE" }
    }
    & npm.cmd run build --prefix $WebPath
    if ($LASTEXITCODE -ne 0) { throw "Web editor build failed with exit code $LASTEXITCODE" }
}

function Start-ResourcePackTunnel {
    $cloudflared = (Get-Command cloudflared.exe -ErrorAction Stop).Source
    $log = Join-Path $RepoPath 'logs\resource-pack-tunnel.log'
    New-Item -ItemType Directory -Path (Split-Path $log) -Force | Out-Null
    Set-Content -LiteralPath $log -Value '' -Encoding UTF8
    $script:tunnelProcess = Start-Process -FilePath $cloudflared -ArgumentList @(
        '--no-autoupdate', 'tunnel', '--url', 'http://127.0.0.1:25566',
        '--logfile', $log, '--loglevel', 'info'
    ) -WorkingDirectory $RepoPath -WindowStyle Hidden -PassThru

    $publicBase = $null
    for ($i = 0; $i -lt 60 -and -not $publicBase; $i++) {
        Start-Sleep -Milliseconds 500
        $match = Select-String -LiteralPath $log -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' -AllMatches -ErrorAction SilentlyContinue
        if ($match) { $publicBase = $match.Matches[-1].Value }
    }
    if (-not $publicBase) { throw 'Market Play resource-pack tunnel did not start.' }

    $config = Get-Content -LiteralPath (Join-Path $ServerPath 'plugins\RPGMaker\config.yml') -Raw -Encoding UTF8
    $packName = [regex]::Match($config, '(?m)^pack-file:\s*(.+)$').Groups[1].Value.Trim()
    $propertiesPath = Join-Path $ServerPath 'server.properties'
    $properties = Get-Content -LiteralPath $propertiesPath -Raw -Encoding UTF8
    $packUrl = "$publicBase/$packName" -replace ':', '\:'
    $properties = $properties -replace '(?m)^resource-pack=.*$', "resource-pack=$packUrl"
    [IO.File]::WriteAllText($propertiesPath, $properties, (New-Object Text.UTF8Encoding($false)))
    Write-Host "Resource pack: $publicBase/$packName"
}

function Deploy-Plugin([string]$Name, [string]$ProjectPath, [string]$JarPath) {
    Write-Host ''
    Write-Host '========================================'
    Write-Host " Building $Name Plugin"
    Write-Host '========================================'

    Ensure-Gradle
    Ensure-Jdk21

    $previousJavaHome = $env:JAVA_HOME
    $previousPath = $env:Path
    try {
        # Use one complete JDK for both Gradle itself and Java compilation.
        # This avoids depending on the server's runtime-only JRE or Gradle toolchain auto-provisioning.
        $env:JAVA_HOME = $JdkHome
        $env:Path = (Join-Path $JdkHome 'bin') + ';' + $previousPath

        $junctionRoot = Join-Path $env:LOCALAPPDATA 'MarketPlayBuild'
        $junctionPath = Join-Path $junctionRoot 'repo'
        New-Item -ItemType Directory -Force -Path $junctionRoot | Out-Null
        if (-not (Test-Path -LiteralPath $junctionPath)) {
            New-Item -ItemType Junction -Path $junctionPath -Target $RepoPath | Out-Null
        }
        if (-not ((Get-Item -LiteralPath $junctionPath).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Gradle build path exists but is not a junction: $junctionPath"
        }
        $buildProject = Join-Path $junctionPath (Split-Path -Leaf $ProjectPath)
        $buildLog = Join-Path $ToolsPath ($Name.ToLowerInvariant() + '-gradle-build.log')
        $projectCache = Join-Path $junctionRoot ($Name.ToLowerInvariant() + '-gradle-cache')
        Remove-Item -LiteralPath $buildLog -Force -ErrorAction SilentlyContinue
        & $GradleBat -p $buildProject --project-cache-dir $projectCache deployToServer --no-daemon --console=plain --stacktrace 2>&1 |
            Tee-Object -FilePath $buildLog
        $gradleExitCode = $LASTEXITCODE
        if ($gradleExitCode -ne 0) {
            throw "$Name plugin deployment failed with exit code $gradleExitCode. Full Gradle log: $buildLog"
        }
    }
    finally {
        if ($null -eq $previousJavaHome) {
            Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
        }
        else {
            $env:JAVA_HOME = $previousJavaHome
        }
        $env:Path = $previousPath
    }

    if (-not (Test-Path -LiteralPath $JarPath)) {
        throw "$Name plugin JAR was not created: $JarPath"
    }

    Write-Host "$Name plugin deployed."
}

try {
    if (-not $BuildOnly) { Assert-ServerJava }
    if (-not $BuildOnly) { Assert-PortsAvailable }

    if (-not (Test-Path -LiteralPath $PluginProjectPath)) {
        throw "RPGMaker plugin project not found: $PluginProjectPath"
    }
    if (-not (Test-Path -LiteralPath $MarketPlayProjectPath)) {
        throw "MarketPlay plugin project not found: $MarketPlayProjectPath"
    }
    if (-not (Test-Path -LiteralPath $WebPath)) {
        throw "Web editor directory not found: $WebPath"
    }

    if ($BuildOnly -or (Test-PluginBuildRequired $PluginProjectPath $PluginJar)) {
        Deploy-Plugin 'RPGMaker' $PluginProjectPath $PluginJar
    }
    else {
        Write-Host 'RPGMaker plugin unchanged. Skipping build.'
    }
    if ($BuildOnly -or (Test-PluginBuildRequired $MarketPlayProjectPath $MarketPlayJar)) {
        Deploy-Plugin 'MarketPlay' $MarketPlayProjectPath $MarketPlayJar
    }
    else {
        Write-Host 'MarketPlay plugin unchanged. Skipping build.'
    }

    Ensure-Jdk21
    if ($BuildOnly -or (Test-ResourcePackBuildRequired)) {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $PackBuildScript -JdkHome $JdkHome
        if ($LASTEXITCODE -ne 0) { throw "Resource-pack build failed with exit code $LASTEXITCODE" }
    }
    else {
        Write-Host 'Market Play resource pack unchanged. Skipping build.'
    }

    Build-WebEditor

    if ($BuildOnly) {
        Write-Host 'Build-only validation completed.'
        return
    }

    Start-ResourcePackTunnel

    Write-Host ''
    Write-Host '========================================'
    Write-Host ' Starting RPGMaker Web Editor'
    Write-Host '========================================'

    $webProcess = Start-Process `
        -FilePath 'cmd.exe' `
        -ArgumentList '/d', '/c', 'npm run dev' `
        -WorkingDirectory $WebPath `
        -WindowStyle Hidden `
        -PassThru

    Write-Host "Web editor started. PID: $($webProcess.Id)"
    Write-Host ''
    Write-Host '========================================'
    Write-Host ' Starting Minecraft Server'
    Write-Host '========================================'

    Push-Location $ServerPath
    try {
        # Keep normal console `stop` available, but reject external Attach API
        # injections that can terminate Paper without an operator command.
        & $ServerJava '-XX:+DisableAttachMechanism' '-Xms2G' '-Xmx2G' '-Dfile.encoding=UTF-8' '-Dstdin.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-jar' 'paper.jar' '--nogui'
        $serverExitCode = $LASTEXITCODE
    }
    finally { Pop-Location }
}
finally {
    if (-not $BuildOnly) {
        Write-Host ''
        Write-Host '========================================'
        Write-Host ' Stopping RPGMaker Web Editor'
        Write-Host '========================================'

        if ($webProcess -and -not $webProcess.HasExited) {
            & taskkill.exe /PID $webProcess.Id /T /F | Out-Null
        }

        if ($tunnelProcess -and -not $tunnelProcess.HasExited) {
            Stop-Process -Id $tunnelProcess.Id -Force
        }

        Write-Host 'Web editor stopped.'

    }
}

Write-Host 'Done.'

if ($null -ne $serverExitCode -and $serverExitCode -ne 0) {
    exit $serverExitCode
}
