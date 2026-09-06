# Resolve and run the monnieSDK demo consumer (GitHub Packages or local SNAPSHOT).
#
#   .\scripts\Run-MonnieSdk.ps1
#   .\scripts\Run-MonnieSdk.ps1 --instructions "Guide a new user through their first budget."
#   .\scripts\Run-MonnieSdk.ps1 --live-api
#   .\scripts\Run-MonnieSdk.ps1 --confirm --action-id <id>
#
# Java does not read .env. This script loads it into the process environment first.

param(
    [switch]$Rebuild,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$DemoArgs
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -Path $root

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    Write-Host "No JDK found on PATH. Install JDK 17+." -ForegroundColor Red
    exit 1
}

$mvn = if (Get-Command mvn -ErrorAction SilentlyContinue) { 'mvn' } else { $null }
if (-not $mvn) {
    Write-Host "mvn is required to run the demo module." -ForegroundColor Red
    exit 1
}

function Import-DotEnv([string]$path) {
    if (-not (Test-Path $path)) { return }
    Get-Content $path | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
        $name, $value = $_ -split '=', 2
        if ($value.Trim()) { Set-Item -Path "Env:$($name.Trim())" -Value $value.Trim() }
    }
}

Import-DotEnv (Join-Path $root '.env')
Import-DotEnv (Join-Path $PSScriptRoot '.env')
$sdkRoot = Join-Path (Split-Path $root -Parent) 'monnieSDK'
Import-DotEnv (Join-Path $sdkRoot '.env')

if (-not ($env:GEMINI_API_KEY -or $env:GROQ_API_KEY -or $env:OPENAI_API_KEY) -and ($DemoArgs -notcontains '--live-api') -and ($DemoArgs -notcontains '--confirm')) {
    Write-Host "No model API key is set. Add GEMINI_API_KEY or GROQ_API_KEY to .env." -ForegroundColor Red
    exit 1
}

if ($Rebuild -or -not (Test-Path (Join-Path $sdkRoot 'monnie-sdk-core\target'))) {
    if (Test-Path (Join-Path $sdkRoot 'pom.xml')) {
        Write-Host "Installing local monnieSDK SNAPSHOT..." -ForegroundColor DarkGray
        & $mvn -B -q -f (Join-Path $sdkRoot 'pom.xml') install -DskipTests
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
}

$demoPom = Join-Path $PSScriptRoot 'monnie-sdk-demo\pom.xml'
Write-Host "Running monnie-sdk-demo..." -ForegroundColor DarkGray
if ($DemoArgs -and $DemoArgs.Count -gt 0) {
    & $mvn -q -f $demoPom exec:java "-Dexec.args=$($DemoArgs -join ' ')"
} else {
    & $mvn -q -f $demoPom exec:java
}
exit $LASTEXITCODE
