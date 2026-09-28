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
