$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $projectRoot

Write-Host "Building frontend bundle with sbt frontend/fastLinkJS..."
sbt "-Dsbt.color=false" "-Dsbt.log.noformat=true" "-Dsbt.supershell=false" "frontend/fastLinkJS"

$targetRoot = Join-Path $projectRoot "frontend/target"
$resourceJs = Join-Path $projectRoot "frontend/resources/frontend.js"
$resourceMap = Join-Path $projectRoot "frontend/resources/main.js.map"
$mainBundle = Get-ChildItem -Path $targetRoot -Recurse -File -Filter "main.js" -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -match "frontend-fastopt" } |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1

if (-not $mainBundle) {
  throw "Could not find frontend-fastopt/main.js under frontend/target"
}

Copy-Item -Path $mainBundle.FullName -Destination $resourceJs -Force

$sourceMapPath = "$($mainBundle.FullName).map"
if (Test-Path $sourceMapPath) {
  Copy-Item -Path $sourceMapPath -Destination $resourceMap -Force
}

Write-Host "Frontend bundle copied to $resourceJs"
