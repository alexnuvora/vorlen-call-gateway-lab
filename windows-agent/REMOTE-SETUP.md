# Vorlen Remote Voice Station — opt-in test

This is an **independent** script. Do not replace or uninstall VoiceMeeter, the Samsung gateway, or your working `VorlenVoiceStation.ps1` manual shortcut test. No automatic campaign dialing is enabled.

## 1. Setup the laptop

Download `VorlenRemoteVoiceStation.ps1` from this folder. On the Windows laptop in a PowerShell window:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\VorlenRemoteVoiceStation.ps1 -Setup
```

The script generates a 256-bit random secret and saves it encrypted via Windows DPAPI under the current user profile. It prints **only the SHA-256 hash** of that secret, plus the station code `main-windows`. Keep the actual local secret private. If you run Setup again, the secret changes and you must register the new hash.

## 2. Register the hash with Vorlen MCP

From any authorised **owner** ChatGPT account connected to Vorlen, say:
"Register Vorlen Windows voice station `main-windows` with this SHA-256 hash: [paste the 64-character hash]".
The MCP uses `register_voice_station` to store the hash in the workspace. This does not start any call.

## 3. Start the laptop listener

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\VorlenRemoteVoiceStation.ps1 -Watch
```

Keep it running in a logged-in, unlocked Windows desktop session. It checks for authorised commands every three seconds. Incoming `prepare_voice` requests expire after 90 seconds. It sends the global Ctrl+H shortcut then reports **shortcut sent** — not proof that ChatGPT Voice actually started.

## 4. Remote smoke test

From ChatGPT on another device connected to Vorlen as owner/manager, ask:
"Using Vorlen, prepare the Windows Voice Station." It invokes `prepare_voice_station` and queues the command.

Your laptop should receive the request and open ChatGPT Voice through Ctrl+H.

### Important limitations

- This does not start a real campaign or dial any customers.
- There is no automatic audio readiness validation. A shortcut acknowledgement is not a Voice readiness signal.
- The remote station uses an authenticated custom Edge Function and an isolated Supabase queue. The public endpoint validates a 256-bit secret against its stored SHA-256 hash and only accepts `poll` and `ack` actions.
- Never commit the local encrypted secret file, and do not send the raw secret to ChatGPT.
- Existing manual operation stays available if the pilot fails. Ctrl+C stops the watcher.
