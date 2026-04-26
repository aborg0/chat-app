param(
  [int]$FrontendPort = 8081,
  [switch]$NoObservability
)

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

$otelDir = Join-Path $projectRoot ".otel"
$otelAgentPath = Join-Path $otelDir "opentelemetry-javaagent.jar"
$otelAgentVersion = "2.10.0"
$otelAgentUrl = "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v$otelAgentVersion/opentelemetry-javaagent.jar"

if (-not $NoObservability) {
  if (Get-Command podman -ErrorAction SilentlyContinue) {
    $compose = "podman"
    $composeArgs = @("compose", "up", "-d", "postgres", "otel-collector", "prometheus", "loki", "tempo", "grafana")
  } elseif (Get-Command podman-compose -ErrorAction SilentlyContinue) {
    $compose = "podman-compose"
    $composeArgs = @("up", "-d", "postgres", "otel-collector", "prometheus", "loki", "tempo", "grafana")
  } elseif (Get-Command docker -ErrorAction SilentlyContinue) {
    $compose = "docker"
    $composeArgs = @("compose", "up", "-d", "postgres", "otel-collector", "prometheus", "loki", "tempo", "grafana")
  } else {
    $compose = $null
    $composeArgs = @()
  }

  if ($compose) {
    Write-Host "Starting local observability stack (Postgres + OTel + Grafana)..."
    Set-Location $projectRoot
    & $compose @composeArgs | Out-Null
  } else {
    Write-Warning "Neither podman compose, podman-compose, nor docker compose is available. Skipping observability stack startup."
  }

  if (-not (Test-Path $otelAgentPath)) {
    New-Item -ItemType Directory -Force -Path $otelDir | Out-Null
    Write-Host "Downloading OpenTelemetry Java agent v$otelAgentVersion..."
    Invoke-WebRequest -Uri $otelAgentUrl -OutFile $otelAgentPath
  }
}

& (Join-Path $PSScriptRoot "build-frontend.ps1")

$pythonCmd = if (Get-Command python -ErrorAction SilentlyContinue) {
  "python"
} elseif (Get-Command py -ErrorAction SilentlyContinue) {
  "py"
} else {
  throw "Neither 'python' nor 'py' is available on PATH."
}

$backendEnvPrefix = if ($NoObservability) {
  ""
} else {
  "$env:JAVA_TOOL_OPTIONS='-javaagent:$otelAgentPath'; " +
  "$env:OTEL_SERVICE_NAME='chat-app-backend'; " +
  "$env:OTEL_EXPORTER_OTLP_PROTOCOL='http/protobuf'; " +
  "$env:OTEL_EXPORTER_OTLP_ENDPOINT='http://localhost:4318'; " +
  "$env:OTEL_TRACES_EXPORTER='otlp'; " +
  "$env:OTEL_METRICS_EXPORTER='otlp'; " +
  "$env:OTEL_LOGS_EXPORTER='otlp'; " +
  "$env:OTEL_RESOURCE_ATTRIBUTES='deployment.environment=local,service.namespace=chat-app'; "
}

$backendCommand = "Set-Location '$projectRoot'; $backendEnvPrefix sbt '-Dsbt.color=false' '-Dsbt.log.noformat=true' '-Dsbt.supershell=false' 'backend/run'"
$frontendCommand = "Set-Location '$projectRoot/frontend/resources'; $pythonCmd -m http.server $FrontendPort"

Start-Process pwsh -ArgumentList "-NoExit", "-Command", $backendCommand | Out-Null
Start-Process pwsh -ArgumentList "-NoExit", "-Command", $frontendCommand | Out-Null

Write-Host "Started backend in a new terminal on http://localhost:8080"
Write-Host "Started frontend static server in a new terminal on http://localhost:$FrontendPort"
Write-Host "Make sure DB settings in backend/resources/application.conf point to a running PostgreSQL instance."
if (-not $NoObservability) {
  Write-Host "Grafana: http://localhost:3000 (admin/admin)"
  Write-Host "Prometheus: http://localhost:9090"
  Write-Host "Tempo: http://localhost:3200"
  Write-Host "Loki: http://localhost:3100"
}
