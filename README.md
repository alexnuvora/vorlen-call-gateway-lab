# Vorlen Call Gateway Lab

Experimental Android call-audio lab derived from the working Vorlen gateway. Production repository `alexnuvora/ai-call-gateway` is intentionally untouched.

This APK uses a separate Android application ID so it can coexist with the production gateway on the same device.


## Why Wireless Debugging recovery permission disappeared after APK updates

The old GitHub Actions builds used **ephemeral debug signing keys** from disposable CI runners. Android cannot install a differently signed APK over the previous app, forcing a reinstall that resets `WRITE_SECURE_SETTINGS`, gateway credentials and ADB pairing identity. The new workflow supports a persistent **signed release APK**; configure four repository Actions secrets:

- `VORLEN_KEYSTORE_BASE64` — base64-encoded private release keystore (keep secret, never commit).
- `VORLEN_KEYSTORE_PASSWORD` — keystore password.
- `VORLEN_KEY_ALIAS` — key alias.
- `VORLEN_KEY_PASSWORD` — signing key password.

Generate a private keystore on your trusted PC with `keytool` and store a secure backup. Add those secrets through GitHub Settings → Secrets and variables → Actions. On each push, download the `vorlen-call-gateway-signed-release` artifact **instead of** the ephemeral debug build. Every signed release must use the same keystore and a higher `versionCode` for Android in-place upgrades.

Because the former APK is signed differently, perform **one final migration** from the debug build to the first release build. Record the gateway credential and laptop settings safely beforehand, uninstall the old debug app, install the first stable-signed release, pair Vorlen to wireless ADB once and use Termux to grant `WRITE_SECURE_SETTINGS` once. Future upgrades from the stable-signed releases should preserve the grant and pairing data.

A stable signing identity **does not bypass Android's permission model**, and Wireless Debugging may still require the phone to be on Wi-Fi. This project deliberately does not embed a private signing key or grant permissions silently.

## Optional one-time Wireless Debugging recovery (Samsung S24 FE)

The **Connect** button first reuses the saved wireless ADB pairing. If ADB is unavailable, the app can attempt a best-effort recovery of the Android `adb_wifi_enabled` setting **only if** the optional `WRITE_SECURE_SETTINGS` permission has been granted through trusted ADB. No root is required for the attempt; Samsung firmware may still block it.

With the phone connected to a trusted computer and ADB authorised, run once:

```powershell
adb shell pm grant com.vorlen.callgateway.lab android.permission.WRITE_SECURE_SETTINGS
adb shell dumpsys package com.vorlen.callgateway.lab | findstr WRITE_SECURE_SETTINGS
```

The permission grant is persistent unless app data/package identity or permissions change. It is **not** a guarantee Wireless Debugging survives a reboot. The first pairing still requires the Android-generated pairing port and six-digit code, entered through the app's Connection settings. Android may also require Wi-Fi and manual enabling in Developer options; the app must not claim a connected state if ADB, audio daemon or backend verification fails.

For security, use only your own trusted computer, do not permanently expose ADB over TCP on your network, and disable Wireless Debugging when the gateway is not in use. To revoke the optional grant:

```powershell
adb shell pm revoke com.vorlen.callgateway.lab android.permission.WRITE_SECURE_SETTINGS
```

## Audio lab

Experimental digital cellular-call audio work is isolated here; the production gateway remains unchanged.

## Windows / ChatGPT Voice routing

The bridge is full duplex, but ChatGPT Voice cannot hear cellular audio merely because it is played through normal laptop speakers. The phone RX stream must be written into the playback side of a **virtual audio cable**, and ChatGPT Voice must use that cable's paired recording endpoint as its microphone.

Use two separate virtual cables where possible:

- **Call RX → ChatGPT mic**
  - `laptop_bridge.py --rx-output` = playback side of virtual cable A.
  - ChatGPT Voice microphone = recording side of virtual cable A.
- **ChatGPT speech → Call TX**
  - ChatGPT Voice speaker/output = playback side of virtual cable B.
  - `laptop_bridge.py --tx-input` = recording side of virtual cable B.

This separation avoids feeding caller audio back into the call.

Example:

```powershell
python desktop/laptop_bridge.py --list-devices
python desktop/laptop_bridge.py --bind 0.0.0.0 --port 28761 --tx-input "CABLE-B Output" --rx-output "CABLE-A Input"
```

Optional local monitoring:

```powershell
python desktop/laptop_bridge.py --bind 0.0.0.0 --port 28761 --tx-input "CABLE-B Output" --rx-output "CABLE-A Input" --monitor-output "Speakers"
```

The bridge prints live `tx` and `rx` sample rates and dBFS levels every five seconds. During ringback, voicemail or caller speech, `rx` should be close to 48,000 samples/s and above the silence floor. If `rx` is active but ChatGPT hears nothing, the selected ChatGPT microphone is not the paired recording endpoint for `--rx-output`.

Legacy `--input` and `--output` flags remain accepted as aliases for `--tx-input` and `--rx-output`.
