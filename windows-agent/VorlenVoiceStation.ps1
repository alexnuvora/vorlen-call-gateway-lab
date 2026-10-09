# Vorlen Voice Station — non-dialling pilot. Windows PowerShell 5.1+
# Start: powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\VorlenVoiceStation.ps1
# This process does NOT place calls or alter the Android gateway.
param([switch]$SendShortcut, [switch]$Watch, [string]$InboxPath = "$env:LOCALAPPDATA\Vorlen\voice-station-inbox.json")
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class VorlenWin {
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
}
"@
function Activate-Voice {
  $candidate = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and ($_.MainWindowTitle -match 'ChatGPT') } | Select-Object -First 1
  if (-not $candidate) {
    Write-Warning 'No open ChatGPT window found. Open ChatGPT yourself and retry. No calls have been placed.'
    return $false
  }
  [VorlenWin]::ShowWindow($candidate.MainWindowHandle, 9) | Out-Null
  [VorlenWin]::SetForegroundWindow($candidate.MainWindowHandle) | Out-Null
  Start-Sleep -Milliseconds 700
  if ([System.Diagnostics.Process]::GetCurrentProcess().Id -eq $candidate.Id) { throw 'Invalid foreground target' }
  [System.Windows.Forms.SendKeys]::SendWait('^h')
  Write-Host 'Sent Ctrl+H to ChatGPT. Confirm Voice is active manually. No call has been placed.'
  return $true
}
if ($SendShortcut) { [void](Activate-Voice); exit }
if (-not $Watch) {
  Write-Host 'Vorlen Voice Station — safe pilot. Use -SendShortcut to test Ctrl+H, or -Watch for local command inbox.'
  exit
}
Write-Host "Local watch enabled at $InboxPath (no network access; no remote dialling)."
$dir = Split-Path -Parent $InboxPath
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$lastId=''
while ($true) {
 try {
   if (Test-Path $InboxPath) {
     $cmd = Get-Content $InboxPath -Raw | ConvertFrom-Json
     if ($cmd.action -eq 'prepare_voice' -and $cmd.id -and $cmd.id -ne $lastId) {
       $lastId=[string]$cmd.id
       [void](Activate-Voice)
     }
   }
 } catch { Write-Warning $_.Exception.Message }
 Start-Sleep -Seconds 2
}
