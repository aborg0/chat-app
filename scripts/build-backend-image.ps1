param(
  [string]$ImageTag = "localhost/chat-app-backend:latest"
)

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $projectRoot

if (Get-Command podman -ErrorAction SilentlyContinue) {
  $engine = "podman"
} elseif (Get-Command docker -ErrorAction SilentlyContinue) {
  $engine = "docker"
} else {
  throw "Neither 'podman' nor 'docker' is available on PATH."
}

$imageTar = Join-Path $projectRoot "backend/target/jib/chat-app-backend-image.tar"
$imageTarSbtPath = "backend/target/jib/chat-app-backend-image.tar"

if (Test-Path $imageTar) {
  Remove-Item $imageTar -Recurse -Force
}

Write-Host "Building backend container tar with sbt backend/jibJavaTarImageBuild..."
sbt "-Dsbt.color=false" "-Dsbt.log.noformat=true" "-Dsbt.supershell=false" "backend/jibJavaTarImageBuild $imageTarSbtPath"

if ($LASTEXITCODE -ne 0) {
  throw "Failed to build backend image tar with jibJavaTarImageBuild (exit code $LASTEXITCODE)."
}

if (-not (Test-Path $imageTar)) {
  throw "Expected Jib image tar was not found at $imageTar"
}

Write-Host "Loading backend image tar into $engine..."
& $engine load -i $imageTar

if ($LASTEXITCODE -ne 0) {
  throw "Failed to load backend image tar into $engine (exit code $LASTEXITCODE)."
}

# podman load may create the image under registry.hub.docker.com/localhost/...;
# ensure compose resolves the expected local tag.
$loadedAlias = "registry.hub.docker.com/localhost/chat-app-backend:latest"
if ($engine -eq "podman") {
  & podman image exists $loadedAlias *> $null
  if ($LASTEXITCODE -eq 0) {
    & podman tag $loadedAlias localhost/chat-app-backend:latest
  }
} else {
  & docker image inspect $loadedAlias *> $null
  if ($LASTEXITCODE -eq 0) {
    & docker tag $loadedAlias localhost/chat-app-backend:latest
  }
}

if ($ImageTag -ne "localhost/chat-app-backend:latest") {
  Write-Host "Retagging image to $ImageTag..."
  if ($engine -eq "podman") {
    & podman tag localhost/chat-app-backend:latest $ImageTag
  } else {
    & docker tag localhost/chat-app-backend:latest $ImageTag
  }
  
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to retag image (exit code $LASTEXITCODE)."
  }
}

Write-Host "Backend image ready: $ImageTag"
