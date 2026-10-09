# Vorlen Windows Voice Station — opt-in pilot

**Existing manual VoiceMeeter/Samsung workflow is unchanged.** The Windows script is a separate proof-of-concept, **not** a production campaign agent, and does not place calls, touch Supabase, edit VoiceMeeter, or change ChatGPT settings.

## Test shortcut first

On your Windows laptop with the global Ctrl+H shortcut configured:

1. Keep your current VoiceMeeter setup working. ChatGPT does not need to be open, but the Ctrl+H global shortcut must be registered and able to launch Voice.
2. Open PowerShell in this directory and run:

   ```powershell
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\VorlenVoiceStation.ps1 -SendShortcut
   ```

3. Verify ChatGPT Voice opens after Ctrl+H. The script **cannot detect whether Voice really started**, so do not dial a client automatically on this result.

If your shortcut is not system-wide or its shortcut application is not running, Windows may send Ctrl+H to the focused app instead. Test with ChatGPT closed and no text editor/browser focused. The pilot does not verify that Voice successfully launched.

## Local inbox smoke test (no Supabase and no dialling)

Run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\VorlenVoiceStation.ps1 -Watch
```

In another PowerShell window send the local test message:

```powershell
$path = Join-Path $env:LOCALAPPDATA 'Vorlen\voice-station-inbox.json'
New-Item -ItemType Directory -Force -Path (Split-Path $path) | Out-Null
@{ id=[guid]::NewGuid().ToString(); action='prepare_voice' } | ConvertTo-Json | Set-Content -Path $path -Encoding UTF8
```

This simulates a remote command with an inert local file. Nothing is sent to Supabase. Ctrl+C ends the watcher.

## Planned gated integration

Only after Ctrl+H has been verified on the actual laptop, add a Supabase command queue with per-station authentication, anti-replay IDs, TTL and explicit session readiness. The first cloud command should be `prepare_voice` **not** `dial`, and no call may be released until ChatGPT Voice and audio bridge are confirmed ready by the operator. Campaign stop/pause must work without reliance on screen automation.

Do not store Supabase service keys or gateway credentials in this script.
