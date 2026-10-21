# Vorlen laptop bridge

This bridge moves raw 48 kHz mono PCM16 audio between the S24 FE gateway and a
laptop running ChatGPT Voice.

## Laptop setup

1. Install Python 3.
2. Install PortAudio/sounddevice support:
   `python -m pip install sounddevice`
3. Install a virtual audio cable/loopback device suitable for your OS.
4. List devices:
   `python desktop/laptop_bridge.py --list-devices`
5. Start the bridge, selecting:
   - `--input`: the virtual device carrying ChatGPT Voice playback.
   - `--output`: the virtual device ChatGPT Voice uses as its microphone.
   Example:
   `python desktop/laptop_bridge.py --input "CABLE Output" --output "CABLE Input"`

The listener defaults to TCP port 28761. Allow that port on the laptop firewall
for the private/local network only.

## Phone

The Android gateway side connects to the laptop's LAN IPv4 address and port
28761. Caller audio is sent phone -> laptop; laptop ChatGPT audio is sent
laptop -> phone. The cellular side remains handled by the existing shell audio
engine.

## Recovery update (0.5.2)

Update **both** this laptop script and the Android lab APK (version code 22).
Keep the existing virtual-cable selections and use the stable-signed APK with
the same signing identity as your installed app.

The listener now owns a separate spawned audio process per phone connection.
If either direction stops completing PCM transfers for 12 seconds (including
a frozen audio driver or stream cleanup), it closes the connection and terminates
that worker. The phone reconnects within the existing active call. Android also
watches both pumps and closes stalled sockets after 10 seconds; its 8-second
read timeout remains in place. Recovery can therefore cause a noticeable audio
gap; this is not a promise of uninterrupted audio during Wi-Fi/device failure.

Silence is valid PCM and does not trigger recovery. The optional local monitor
still belongs to the session: if its driver hangs, the session will be restarted.
The bridge does not detect a muted or incorrectly selected ChatGPT microphone,
or control ChatGPT Voice itself.

Run regression tests without audio hardware:
`python -m unittest discover -s desktop/tests -v`

### Hardware acceptance test

1. Start the updated script with the same two virtual cables as before.
2. Place an authorised test call and check speech in both directions.
3. Briefly disconnect laptop Wi-Fi, then reconnect. Verify audio returns on the
   **same** cellular call and a reconnect event is recorded.
4. Restart the laptop bridge during the call and verify the same recovery.
5. Run a 20-minute conversation, including periods of silence, then hang up and
   place a second test call. Confirm no duplicate/old audio and no stuck worker.
6. If audio is lost, keep the timestamp and bridge console output. Nonzero RX
   traffic with audible levels but no ChatGPT response points to routing or the
   ChatGPT session, which is outside the bridge's transport watchdog.

Do not replace files or restart the bridge during a client call.
