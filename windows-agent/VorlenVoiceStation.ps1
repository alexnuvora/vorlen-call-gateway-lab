# Vorlen Voice Station — non-dialling pilot. Windows PowerShell 5.1+
# Start: powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\VorlenVoiceStation.ps1
# This process does NOT place calls or alter the Android gateway.
param([switch]$SendShortcut, [switch]$Watch, [string]$InboxPath = "$env:LOCALAPPDATA\Vorlen\voice-station-inbox.json")
# Send the global hotkey directly, regardless of whether ChatGPT is already open.
# keybd_event is supported on Windows PowerShell 5.1 and invokes registered hotkeys.
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class VorlenHotkey {
 [DllImport("user32.dll", SetLastError = true)]
 public static extern void keybd_event(byte virtualKey, byte scanCode, uint flags, UIntPtr extraInfo);
}
"@
function Activate-Voice {
  $keyUp = [uint32]0x0002
  try {
    [VorlenHotkey]::keybd_event(0x11, 0, 0, [UIntPtr]::Zero) # Ctrl down
    Start-Sleep -Milliseconds 80
    [VorlenHotkey]::keybd_event(0x48, 0, 0, [UIntPtr]::Zero) # H down
    Start-Sleep -Milliseconds 80
    [VorlenHotkey]::keybd_event(0x48, 0, $keyUp, [UIntPtr]::Zero) # H up
    [VorlenHotkey]::keybd_event(0x11, 0, $keyUp, [UIntPtr]::Zero) # Ctrl up
    Write-Host 'Sent global Ctrl+H shortcut. Check that ChatGPT Voice opens. No call was placed.'
    return $true
  } catch {
    # Never leave Ctrl held when something fails.
    [VorlenHotkey]::keybd_event(0x11, 0, $keyUp, [UIntPtr]::Zero)
    Write-Warning $_.Exception.Message
    return $false
  }
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
