#!/usr/bin/env python3
"""Vorlen laptop audio bridge.

Full-duplex 48 kHz mono PCM16 transport between Windows audio devices and the
Vorlen Android cellular gateway.

Usage:
  python laptop_bridge.py --list-devices
  python laptop_bridge.py --bind 0.0.0.0 --port 28761 --tx-input DEVICE --rx-output DEVICE
  python laptop_bridge.py --bind 0.0.0.0 --port 28761 --tx-input DEVICE --rx-output DEVICE --monitor-output DEVICE

Audio routing:
  --rx-output is where CALLER/RINGBACK/VOICEMAIL audio is played. For ChatGPT
  Voice to hear it, choose the playback side of a virtual audio cable and set
  ChatGPT Voice's microphone to that cable's recording side.

  --tx-input is what is sent back into the cellular call. Choose the recording
  side of a second virtual cable and set ChatGPT Voice's speaker/output to that
  cable's playback side.

  Two separate virtual cables are strongly recommended to avoid feedback.
"""
import argparse
import audioop
from array import array
import math
import socket
import struct
import threading
import time

try:
    import sounddevice as sd
except ImportError:
    raise SystemExit("Install dependency first: python -m pip install sounddevice")

RATE = 48000
CHANNELS = 1
DEFAULT_INPUT_CHANNELS = 2
DTYPE = "int16"
SAMPLE_BYTES = 2
FRAMES = 480                 # 10 ms packets: lower caller->ChatGPT latency
FRAME_BYTES = FRAMES * SAMPLE_BYTES
MAGIC = b"VOR1"


EXPECTED_DISCONNECT_ERRNOS = {10053, 10054, 10058}


def is_expected_disconnect(exc):
    """Return True for normal peer-close/reset conditions on Windows/TCP.

    Android tears down the bridge socket when a cellular call ends. Windows may
    surface that orderly lifecycle transition as WSAECONNRESET (10054) rather
    than recv() returning b''. BrokenPipe/ConnectionAborted are equivalent from
    the listener's point of view: the current bridge is over and the server
    should immediately go back to accept().
    """
    if isinstance(exc, (ConnectionResetError, BrokenPipeError, ConnectionAbortedError)):
        return True
    if isinstance(exc, ConnectionError) and str(exc) == "phone disconnected":
        return True
    return getattr(exc, "winerror", None) in EXPECTED_DISCONNECT_ERRNOS


def recvall(sock, n):
    b = bytearray()
    while len(b) < n:
        x = sock.recv(n - len(b))
        if not x:
            raise ConnectionError("phone disconnected")
        b.extend(x)
    return bytes(b)


def send_frame(sock, payload, lock):
    with lock:
        sock.sendall(struct.pack("!I", len(payload)) + payload)


def resolve_device(value, kind):
    if isinstance(value, str) and value.strip().isdigit():
        index = int(value.strip())
        devices = sd.query_devices()
        if index < 0 or index >= len(devices):
            raise ValueError(f"{kind} device index {index} is out of range (0..{len(devices)-1})")
        info = devices[index]
        channels_key = "max_input_channels" if kind == "input" else "max_output_channels"
        if info[channels_key] < 1:
            raise ValueError(f"device {index} ({info['name']}) has no {kind} channels")
        return index
    return value


def device_description(device, kind):
    info = sd.query_devices(device, kind)
    hostapi = sd.query_hostapis(info["hostapi"])["name"]
    return (
        f"{info['name']} | host={hostapi} | defaultRate={info['default_samplerate']:.0f} Hz | "
        f"channels={info['max_input_channels'] if kind == 'input' else info['max_output_channels']}"
    )


def choose_input_channels(device):
    info = sd.query_devices(device, "input")
    # Virtual cables are commonly mono or stereo. Prefer stereo when available
    # because ChatGPT/browser output devices frequently expose two channels.
    return 2 if info["max_input_channels"] >= 2 else 1


def validate_audio(input_dev, output_dev, monitor_dev=None):
    input_channels = choose_input_channels(input_dev)
    sd.check_input_settings(device=input_dev, channels=input_channels, dtype=DTYPE, samplerate=RATE)
    sd.check_output_settings(device=output_dev, channels=CHANNELS, dtype=DTYPE, samplerate=RATE)
    if monitor_dev is not None:
        sd.check_output_settings(device=monitor_dev, channels=CHANNELS, dtype=DTYPE, samplerate=RATE)
    print("GPT -> call input :", device_description(input_dev, "input"))
    print("Call -> GPT output:", device_description(output_dev, "output"))
    if monitor_dev is not None:
        print("Local monitor     :", device_description(monitor_dev, "output"))
    print(f"Capture: {RATE} Hz, {input_channels}ch PCM16 -> mono")
    print(f"Wire   : {RATE} Hz, mono, signed PCM16, {FRAMES} frames/{FRAME_BYTES} bytes per 10 ms")
    print("IMPORTANT: ChatGPT microphone must be the recording side paired with --rx-output.")
    print("IMPORTANT: ChatGPT speaker/output must feed the recording device selected by --tx-input.")
    return input_channels


def input_to_mono_pcm16(pcm, channels):
    expected = FRAMES * channels * SAMPLE_BYTES
    if len(pcm) != expected:
        raise RuntimeError(f"capture frame size mismatch: got {len(pcm)} bytes, expected {expected}")
    if channels == 1:
        return pcm
    samples = array("h")
    samples.frombytes(pcm)
    mono = array("h", [0]) * FRAMES
    j = 0
    for i in range(FRAMES):
        mono[i] = (samples[j] + samples[j + 1]) // 2
        j += channels
    return mono.tobytes()


def rms_dbfs(pcm):
    if not pcm:
        return -120.0
    rms = audioop.rms(pcm, SAMPLE_BYTES)
    if rms <= 0:
        return -120.0
    return 20.0 * math.log10(rms / 32768.0)


def handle(conn, input_dev, output_dev, input_channels, monitor_dev=None):
    # Voice PCM is sent in 10 ms packets. Disable Nagle so a short packet is never
    # held waiting for a previous ACK; latency matters more than throughput here.
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    conn.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    # Best-effort Windows keepalive tuning. This helps detect dead Wi-Fi/NAT
    # sessions without killing healthy long calls. Unsupported platforms simply
    # keep the default SO_KEEPALIVE behaviour.
    try:
        conn.ioctl(socket.SIO_KEEPALIVE_VALS, (1, 15000, 3000))
    except (AttributeError, OSError):
        pass
    print("Phone connected:", conn.getpeername(), "TCP_NODELAY=1 KEEPALIVE=1")
    hello = recvall(conn, 4)
    if hello == b"VORP":
        conn.sendall(b"PONG")
        print("Bridge preflight OK")
        return
    if hello != MAGIC:
        raise ConnectionError("bad handshake")
    conn.sendall(MAGIC)

    lock = threading.Lock()
    stop = threading.Event()
    stats_lock = threading.Lock()
    stats = {
        "tx_frames": 0, "tx_bytes": 0, "tx_overflows": 0, "tx_db": -120.0,
        "rx_frames": 0, "rx_bytes": 0, "rx_db": -120.0,
    }

    out = sd.RawOutputStream(samplerate=RATE, channels=CHANNELS, dtype=DTYPE,
                             blocksize=FRAMES, device=output_dev, latency="low")
    monitor = None
    if monitor_dev is not None:
        monitor = sd.RawOutputStream(samplerate=RATE, channels=CHANNELS, dtype=DTYPE,
                                     blocksize=FRAMES, device=monitor_dev, latency="low")
    inp = sd.RawInputStream(samplerate=RATE, channels=input_channels, dtype=DTYPE,
                            blocksize=FRAMES, device=input_dev, latency="low")
    out.start()
    if monitor is not None:
        monitor.start()
    inp.start()

    if abs(float(inp.samplerate) - RATE) > 1 or abs(float(out.samplerate) - RATE) > 1:
        raise RuntimeError(f"PortAudio rate mismatch: input={inp.samplerate}, output={out.samplerate}, expected={RATE}")
    print(f"Audio ready: input={input_dev} @ {inp.samplerate:.0f} Hz, output={output_dev} @ {out.samplerate:.0f} Hz; full-duplex low-latency bridge active")

    def rx():
        try:
            while not stop.is_set():
                n = struct.unpack("!I", recvall(conn, 4))[0]
                if n > 192000:
                    raise ConnectionError("invalid frame")
                if not n:
                    continue
                payload = recvall(conn, n)
                if len(payload) % SAMPLE_BYTES:
                    raise ConnectionError(f"unaligned caller PCM frame: {len(payload)} bytes")
                out.write(payload)
                if monitor is not None:
                    monitor.write(payload)
                with stats_lock:
                    stats["rx_frames"] += 1
                    stats["rx_bytes"] += len(payload)
                    stats["rx_db"] = rms_dbfs(payload)
        except Exception as e:
            if is_expected_disconnect(e):
                print("Phone -> laptop audio closed")
            else:
                print("Phone -> laptop audio failed:", repr(e))
        finally:
            stop.set()

    def reporter():
        last_tx = last_rx = 0
        last_t = time.monotonic()
        while not stop.wait(5.0):
            now = time.monotonic()
            elapsed = now - last_t
            with stats_lock:
                txb, rxb = stats["tx_bytes"], stats["rx_bytes"]
                txdb, rxdb = stats["tx_db"], stats["rx_db"]
                ov = stats["tx_overflows"]
            tx_rate = (txb - last_tx) / SAMPLE_BYTES / elapsed
            rx_rate = (rxb - last_rx) / SAMPLE_BYTES / elapsed
            print(f"AUDIO tx={tx_rate:.0f} samp/s {txdb:.1f} dBFS rx={rx_rate:.0f} samp/s {rxdb:.1f} dBFS overflows={ov}")
            last_tx, last_rx, last_t = txb, rxb, now

    threading.Thread(target=rx, daemon=True).start()
    threading.Thread(target=reporter, daemon=True).start()

    try:
        while not stop.is_set():
            data, overflow = inp.read(FRAMES)
            payload = input_to_mono_pcm16(bytes(data), input_channels)
            if len(payload) != FRAME_BYTES:
                raise RuntimeError(f"downmix frame size mismatch: got {len(payload)} bytes, expected {FRAME_BYTES}")
            with stats_lock:
                stats["tx_frames"] += 1
                stats["tx_bytes"] += len(payload)
                stats["tx_db"] = rms_dbfs(payload)
                if overflow:
                    stats["tx_overflows"] += 1
            send_frame(conn, payload, lock)
    finally:
        stop.set()
        for stream in (inp, out, monitor):
            if stream is None:
                continue
            try:
                stream.stop()
            except Exception:
                pass
            stream.close()
        conn.close()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--bind", default="0.0.0.0")
    p.add_argument("--port", type=int, default=28761)
    p.add_argument("--tx-input", "--input", dest="input", help="Recording device carrying ChatGPT Voice output into the call")
    p.add_argument("--rx-output", "--output", dest="output", help="Playback device whose paired recording endpoint is selected as ChatGPT Voice microphone")
    p.add_argument("--monitor-output", help="Optional physical speaker/headset output to monitor caller audio locally")
    p.add_argument("--list-devices", action="store_true")
    a = p.parse_args()

    if a.list_devices:
        print(sd.query_devices())
        return
    if a.input is None or a.output is None:
        p.error("--input and --output are required (use --list-devices first)")

    input_dev = resolve_device(a.input, "input")
    output_dev = resolve_device(a.output, "output")
    monitor_dev = resolve_device(a.monitor_output, "output") if a.monitor_output is not None else None
    input_channels = validate_audio(input_dev, output_dev, monitor_dev)

    # Supervisor loop: never let a transient listener/session exception kill the
    # desktop bridge process. Re-create the listening socket if Windows/network
    # state invalidates it, while preserving immediate readiness after normal
    # cellular call teardown.
    while True:
        try:
            with socket.socket() as s:
                s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                s.bind((a.bind, a.port))
                s.listen(4)
                print(f"Vorlen laptop bridge listening on {a.bind}:{a.port}")
                while True:
                    try:
                        c, _ = s.accept()
                    except OSError as e:
                        print("Listener accept failed; rebuilding listener:", repr(e))
                        break
                    try:
                        handle(c, input_dev, output_dev, input_channels, monitor_dev)
                    except Exception as e:
                        if is_expected_disconnect(e):
                            print("Phone bridge closed; listening for next connection")
                        else:
                            print("Bridge session failed:", repr(e))
                        try:
                            c.close()
                        except Exception:
                            pass
                        if not is_expected_disconnect(e):
                            time.sleep(.25)
        except KeyboardInterrupt:
            raise
        except OSError as e:
            print("Listener failed; retrying:", repr(e))
            time.sleep(1.0)


if __name__ == "__main__":
    main()
