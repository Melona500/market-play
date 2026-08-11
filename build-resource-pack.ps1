param([Parameter(Mandatory = $true)][string]$JdkHome)

$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'dialogue-resource-pack'
$jar = Join-Path $JdkHome 'bin\jar.exe'
$name = 'MarketPlay-Pack-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.zip'
$output = Join-Path $PSScriptRoot $name

if (-not (Test-Path -LiteralPath $jar)) { throw "jar.exe not found: $jar" }
& $jar --create --file $output -C $source .
if ($LASTEXITCODE -ne 0) { throw 'Resource-pack build failed.' }

$sha1 = (Get-FileHash -LiteralPath $output -Algorithm SHA1).Hash.ToLowerInvariant()
$propertiesPath = Join-Path $PSScriptRoot 'server.properties'
$properties = Get-Content -LiteralPath $propertiesPath -Raw -Encoding UTF8
$currentUrl = [regex]::Match($properties, '(?m)^resource-pack=(.+)$').Groups[1].Value
$baseUrl = if ($currentUrl.Contains('/')) { $currentUrl.Substring(0, $currentUrl.LastIndexOf('/')) } else { '' }
if ($baseUrl) { $properties = $properties -replace '(?m)^resource-pack=.*$', "resource-pack=$baseUrl/$name" }
$properties = $properties -replace '(?m)^resource-pack-id=.*$', "resource-pack-id=$([guid]::NewGuid())"
$properties = $properties -replace '(?m)^resource-pack-sha1=.*$', "resource-pack-sha1=$sha1"
[IO.File]::WriteAllText($propertiesPath, $properties, (New-Object Text.UTF8Encoding($false)))

$configPath = Join-Path $PSScriptRoot 'plugins\RPGMaker\config.yml'
$config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8
$config = $config -replace '(?m)^pack-file:.*$', "pack-file: $name"
[IO.File]::WriteAllText($configPath, $config, (New-Object Text.UTF8Encoding($false)))

Write-Host "Built $name"
Write-Host "SHA1 $sha1"
