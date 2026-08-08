# Print the last contiguous [flood-debug] block from the Loom runClient log.
# Usage (from repo root):
#   .\extract-flood-debug.ps1
#   .\extract-flood-debug.ps1 -Log run\logs\latest.log
param(
  [string]$Log = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Log)) {
  $Log = Join-Path $repoRoot "run\logs\latest.log"
} elseif (-not [System.IO.Path]::IsPathRooted($Log)) {
  $Log = Join-Path $repoRoot $Log
}

if (-not (Test-Path -LiteralPath $Log)) {
  Write-Error "Log not found: $Log"
  exit 1
}

$marker = "[flood-debug]"
$lines = Get-Content -LiteralPath $Log
$blockStarts = @()
for ($i = 0; $i -lt $lines.Count; $i++) {
  if ($lines[$i] -like "*${marker}*profile=*") {
    $blockStarts += $i
  }
}

if ($blockStarts.Count -eq 0) {
  # Fallback: any flood-debug line — find last run of contiguous matches
  $last = -1
  for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -like "*${marker}*") {
      $last = $i
    }
  }
  if ($last -lt 0) {
    Write-Error "No [flood-debug] lines in $Log"
    exit 1
  }
  $start = $last
  while ($start -gt 0 -and $lines[$start - 1] -like "*${marker}*") {
    $start--
  }
  for ($i = $start; $i -le $last; $i++) {
    Write-Output $lines[$i]
  }
  exit 0
}

$start = $blockStarts[$blockStarts.Count - 1]
$end = $start
for ($i = $start; $i -lt $lines.Count; $i++) {
  if ($lines[$i] -like "*${marker}*") {
    $end = $i
  } elseif ($i -gt $start) {
    break
  }
}

for ($i = $start; $i -le $end; $i++) {
  Write-Output $lines[$i]
}
