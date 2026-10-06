# Vorlen Call Gateway Lab

Experimental Android call-audio lab derived from the working Vorlen gateway. Production repository `alexnuvora/ai-call-gateway` is intentionally untouched.

This APK uses a separate Android application ID so it can coexist with the production gateway on the same device.

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
