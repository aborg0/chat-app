param(
  [string]$ImageTag = "localhost/chat-app-backend:latest",
  [switch]$ForceRebuild
)

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $projectRoot

function Get-LatestWriteUtc {
  param([string[]]$Paths)

  $latest = [datetime]::MinValue
  foreach ($path in $Paths) {
    if (Test-Path $path) {
      $candidate = Get-ChildItem -Path $path -Recurse -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
      if ($candidate -and $candidate.LastWriteTimeUtc -gt $latest) {
        $latest = $candidate.LastWriteTimeUtc
      }
    }
  }
  return $latest
}

$frontendBundle = Join-Path $projectRoot "frontend/resources/frontend.js"
$frontendLatestSource = Get-LatestWriteUtc @(
  (Join-Path $projectRoot "frontend/src/main/scala"),
  (Join-Path $projectRoot "shared/src/main/scala"),
  (Join-Path $projectRoot "frontend/resources/index.html"),
  (Join-Path $projectRoot "frontend/resources/otel.js"),
  (Join-Path $projectRoot "frontend/resources/styles.css"),
  (Join-Path $projectRoot "scripts/build-frontend.ps1")
)

$shouldBuildFrontend = $ForceRebuild -or -not (Test-Path $frontendBundle)
if (-not $shouldBuildFrontend) {
  $bundleWrite = (Get-Item $frontendBundle).LastWriteTimeUtc
  $shouldBuildFrontend = $bundleWrite -lt $frontendLatestSource
}

if ($shouldBuildFrontend) {
  & (Join-Path $PSScriptRoot "build-frontend.ps1")
} else {
  Write-Host "Skipping frontend bundle rebuild (no source changes detected)."
}

$backendLatestSource = Get-LatestWriteUtc @(
  (Join-Path $projectRoot "backend/src/main/scala"),
  (Join-Path $projectRoot "backend/resources"),
  (Join-Path $projectRoot "shared/src/main/scala"),
  (Join-Path $projectRoot "project/plugins.sbt"),
  (Join-Path $projectRoot "build.sbt"),
  (Join-Path $projectRoot "scripts/build-backend-image.ps1")
)

$imageExists = $false
if (Get-Command podman -ErrorAction SilentlyContinue) {
  $engine = "podman"
  & podman image exists $ImageTag *> $null
  $imageExists = ($LASTEXITCODE -eq 0)
} elseif (Get-Command docker -ErrorAction SilentlyContinue) {
  $engine = "docker"
  & docker image inspect $ImageTag *> $null
  $imageExists = ($LASTEXITCODE -eq 0)
} else {
  throw "Neither podman nor docker is available on PATH."
}

$shouldBuildBackendImage = $ForceRebuild -or -not $imageExists
if (-not $shouldBuildBackendImage) {
  $imageCreatedRaw = (& $engine inspect -f "{{.Created}}" $ImageTag 2>$null)
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($imageCreatedRaw)) {
    $shouldBuildBackendImage = $true
  } else {
    try {
      $imageCreated = [DateTimeOffset]::Parse($imageCreatedRaw).UtcDateTime
      if ($imageCreated -lt $backendLatestSource) {
        $shouldBuildBackendImage = $true
      }
    } catch {
      $shouldBuildBackendImage = $true
    }
  }
}

if ($shouldBuildBackendImage) {
  & (Join-Path $PSScriptRoot "build-backend-image.ps1") -ImageTag $ImageTag
} else {
  Write-Host "Skipping backend image rebuild (image exists and no rebuild forced)."
}

if (Get-Command podman -ErrorAction SilentlyContinue) {
  $compose = "podman"
  $composeArgs = @("compose", "up", "-d")
} elseif (Get-Command podman-compose -ErrorAction SilentlyContinue) {
  $compose = "podman-compose"
  $composeArgs = @("up", "-d")
} elseif (Get-Command docker -ErrorAction SilentlyContinue) {
  $compose = "docker"
  $composeArgs = @("compose", "up", "-d")
} else {
  throw "Neither podman compose, podman-compose, nor docker compose is available."
}

Write-Host "Starting full stack with $compose $($composeArgs -join ' ')"
& $compose @composeArgs
if ($LASTEXITCODE -ne 0) {
  throw "Compose startup failed with exit code $LASTEXITCODE"
}

if (Get-Command podman -ErrorAction SilentlyContinue) {
  $engine = "podman"
} elseif (Get-Command docker -ErrorAction SilentlyContinue) {
  $engine = "docker"
} else {
  throw "Neither podman nor docker is available for post-start verification."
}

$requiredContainers = @(
  "chat-app-postgres",
  "chat-app-backend",
  "chat-app-frontend"
)

$failedContainers = @()
foreach ($container in $requiredContainers) {
  $state = (& $engine inspect -f "{{.State.Status}}" $container 2>$null)
  if ($LASTEXITCODE -ne 0 -or $state -ne "running") {
    $failedContainers += $container
  }
}

if ($failedContainers.Count -gt 0) {
  foreach ($container in $failedContainers) {
    Write-Host "--- Logs: $container ---"
    & $engine logs --tail 80 $container 2>$null
  }
  throw "Stack startup incomplete. These containers are not running: $($failedContainers -join ', ')"
}

Write-Host "Local stack is starting."
Write-Host "Frontend: http://localhost:8081"
Write-Host "Backend: http://localhost:8080"
Write-Host "Grafana: http://localhost:3000 (admin/admin)"
Write-Host "Prometheus: http://localhost:9090"
Write-Host "Tempo: http://localhost:3200"
Write-Host "Loki: http://localhost:3100"
