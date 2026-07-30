& (Join-Path $PSScriptRoot "build-production.ps1") -Artifact bundle
exit $LASTEXITCODE
