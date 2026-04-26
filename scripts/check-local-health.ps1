param(
  [int]$TimeoutSeconds = 180,
  [int]$PollSeconds = 3
)

$ErrorActionPreference = "Stop"

if ($TimeoutSeconds -lt 1) {
  throw "TimeoutSeconds must be at least 1."
}
if ($PollSeconds -lt 1) {
  throw "PollSeconds must be at least 1."
}

function Test-TcpPort {
  param(
    [string]$HostName,
    [int]$Port,
    [int]$TimeoutMs = 1500
  )

  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $async = $client.BeginConnect($HostName, $Port, $null, $null)
    $completed = $async.AsyncWaitHandle.WaitOne($TimeoutMs)
    if (-not $completed) {
      return $false
    }
    $client.EndConnect($async)
    return $true
  } catch {
    return $false
  } finally {
    $client.Close()
  }
}

function Test-HttpEndpoint {
  param(
    [string]$Url
  )

  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -Method Get -TimeoutSec 4
    return ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400)
  } catch {
    return $false
  }
}

$checks = @(
  @{ Name = "Postgres"; Kind = "tcp"; Host = "localhost"; Port = 5432 },
  @{ Name = "Backend"; Kind = "http"; Url = "http://localhost:8080/health" },
  @{ Name = "Frontend"; Kind = "http"; Url = "http://localhost:8081" },
  @{ Name = "Grafana"; Kind = "http"; Url = "http://localhost:3000/api/health" },
  @{ Name = "Prometheus"; Kind = "http"; Url = "http://localhost:9090/-/ready" },
  @{ Name = "Loki"; Kind = "http"; Url = "http://localhost:3100/ready" },
  @{ Name = "Tempo"; Kind = "http"; Url = "http://localhost:3200/ready" }
)

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$results = @()

while ((Get-Date) -lt $deadline) {
  $results = foreach ($check in $checks) {
    $ok = if ($check.Kind -eq "tcp") {
      Test-TcpPort -HostName $check.Host -Port $check.Port
    } else {
      Test-HttpEndpoint -Url $check.Url
    }

    [PSCustomObject]@{
      Service = $check.Name
      Ready = $ok
      Probe = if ($check.Kind -eq "tcp") { "${($check.Host)}:${($check.Port)}" } else { $check.Url }
    }
  }

  if (($results | Where-Object { -not $_.Ready }).Count -eq 0) {
    Write-Host "All services are ready."
    $results | Format-Table -AutoSize
    exit 0
  }

  Start-Sleep -Seconds $PollSeconds
}

Write-Error "Timed out waiting for local services to become ready."
$results | Format-Table -AutoSize
exit 1
