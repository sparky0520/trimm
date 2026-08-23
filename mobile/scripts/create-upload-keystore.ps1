$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$keyTool = Join-Path $env:JAVA_HOME "bin\keytool.exe"
$keyStoreDirectory = Join-Path $repositoryRoot "keystore"
$keyStorePath = Join-Path $keyStoreDirectory "ctrlb-upload.jks"

if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath $keyTool)) {
    throw "JAVA_HOME must point to a JDK 17 installation."
}

if (Test-Path -LiteralPath $keyStorePath) {
    throw "A keystore already exists at $keyStorePath. It was not overwritten."
}

New-Item -ItemType Directory -Path $keyStoreDirectory -Force | Out-Null

Write-Host "Creating the ctrlB Android upload key."
Write-Host "Choose a strong password and store it in a password manager."
Write-Host "When prompted for the key password, press Enter to reuse the keystore password."

& $keyTool -genkeypair `
    -keystore $keyStorePath `
    -alias "ctrlb-upload" `
    -keyalg RSA `
    -keysize 2048 `
    -validity 10000

if ($LASTEXITCODE -ne 0) {
    throw "keytool failed with exit code $LASTEXITCODE."
}

Write-Host ""
Write-Host "Upload keystore created at: $keyStorePath"
Write-Host "Add these values to .env.local:"
Write-Host "CTRLB_ANDROID_UPLOAD_STORE_FILE=keystore/ctrlb-upload.jks"
Write-Host "CTRLB_ANDROID_UPLOAD_STORE_PASSWORD=<the password you entered>"
Write-Host "CTRLB_ANDROID_UPLOAD_KEY_ALIAS=ctrlb-upload"
Write-Host "CTRLB_ANDROID_UPLOAD_KEY_PASSWORD=<the same password, if you pressed Enter>"
Write-Host ""
Write-Host "Back up the keystore and its passwords securely. Do not commit the keystore."
