# Vorlen Windows Voice Station Remote Pilot - prepare_voice only. PowerShell 5.1+
# Does NOT dial, start a campaign or modify VoiceMeeter/Samsung.
param([switch]$Setup,[switch]$Watch,[string]$StationCode='main-windows')
$ErrorActionPreference='Stop'
$Endpoint='https://mzkaodoruhklzluikagy.supabase.co/functions/v1/vorlen-voice-station'
$Store=Join-Path $env:LOCALAPPDATA 'Vorlen\voice-station-secret.txt'
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class VorlenRemoteHotkey {
 [DllImport("user32.dll")] public static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);
}
"@
function Send-VoiceShortcut {
 $up=[uint32]2
 try {
  [VorlenRemoteHotkey]::keybd_event(0x11,0,0,[UIntPtr]::Zero)
  Start-Sleep -Milliseconds 90
  [VorlenRemoteHotkey]::keybd_event(0x48,0,0,[UIntPtr]::Zero)
  Start-Sleep -Milliseconds 90
  [VorlenRemoteHotkey]::keybd_event(0x48,0,$up,[UIntPtr]::Zero)
  [VorlenRemoteHotkey]::keybd_event(0x11,0,$up,[UIntPtr]::Zero)
  Write-Host 'Sent Ctrl+H. This does not prove that ChatGPT Voice is active.'
  return $true
 } catch {
  [VorlenRemoteHotkey]::keybd_event(0x11,0,$up,[UIntPtr]::Zero)
  Write-Warning $_.Exception.Message
  return $false
 }
}
function Digest([string]$inputText) {
 $bytes=[Text.Encoding]::UTF8.GetBytes($inputText)
 $sha=[Security.Cryptography.SHA256]::Create()
 try { return -join ($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) }
 finally { $sha.Dispose() }
}
if ($Setup) {
 [IO.Directory]::CreateDirectory((Split-Path -Parent $Store)) | Out-Null
 $bytes=New-Object byte[] 32
 $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
 try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
 $secret=(-join ($bytes | ForEach-Object { $_.ToString('x2') }))
 # Encrypted to the current Windows user with DPAPI.
 $secure=ConvertTo-SecureString $secret -AsPlainText -Force
 ConvertFrom-SecureString $secure | Set-Content -LiteralPath $Store -Encoding ASCII
 Write-Host 'Station code:' $StationCode
 Write-Host 'Register this SHA-256 hash via Vorlen MCP register_voice_station (not the secret):'
 Write-Host (Digest $secret)
 Write-Host 'Local secret encrypted for this Windows user. Do not share or commit the file.'
 exit
}
if (-not $Watch) {
 Write-Host 'Use -Setup once and register the displayed hash, then -Watch.'
 exit
}
if (-not (Test-Path -LiteralPath $Store)) { throw 'Run -Setup first.' }
$secure=Get-Content -LiteralPath $Store -Raw | ConvertTo-SecureString
$bstr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $secret=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
$headers=@{'X-Vorlen-Station-Secret'=$secret}
Write-Host 'Vorlen remote prepare-only watcher active. Station:' $StationCode
Write-Host 'No automatic call requests will be made. Ctrl+C to stop.'
while ($true) {
 try {
  $body=@{action='poll';station_code=$StationCode} | ConvertTo-Json -Compress
  $resp=Invoke-RestMethod -Uri $Endpoint -Method Post -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 12
  if ($null -ne $resp.command -and $resp.command.action -eq 'prepare_voice') {
   Write-Host 'Received prepare_voice command:' $resp.command.command_id
   $ok=Send-VoiceShortcut
   $ack=@{action='ack';station_code=$StationCode;command_id=$resp.command.command_id;success=[bool]$ok;message='Shortcut sent; Voice readiness NOT confirmed'} | ConvertTo-Json -Compress
   Invoke-RestMethod -Uri $Endpoint -Method Post -Headers $headers -ContentType 'application/json' -Body $ack -TimeoutSec 12 | Out-Null
  }
 } catch { Write-Warning ("Remote poll/ack: "+$_.Exception.Message) }
 Start-Sleep -Seconds 3
}
