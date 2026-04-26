param(
  [switch]$RemoveVolumes
)

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $projectRoot

if (Get-Command podman -ErrorAction SilentlyContinue) {
  $compose = "podman"
  $composeArgs = @("compose", "down")
} elseif (Get-Command podman-compose -ErrorAction SilentlyContinue) {
  $compose = "podman-compose"
  $composeArgs = @("down")
} elseif (Get-Command docker -ErrorAction SilentlyContinue) {
  $compose = "docker"
  $composeArgs = @("compose", "down")
} else {
  throw "Neither podman compose, podman-compose, nor docker compose is available."
}

if ($RemoveVolumes) {
  $composeArgs += "-v"
}

Write-Host "Stopping local stack with $compose $($composeArgs -join ' ')"
& $compose @composeArgs
Write-Host "Local stack stopped."
