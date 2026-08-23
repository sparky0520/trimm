param(
    [ValidateSet("apk", "bundle")]
    [string]$Artifact = "apk"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$javaExecutable = Join-Path $env:JAVA_HOME "bin\java.exe"
$gradleWrapper = Join-Path $repositoryRoot "gradlew.bat"
$localEnvironmentFile = Join-Path $repositoryRoot ".env.local"

if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath $javaExecutable)) {
    throw "JAVA_HOME must point to a JDK 17 installation. Current value: '$env:JAVA_HOME'"
}

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$javaVersionOutput = & $javaExecutable -version 2>&1 | Out-String
$ErrorActionPreference = $previousErrorActionPreference
if ($javaVersionOutput -notmatch 'version "17(?:[."-])') {
    throw "ctrlB Mobile requires JDK 17. JAVA_HOME currently reports: $($javaVersionOutput.Trim())"
}

if (-not (Test-Path -LiteralPath $localEnvironmentFile)) {
    throw "Missing .env.local. Copy .env.local.example to .env.local and add the production configuration."
}

$localEnvironment = @{}
Get-Content -LiteralPath $localEnvironmentFile | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
        $localEnvironment[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$requiredSettings = @(
    "CTRLB_BACKEND_URL",
    "CTRLB_CLERK_PUBLISHABLE_KEY",
    "CTRLB_ANDROID_UPLOAD_STORE_FILE",
    "CTRLB_ANDROID_UPLOAD_STORE_PASSWORD",
    "CTRLB_ANDROID_UPLOAD_KEY_ALIAS",
    "CTRLB_ANDROID_UPLOAD_KEY_PASSWORD"
)

$missingSettings = $requiredSettings | Where-Object {
    -not $localEnvironment.ContainsKey($_) -or [string]::IsNullOrWhiteSpace($localEnvironment[$_])
}
if ($missingSettings) {
    throw "Missing production settings in .env.local: $($missingSettings -join ', ')"
}

$backendUri = $null
if (
    -not [Uri]::TryCreate($localEnvironment["CTRLB_BACKEND_URL"], [UriKind]::Absolute, [ref]$backendUri) -or
    $backendUri.Scheme -ne "https"
) {
    throw "CTRLB_BACKEND_URL must be an absolute HTTPS production origin."
}

$keyStorePath = Join-Path $repositoryRoot $localEnvironment["CTRLB_ANDROID_UPLOAD_STORE_FILE"]
if (-not (Test-Path -LiteralPath $keyStorePath)) {
    throw "The configured upload keystore does not exist: $keyStorePath"
}

# Ensure Gradle uses JAVA_HOME even if an obsolete Java executable appears earlier in PATH.
$env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
$gradleTask = if ($Artifact -eq "apk") { "assembleRelease" } else { "bundleRelease" }
$artifactPath = if ($Artifact -eq "apk") {
    "app\build\outputs\apk\release\app-release.apk"
} else {
    "app\build\outputs\bundle\release\app-release.aab"
}

Push-Location $repositoryRoot
try {
    & $gradleWrapper clean $gradleTask
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

Write-Host "Production artifact: $(Join-Path $repositoryRoot $artifactPath)"
